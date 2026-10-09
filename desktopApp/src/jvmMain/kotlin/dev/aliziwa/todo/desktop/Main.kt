package dev.aliziwa.todo.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.aliziwa.todo.App
import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.jvmDatabaseBuilder
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.desktop.llm.LlamaCppEngine
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.platform.StickySummary
import dev.aliziwa.todo.platform.SystemClock
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

private const val TAG = "main"

/** Everything the composition needs, assembled before Compose starts. */
private class DesktopShell(
    val graph: AppGraph,
    val ui: DesktopUiController,
    val alarms: DesktopAlarmScheduler,
    val alerts: DesktopAlertPresenter,
    val sound: JvmSoundPlayer,
    val capabilities: DesktopCapabilities,
    val prefs: DesktopPrefs,
    val clock: Clock,
    val scope: CoroutineScope,
    val debug: Boolean,
)

fun main(args: Array<String>) {
    // Dock / menu-bar name before any AWT class loads.
    System.setProperty("apple.awt.application.name", "Personal Todo")

    val paths = DesktopPaths()
    DesktopLog.attachFile(paths.dataDir)
    DesktopLog.i(TAG, "starting; args=${args.joinToString(" ")}")

    // Headless maintenance commands. The settings UI (shared agent) calls
    // PlatformCapabilities.setLaunchAtLogin(); these exist so launch-at-login can be installed,
    // inspected and removed from a terminal while that UI is still being written.
    if (args.any { it in LOGIN_AGENT_COMMANDS }) {
        runLoginAgentCommand(args, paths)
        return
    }

    val requestedRoute = args.firstOrNull { it.startsWith("--route=") }?.removePrefix("--route=")
    val startHidden = args.contains("--background") || System.getenv("TODO_START_HIDDEN") == "1"

    // ---- single instance: a second launch focuses the resident one and exits -------------------
    val guard = SingleInstance.acquire(
        paths,
        command = if (requestedRoute != null) "${SingleInstance.CMD_OPEN} $requestedRoute" else SingleInstance.CMD_FRONT,
    )
    if (!guard.isPrimary) {
        DesktopLog.i(TAG, "another instance is already running; brought it to the front and exiting")
        return
    }
    Runtime.getRuntime().addShutdownHook(Thread({ runCatching { guard.close() } }, "todo-shutdown"))

    // ---- platform services --------------------------------------------------------------------
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = DesktopPrefs(paths.prefsFile)
    val ui = DesktopUiController()
    val sound = JvmSoundPlayer(appScope)
    val alerts = DesktopAlertPresenter(ui, sound, soundEnabled = { prefs.getBool("sound.enabled", true) })
    val capabilities = DesktopCapabilities(paths, ui)
    val debug = System.getProperty("todo.debug")?.toBoolean() == true || System.getenv("TODO_DEBUG") == "1"

    lateinit var alarms: DesktopAlarmScheduler
    alarms = DesktopAlarmScheduler(
        scope = appScope,
        clock = SystemClock,
        onAlarm = { taskId -> TodoApp.onAlarmFired(taskId) },
        onDailyPrompt = { TodoApp.onDailyPromptFired() },
        onWake = { lost ->
            DesktopLog.i(TAG, "woke after ${lost}ms — resyncing alarms and re-evaluating due state")
            SharedBridge.resyncAlarms()
            TodoApp.onAppForegrounded()
        },
    )

    val llm = LlamaCppEngine(appScope)

    val graph = TodoApp.start(
        PlatformServices(
            alarms = alarms,
            alerts = alerts,
            sound = sound,
            paths = paths,
            clock = SystemClock,
            http = DesktopHttp,
            capabilities = capabilities,
            localLlmEngine = llm,
        ),
        jvmDatabaseBuilder(paths.databasePath),
    )
    alarms.start()

    // Debug-only startup hook (TODO_DEBUG=1 TODO_DEBUG_ALERT_SECONDS=90): inserts a real task so
    // the whole chain — repository -> DueEngine -> AlarmScheduler -> AlertPresenter -> alert window
    // + doorbell — runs exactly as it does for a user-created task.
    if (debug) {
        (System.getProperty("todo.debug.alertSeconds") ?: System.getenv("TODO_DEBUG_ALERT_SECONDS"))
            ?.toIntOrNull()
            ?.let { seconds -> appScope.launch { createDebugTask(graph, seconds) } }
    }

    guard.listen { command, argument ->
        when (command) {
            SingleInstance.CMD_OPEN -> ui.openMainWindow(argument)
            else -> ui.openMainWindow()
        }
        TodoApp.onAppForegrounded()
    }

    if (startHidden) {
        DesktopLog.i(TAG, "started in background (LaunchAgent); staying in the menu bar")
        ui.hideMainWindow()
    } else if (requestedRoute != null) {
        ui.openMainWindow(requestedRoute)
    }

    // Catch anything that came due while the process was not running, and let the shared layer
    // arm its alarms / publish the first sticky summary.
    SharedBridge.resyncAlarms()
    TodoApp.onAppForegrounded()

    val shell = DesktopShell(graph, ui, alarms, alerts, sound, capabilities, prefs, SystemClock, appScope, debug)

    // exitProcessOnExit = false so the teardown below actually runs.
    application(exitProcessOnExit = false) { DesktopApplication(shell) }

    DesktopLog.i(TAG, "shutting down")
    runCatching { alarms.shutdown() }
    runCatching { sound.release() }
    runCatching { appScope.launch { llm.unload() } }
    runCatching { guard.close() }
    // Compose/AWT keeps non-daemon threads alive; be explicit.
    kotlin.system.exitProcess(0)
}

