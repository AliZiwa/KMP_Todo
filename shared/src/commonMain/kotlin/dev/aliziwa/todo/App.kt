package dev.aliziwa.todo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.assistant.AssistantMode
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.DeletedTask
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Due
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.llm.ModelCatalog
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.ui.AppBottomBar
import dev.aliziwa.todo.ui.AppNavRail
import dev.aliziwa.todo.ui.BoardTab
import dev.aliziwa.todo.ui.ChatScreen
import dev.aliziwa.todo.ui.ContentColumn
import dev.aliziwa.todo.ui.DueAlertDialog
import dev.aliziwa.todo.ui.EmptyArt
import dev.aliziwa.todo.ui.EmptyState
import dev.aliziwa.todo.ui.GradientFab
import dev.aliziwa.todo.ui.GreetingHeader
import dev.aliziwa.todo.ui.LocalUse24HourClock
import dev.aliziwa.todo.ui.NavTarget
import dev.aliziwa.todo.ui.PlanDayScreen
import dev.aliziwa.todo.ui.Route
import dev.aliziwa.todo.ui.SettingsScreen
import dev.aliziwa.todo.ui.TaskActions
import dev.aliziwa.todo.ui.TaskEditorForm
import dev.aliziwa.todo.ui.TodayScreen
import dev.aliziwa.todo.ui.TodoTheme
import dev.aliziwa.todo.ui.parseRoute
import dev.aliziwa.todo.ui.rememberNavigator
import dev.aliziwa.todo.ui.rememberNowMillis
import kotlinx.coroutines.launch

/** Below this width the rail becomes a bottom bar, so phones keep working. */
private const val RAIL_BREAKPOINT_DP = 700

/** Single-user app; the greeting is allowed to be personal. */
private const val USER_NAME = "Ali"

