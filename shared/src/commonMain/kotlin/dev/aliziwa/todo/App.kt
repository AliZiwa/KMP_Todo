package dev.aliziwa.todo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.aliziwa.todo.assistant.AssistantMode
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.DeletedTask
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.llm.ModelCatalog
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.ui.ChatScreen
import dev.aliziwa.todo.ui.DueAlertDialog
import dev.aliziwa.todo.ui.EmptyState
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

/**
 * Root composable hosted by every platform. [initialRoute] lets platforms deep-link
 * (e.g. notification tap -> "task/<id>", menu bar "Add" -> "add", daily prompt -> "plan").
 *
 * Supported routes: `today`, `add`, `plan`, `task/<id>`, `alert/<id>`, `chat`, `settings`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(graph: AppGraph, initialRoute: String? = null) {
    TodoTheme {
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

        var pendingUndo by remember { mutableStateOf<DeletedTask?>(null) }
        var testResult by remember { mutableStateOf<String?>(null) }

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

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(titleFor(nav.current)) },
                    navigationIcon = {
                        if (nav.canGoBack) {
                            IconButton(onClick = { nav.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (nav.current !is Route.Today) {
                            IconButton(onClick = { nav.reset(Route.Today) }) {
                                Icon(Icons.Default.Today, contentDescription = "Today")
                            }
                        }
                        IconButton(onClick = { nav.push(Route.Chat) }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Assistant")
                        }
                        IconButton(onClick = { nav.push(Route.Settings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            floatingActionButton = {
                if (nav.current is Route.Today) {
                    FloatingActionButton(onClick = { nav.push(Route.Add) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add task")
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val route = nav.current) {
                    Route.Today -> TodayScreen(
                        snapshot = snapshot,
                        today = today,
                        zone = zone,
                        nowMillis = nowMillis,
                        actions = actions,
                    )

                    Route.Add -> {
                        val prefill = manualPrefill
                        TaskEditorForm(
                            initial = prefill ?: TaskDraft(scope = TaskScope.DAY, date = today),
                            today = today,
                            confirmLabel = "Add task",
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
                    }

                    is Route.Task -> {
                        val item = Boards.resolve(snapshot, route.key, zone)
                        if (item == null) {
                            EmptyState("Task not found", "It may have been deleted.")
                        } else {
                            TaskEditorForm(
                                initial = draftOf(item, zone),
                                today = today,
                                confirmLabel = "Save",
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
                                        undoable(graph.dueEngine.delete(item.key))
                                        nav.back()
                                    }
                                },
                            )
                        }
                    }

                    Route.Plan -> {
                        // Looking at the sheet counts as seen: leaving the route for any reason
                        // (its own buttons, back, the Today action, window closing) consumes the
                        // prompt, so the user is not asked again today. A prompt they never open
                        // is never consumed and so comes back on the next launch/wake.
                        DisposableEffect(Unit) {
                            onDispose { graph.dueEngine.consumeDailyPrompt() }
                        }
                        PlanDayScreen(
                            today = today,
                            existing = Boards.day(snapshot, today, zone).let { it.overdue + it.items },
                            nowMillis = nowMillis,
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
                                    // The settings change makes LlmRuntimeFactory load it; this is
                                    // just to surface progress immediately.
                                    engine?.load(path)
                                }
                            },
                            onTestBackend = {
                                scope.launch {
                                    testResult = "Testing…"
                                    testResult = graph.assistant.testBackend().fold(
                                        onSuccess = { "OK — $it" },
                                        onFailure = { "Failed — ${it.message}" },
                                    )
                                }
                            },
                            onOpenUrl = { graph.platform.capabilities.openUrl(it) },
                            onReenableAssistant = { scope.launch { graph.assistant.reenable() } },
                        )
                    }

                    is Route.Alert -> {
                        // Rendered as the overlay below; show today behind it.
                        TodayScreen(
                            snapshot = snapshot,
                            today = today,
                            zone = zone,
                            nowMillis = nowMillis,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // The due alert always appears on top, both automatically (engine) and via the deep link.
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

        // Morning prompt: route there once per day when the engine asks for it.
        LaunchedEffect(dailyPromptRequested) {
            if (dailyPromptRequested && nav.current !is Route.Plan) nav.push(Route.Plan)
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
    Route.Plan -> "Plan your day"
    Route.Chat -> "Assistant"
    Route.Settings -> "Settings"
    is Route.Task -> "Edit task"
    is Route.Alert -> "Due now"
}