private val LOGIN_AGENT_COMMANDS =
    setOf("--install-login-agent", "--remove-login-agent", "--check-login-agent")

/** Runs a LaunchAgent command and prints the outcome; used instead of starting the UI. */
private fun runLoginAgentCommand(args: Array<String>, paths: DesktopPaths) {
    val capabilities = DesktopCapabilities(paths, DesktopUiController())
    kotlinx.coroutines.runBlocking {
        when {
            args.contains("--install-login-agent") -> {
                val ok = capabilities.setLaunchAtLogin(true)
                println("install-login-agent: ok=" + ok)
                println("  plist   = " + capabilities.plistFile.absolutePath)
                println("  program = " + capabilities.resolveProgramArguments())
                println("  packaged= " + capabilities.isPackaged())
            }
            args.contains("--remove-login-agent") -> {
                println("remove-login-agent: ok=" + capabilities.setLaunchAtLogin(false))
            }
            else -> {
                println("login-agent enabled=" + capabilities.isLaunchAtLoginEnabled())
                println("  plist   = " + capabilities.plistFile.absolutePath +
                    " exists=" + capabilities.plistFile.exists())
                println("  program = " + capabilities.resolveProgramArguments())
            }
        }
    }
}


/**
 * Debug-only: creates a real DAY task whose due minute is the one containing `now + seconds`.
 * Task times have minute granularity, so the alert lands on that minute boundary.
 */
private suspend fun createDebugTask(graph: AppGraph, seconds: Int): String? {
    val zone = runCatching { ZoneId.of(SystemClock.timeZoneId()) }.getOrElse { ZoneId.systemDefault() }
    val due = Instant.ofEpochMilli(SystemClock.nowEpochMillis() + seconds * 1_000L).atZone(zone)
    val id = graph.taskRepository.create(
        TaskDraft(
            title = "Debug: pick up kids",
            notes = "created by the desktop debug hook",
            priority = Priority.HIGH,
            scope = TaskScope.DAY,
            date = LocalDate(due.year, due.monthValue, due.dayOfMonth),
            timeMinutes = due.hour * 60 + due.minute,
        ),
    )
    DesktopLog.i(TAG, "debug: created task $id due ${due.hour}:${due.minute} local")
    return id
}