/**
 * Root composable hosted by every platform. [initialRoute] lets platforms deep-link
 * (e.g. notification tap -> "task/<id>", menu bar "Add" -> "add", daily prompt -> "plan").
 *
 * Supported routes: `today`, `add`, `plan`, `task/<id>`, `alert/<id>`, `chat`, `settings`.
 * Which of the four boards the `today` route shows is shell state (the rail), not a route.
 *
 * [darkTheme] forces a scheme instead of following the system one; only screenshot tests pass it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(graph: AppGraph, initialRoute: String? = null, darkTheme: Boolean? = null) {
    TodoTheme(dark = darkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()) {
        val nav = rememberNavigator(parseRoute(initialRoute))
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val nowMillis by rememberNowMillis(graph.platform.clock)

        val snapshot by graph.taskRepository.snapshot.collectAsState()
        val settings by graph.settingsRepository.settings.collectAsState()
        val currentAlert by graph.dueEngine.currentAlert.collectAsState()
        val dailyPromptRequested by graph.dueEngine.dailyPromptRequested.collectAsState()
        val manualPrefill by graph.assistant.manualPrefill.collectAsState()

        val zone = remember(nowMillis / 60_000) { graph.zone() }
        val today = remember(nowMillis / 60_000) { DateMath.today(nowMillis, zone) }
        val nowMinutes = remember(nowMillis / 60_000, zone) { DateMath.minutesOfDay(nowMillis, zone) }

        var pendingUndo by remember { mutableStateOf<DeletedTask?>(null) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var board by remember { mutableStateOf(BoardTab.DAY) }

        LaunchedEffect(Unit) { TodoApp.onAppForegrounded() }

        // A deep link into "alert/<id>" should also mute the sound when acted on, so it shares the
        // same action handlers as the automatic overlay.
        val deepLinkAlert: DueAlert? = (nav.current as? Route.Alert)?.let { route ->
            Boards.resolve(snapshot, route.key, zone)?.let { item ->
                DueAlert(
                    taskId = item.key,
                    title = item.title,
                    dueAtEpochMillis = item.dueAt ?: nowMillis,
                    priority = item.priority.ordinal,
                    isRecurring = item.isRecurringInstance,
                    extensionCount = item.extensionCount,
                )
            }
        }

        fun undoable(deleted: DeletedTask?) {
            if (deleted == null) return
            pendingUndo = deleted
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "Deleted \"${deleted.task.title}\"",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && pendingUndo == deleted) {
                    graph.taskRepository.restore(deleted)
                }
                if (pendingUndo == deleted) pendingUndo = null
            }
        }

        val actions = remember(graph) {
            TaskActions(
                onToggleDone = { item ->
                    scope.launch {
                        if (item.status == TaskStatus.DONE) graph.taskRepository.reopen(item.key)
                        else graph.dueEngine.complete(item.key)
                    }
                },
                onOpen = { item -> nav.push(Route.Task(item.key)) },
                onExtend = { item, minutes -> scope.launch { graph.dueEngine.extend(item.key, minutes) } },
                onDismiss = { item -> scope.launch { graph.dueEngine.dismiss(item.key) } },
                onDelete = { item -> scope.launch { undoable(graph.dueEngine.delete(item.key)) } },
            )
        }

        // The assistant's manual fallback opens the prefilled task form.
        LaunchedEffect(manualPrefill) {
            if (manualPrefill != null && nav.current !is Route.Add) nav.push(Route.Add)
        }

        // Today's numbers drive the header ring, the rail badge and the greeting.
        val dayBoard = Boards.day(snapshot, today, zone)
        val todays = dayBoard.items + dayBoard.overdue
        val doneToday = todays.count { it.status == TaskStatus.DONE }
        val remainingToday = todays.count { it.status != TaskStatus.DONE }
        val dueNowCount = todays.count { Due.isRinging(it, nowMillis) }

        val route = nav.current
        val editorOpen = route is Route.Add || route is Route.Task
        val selectedNav: NavTarget? = when (route) {
            Route.Today -> NavTarget.Board(board)
            Route.Chat -> NavTarget.Chat
            Route.Settings -> NavTarget.Settings
            else -> null
        }
        val onSelectNav: (NavTarget) -> Unit = { target ->
            when (target) {
                is NavTarget.Board -> {
                    board = target.tab
                    nav.reset(Route.Today)
                }
                NavTarget.Chat -> nav.reset(Route.Chat)
                NavTarget.Settings -> nav.reset(Route.Settings)
            }
        }

        CompositionLocalProvider(LocalUse24HourClock provides settings.use24HourClock) {
            BoxWithConstraints {
                val wide = maxWidth >= RAIL_BREAKPOINT_DP.dp
                Scaffold(
                    // Keep the whole app out of the status bar / notch / home indicator.
                    // safeDrawing is zero on desktop, so only Android and iOS are affected.
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!wide) {
                            AppBottomBar(
                                selected = selectedNav,
                                onSelect = onSelectNav,
                                dueNowCount = dueNowCount,
                            )
                        }
                    },
                ) { padding ->
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        if (wide) {
                            AppNavRail(
                                selected = selectedNav,
                                onSelect = onSelectNav,
                                dueNowCount = dueNowCount,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            ContentColumn {
                                Column(Modifier.fillMaxSize()) {
                                    if (route is Route.Today || route is Route.Alert) {
                                        GreetingHeader(
                                            name = USER_NAME,
                                            today = today,
                                            hourOfDay = nowMinutes / 60,
                                            done = doneToday,
                                            remaining = remainingToday,
                                            dueNow = dueNowCount,
                                            modifier = Modifier.padding(
                                                start = 16.dp,
                                                end = 16.dp,
                                                top = 16.dp,
                                                bottom = 6.dp,
                                            ),
                                        )
                                    } else {
                                        PageHeader(
                                            title = titleFor(route),
                                            canGoBack = nav.canGoBack,
                                            onBack = { if (!nav.back()) nav.reset(Route.Today) },
                                        )
                                    }

                                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                                        AnimatedContent(
                                            targetState = if (route is Route.Today) {
                                                "today-${board.name}"
                                            } else {
                                                route.toString()
                                            },
                                            transitionSpec = {
                                                (fadeIn(tween(190)) + scaleIn(tween(220), initialScale = 0.97f))
                                                    .togetherWith(fadeOut(tween(110))) using
                                                    SizeTransform(clip = false)
                                            },
                                            label = "route",
                                        ) { _ ->
                                            RouteContent(
                                                graph = graph,
                                                nav = nav,
                                                route = route,
                                                board = board,
                                                snapshot = snapshot,
                                                settings = settings,
                                                today = today,
                                                zone = zone,
                                                nowMillis = nowMillis,
                                                nowMinutes = nowMinutes,
                                                actions = actions,
                                                manualPrefill = manualPrefill,
                                                testResult = testResult,
                                                onTestResult = { testResult = it },
                                                onUndoable = { undoable(it) },
                                            )
                                        }
                                    }
                                }
                            }

                            if (route is Route.Today || editorOpen) {
                                GradientFab(
                                    editorOpen = editorOpen,
                                    onClick = {
                                        if (editorOpen) {
                                            graph.assistant.clearManualPrefill()
                                            if (!nav.back()) nav.reset(Route.Today)
                                        } else {
                                            nav.push(Route.Add)
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 26.dp, bottom = 26.dp),
                                )
                            }
                        }
                    }
                }

                // The due alert always appears on top, both automatically (engine) and via deep link.
                val alert = currentAlert ?: deepLinkAlert
                if (alert != null) {
                    DueAlertDialog(
                        alert = alert,
                        nowMillis = nowMillis,
                        onDone = {
                            scope.launch {
                                graph.dueEngine.complete(alert.taskId)
                                if (nav.current is Route.Alert) nav.reset(Route.Today)
                            }
                        },
                        onExtend = { minutes ->
                            scope.launch {
                                graph.dueEngine.extend(alert.taskId, minutes)
                                if (nav.current is Route.Alert) nav.reset(Route.Today)
                            }
                        },
                        onDismissTask = {
                            scope.launch {
                                graph.dueEngine.dismiss(alert.taskId)
                                if (nav.current is Route.Alert) nav.reset(Route.Today)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                undoable(graph.dueEngine.delete(alert.taskId))
                                if (nav.current is Route.Alert) nav.reset(Route.Today)
                            }
                        },
                        onClose = {
                            scope.launch {
                                graph.dueEngine.silenceCurrentAlert()
                                if (nav.current is Route.Alert) nav.reset(Route.Today)
                            }
                        },
                    )
                }
            }
        }

        // Morning prompt: route there once per day when the engine asks for it.
        LaunchedEffect(dailyPromptRequested) {
            if (dailyPromptRequested && nav.current !is Route.Plan) nav.push(Route.Plan)
        }
    }
}

/** Compact header for everything that is not a board: back arrow + title. */
@Composable
private fun PageHeader(title: String, canGoBack: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RouteContent(
    graph: AppGraph,
    nav: dev.aliziwa.todo.ui.Navigator,
    route: Route,
    board: BoardTab,
    snapshot: dev.aliziwa.todo.domain.TaskSnapshot,
    settings: AppSettings,
    today: kotlinx.datetime.LocalDate,
    zone: kotlinx.datetime.TimeZone,
    nowMillis: Long,
    nowMinutes: Int,
    actions: TaskActions,
    manualPrefill: TaskDraft?,
    testResult: String?,
    onTestResult: (String?) -> Unit,
    onUndoable: (DeletedTask?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    when (route) {
        Route.Today, is Route.Alert -> TodayScreen(
            snapshot = snapshot,
            today = today,
            zone = zone,
            nowMillis = nowMillis,
            actions = actions,
            tab = board,
        )

        Route.Add -> TaskEditorForm(
            initial = manualPrefill ?: TaskDraft(scope = TaskScope.DAY, date = today),
            today = today,
            confirmLabel = "Add task",
            nowMinutes = nowMinutes,
            onSubmit = { draft ->
                scope.launch {
                    graph.taskRepository.create(draft)
                    graph.assistant.clearManualPrefill()
                    nav.back()
                }
            },
            onCancel = {
                graph.assistant.clearManualPrefill()
                nav.back()
            },
        )

        is Route.Task -> {
            val item = Boards.resolve(snapshot, route.key, zone)
            if (item == null) {
                EmptyState("Task not found", "It may have been deleted.", art = EmptyArt.CALENDAR)
            } else {
                TaskEditorForm(
                    initial = draftOf(item, zone),
                    today = today,
                    confirmLabel = "Save",
                    nowMinutes = nowMinutes,
                    onSubmit = { draft ->
                        scope.launch {
                            graph.taskRepository.update(item.taskId, draft)
                            graph.dueEngine.evaluateAll()
                            nav.back()
                        }
                    },
                    onCancel = { nav.back() },
                    onDelete = {
                        scope.launch {
                            onUndoable(graph.dueEngine.delete(item.key))
                            nav.back()
                        }
                    },
                )
            }
        }

        Route.Plan -> {
            // Looking at the sheet counts as seen: leaving the route for any reason (its own
            // buttons, back, the Today action, window closing) consumes the prompt, so the user is
            // not asked again today. A prompt they never open is never consumed and so comes back
            // on the next launch/wake.
            DisposableEffect(Unit) {
                onDispose { graph.dueEngine.consumeDailyPrompt() }
            }
            PlanDayScreen(
                today = today,
                existing = Boards.day(snapshot, today, zone).let { it.overdue + it.items },
                nowMillis = nowMillis,
                nowMinutes = nowMinutes,
                onAddAll = { drafts ->
                    scope.launch {
                        drafts.forEach { graph.taskRepository.create(it) }
                        graph.dueEngine.consumeDailyPrompt()
                        nav.reset(Route.Today)
                    }
                },
                onClose = {
                    graph.dueEngine.consumeDailyPrompt()
                    if (!nav.back()) nav.reset(Route.Today)
                },
            )
        }

        Route.Chat -> {
            val messages by graph.assistant.messages.collectAsState()
            val mode by graph.assistant.mode.collectAsState()
            val busy by graph.assistant.busy.collectAsState()
            val status by graph.assistant.statusLine.collectAsState()
            ChatScreen(
                messages = messages,
                mode = mode,
                busy = busy,
                statusLine = status,
                onSend = { graph.assistant.send(it) },
                onCancel = { graph.assistant.cancel() },
                onConfirm = { graph.assistant.confirm(it) },
                onReject = { graph.assistant.reject(it) },
                onOpenSettings = { nav.push(Route.Settings) },
                onReenable = { scope.launch { graph.assistant.reenable() } },
                suggestedModelName = ModelCatalog
                    .defaultFor(graph.platform.localLlmEngine?.supportedExtensions ?: emptyList())
                    ?.displayName,
            )
        }

        Route.Settings -> {
            val downloadState by graph.modelDownloader.state.collectAsState()
            val engine = graph.platform.localLlmEngine
            val engineState = engine?.state?.collectAsState()?.value
            val mode by graph.assistant.mode.collectAsState()
            SettingsScreen(
                settings = settings,
                engineState = engineState,
                hasLocalEngine = engine != null,
                supportedExtensions = engine?.supportedExtensions ?: emptyList(),
                suggestions = ModelCatalog.suggestionsFor(engine?.supportedExtensions ?: emptyList()),
                downloadState = downloadState,
                testResult = testResult,
                assistantInManualFallback = mode == AssistantMode.MANUAL_FALLBACK,
                onUpdate = { transform -> scope.launch { applySettings(graph, transform) } },
                onDownload = { url, fileName ->
                    graph.modelDownloader.start(
                        url = url,
                        allowedExtensions = engine?.supportedExtensions ?: emptyList(),
                        fileNameOverride = fileName,
                    )
                },
                onCancelDownload = { graph.modelDownloader.cancel() },
                onUseModel = { path ->
                    scope.launch {
                        graph.llmRuntimeFactory.clearLoadFailure()
                        graph.settingsRepository.update { it.copy(localModelPath = path) }
                        // The settings change makes LlmRuntimeFactory load it; this is just to
                        // surface progress immediately.
                        engine?.load(path)
                    }
                },
                onTestBackend = {
                    scope.launch {
                        onTestResult("Testing…")
                        onTestResult(
                            graph.assistant.testBackend().fold(
                                onSuccess = { "OK — $it" },
                                onFailure = { "Failed — ${it.message}" },
                            ),
                        )
                    }
                },
                onOpenUrl = { graph.platform.capabilities.openUrl(it) },
                onReenableAssistant = { scope.launch { graph.assistant.reenable() } },
            )
        }
    }
}

private suspend fun applySettings(graph: AppGraph, transform: (AppSettings) -> AppSettings) {
    val before = graph.settingsRepository.current()
    val after = graph.settingsRepository.update(transform)
    if (after.launchAtLogin != before.launchAtLogin) {
        val ok = runCatching { graph.platform.capabilities.setLaunchAtLogin(after.launchAtLogin) }
            .getOrDefault(false)
        if (!ok && after.launchAtLogin) {
            graph.settingsRepository.update { it.copy(launchAtLogin = false) }
        }
    }
    if (!after.soundEnabled) runCatching { graph.platform.sound.stop() }
    if (after.localModelPath != before.localModelPath || after.llmBackend != before.llmBackend) {
        graph.llmRuntimeFactory.clearLoadFailure()
        graph.llmRuntimeFactory.close()
    }
}

private fun draftOf(item: TaskItem, zone: kotlinx.datetime.TimeZone): TaskDraft = TaskDraft(
    id = item.taskId,
    title = item.title,
    notes = item.notes,
    priority = item.priority,
    scope = item.scope,
    date = item.scheduledDate,
    timeMinutes = item.dueAt?.let { DateMath.minutesOfDay(it, zone) },
)

private fun titleFor(route: Route): String = when (route) {
    Route.Today -> "Personal Todo"
    Route.Add -> "New task"
    // The plan sheet carries its own big title card, so the page header is just the back arrow.
    Route.Plan -> ""
    Route.Chat -> "Assistant"
    Route.Settings -> "Settings"
    is Route.Task -> "Edit task"
    is Route.Alert -> "Due now"
}