@Composable
private fun ApplicationScope.DesktopApplication(shell: DesktopShell) {
    val ui = shell.ui
    val scope = rememberCoroutineScope()

    val sticky by ui.sticky.collectAsState()
    val alert by ui.alert.collectAsState()
    val dailyPromptVisible by ui.dailyPromptVisible.collectAsState()
    val mainVisible by ui.mainWindowVisible.collectAsState()
    val route by ui.route.collectAsState()
    val routeNonce by ui.routeNonce.collectAsState()
    val frontNonce by ui.frontNonce.collectAsState()
    val quitRequested by ui.quitRequested.collectAsState()

    // One ticker drives every countdown in the shell (tray tooltip, menu header).
    var nowMillis by remember { mutableStateOf(shell.clock.nowEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMillis = shell.clock.nowEpochMillis()
        }
    }

    if (quitRequested) {
        LaunchedEffect(Unit) {
            DesktopLog.i(TAG, "quit requested from the tray")
            exitApplication()
        }
    }

    // ---- menu bar ------------------------------------------------------------------------------
    val dueNow = (sticky?.dueNowCount ?: 0) > 0
    val headline = stickyHeadline(sticky, nowMillis)
    Tray(
        icon = rememberVectorPainter(if (dueNow) TrayIcons.DueNow else TrayIcons.Idle),
        tooltip = headline,
        onAction = { ui.openMainWindow() },
        menu = {
            // macOS does not render AWT tray tooltips, so the summary is also the first menu item.
            Item(headline, onClick = { ui.openMainWindow("today") })
            Separator()
            Item("Open", onClick = { ui.openMainWindow("today") })
            Item("Add task", onClick = { ui.openMainWindow("add") })
            Item("Plan my day", onClick = { ui.openMainWindow("plan") })
            Item("Chat", onClick = { ui.openMainWindow("chat") })
            Separator()
            if (shell.debug) {
                Item("Debug: task due in ~1 min", onClick = {
                    shell.scope.launch { createDebugTask(shell.graph, 90) }
                })
                Item("Debug: task due now", onClick = {
                    shell.scope.launch { createDebugTask(shell.graph, 0) }
                })
                Item("Debug: plan my day window", onClick = { shell.alerts.showDailyPrompt() })
                Separator()
            }
            Item("Quit", onClick = { ui.requestQuit() })
        },
    )

    // ---- main window (close hides it; the process stays resident) -------------------------------
    val mainState = rememberWindowState(
        size = DpSize(
            shell.prefs.getFloat(KEY_W, 1_120f).coerceAtLeast(480f).dp,
            shell.prefs.getFloat(KEY_H, 780f).coerceAtLeast(360f).dp,
        ),
        position = shell.prefs.let { p ->
            val x = p.getFloat(KEY_X, Float.NaN)
            val y = p.getFloat(KEY_Y, Float.NaN)
            if (x.isNaN() || y.isNaN()) WindowPosition(Alignment.Center) else WindowPosition(x.dp, y.dp)
        },
        placement = if (shell.prefs.getBool(KEY_MAXIMIZED, false)) WindowPlacement.Maximized else WindowPlacement.Floating,
    )

    // Persist geometry (debounced) whenever the user moves/resizes the window.
    LaunchedEffect(mainState) {
        snapshotFlow { Triple(mainState.size, mainState.position, mainState.placement) }
            .collectLatest { (size, position, placement) ->
                delay(400)
                val absolute = position as? WindowPosition.Absolute
                shell.prefs.put(
                    KEY_W to size.width.value,
                    KEY_H to size.height.value,
                    KEY_X to absolute?.x?.value,
                    KEY_Y to absolute?.y?.value,
                    KEY_MAXIMIZED to (placement == WindowPlacement.Maximized),
                )
            }
    }

    if (mainVisible) {
        Window(
            onCloseRequest = {
                DesktopLog.i(TAG, "main window closed — hiding, process stays in the menu bar")
                ui.hideMainWindow()
            },
            state = mainState,
            title = "Personal Todo",
            icon = rememberVectorPainter(TrayIcons.Idle),
        ) {
            LaunchedEffect(frontNonce, routeNonce) {
                window.toFront()
                window.requestFocus()
            }
            // `App` takes the route only as an initial value, so re-key to deep-link an open window.
            key(routeNonce) {
                App(shell.graph, initialRoute = route)
            }
        }
    }

    // ---- daily "Plan your day" window ----------------------------------------------------------
    if (dailyPromptVisible) {
        val promptState = rememberWindowState(
            width = 760.dp,
            height = 640.dp,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = { shell.alerts.dismissDailyPrompt() },
            state = promptState,
            title = "Plan your day",
            icon = rememberVectorPainter(TrayIcons.Idle),
        ) {
            LaunchedEffect(Unit) {
                window.toFront()
                window.requestFocus()
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                App(shell.graph, initialRoute = "plan")
            }
        }
    }

    // ---- always-on-top due alert ---------------------------------------------------------------
    alert?.let { current ->
        DueAlertWindow(current, shell.clock) { action ->
            shell.sound.stop()
            when (action) {
                AlertAction.Done -> {
                    ui.dismissAlert(current.taskId)
                    scope.launch { runCatching { TodoApp.completeTask(current.taskId) } }
                }
                is AlertAction.Extend -> {
                    ui.dismissAlert(current.taskId)
                    scope.launch { runCatching { TodoApp.extendTask(current.taskId, action.minutes) } }
                }
                AlertAction.Dismiss -> {
                    ui.dismissAlert(current.taskId)
                    scope.launch { runCatching { TodoApp.dismissAlert(current.taskId) } }
                }
                AlertAction.Delete -> {
                    ui.dismissAlert(current.taskId)
                    scope.launch { runCatching { TodoApp.deleteTask(current.taskId) } }
                }
                AlertAction.Silence -> {
                    // Keep the task due; the shared engine will re-alert. Just stop the noise and
                    // close this window so the user can get to the UI.
                    ui.dismissAlert(current.taskId)
                }
            }
            DesktopLog.i(TAG, "alert action $action for ${current.taskId}")
        }
    }
}

/** "Next: Pick up kids in 12m · 2 due now · 4 left today" */
private fun stickyHeadline(summary: StickySummary?, nowMillis: Long): String {
    if (summary == null) return "Personal Todo"
    val parts = mutableListOf<String>()
    val title = summary.nextTaskTitle
    val due = summary.nextDueAtEpochMillis
    if (title != null && due != null) {
        parts += "Next: ${title.take(40)} ${Countdown.phrase(due, nowMillis)}"
    } else if (title != null) {
        parts += "Next: ${title.take(40)}"
    } else {
        parts += "Nothing scheduled"
    }
    if (summary.dueNowCount > 0) parts += "${summary.dueNowCount} due now"
    parts += "${summary.remainingTodayCount} left today"
    if (summary.completedTodayCount > 0) parts += "${summary.completedTodayCount} done"
    return parts.joinToString(" · ")
}

private const val KEY_W = "window.width"
private const val KEY_H = "window.height"
private const val KEY_X = "window.x"
private const val KEY_Y = "window.y"
private const val KEY_MAXIMIZED = "window.maximized"
