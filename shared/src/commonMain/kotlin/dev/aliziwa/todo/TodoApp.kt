package dev.aliziwa.todo

import androidx.room.RoomDatabase
import dev.aliziwa.todo.assistant.AssistantService
import dev.aliziwa.todo.assistant.PlanExecutor
import dev.aliziwa.todo.assistant.PlanResolver
import dev.aliziwa.todo.assistant.RuleBasedParser
import dev.aliziwa.todo.data.SettingsRepository
import dev.aliziwa.todo.data.TaskRepository
import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.db.buildTodoDatabase
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.llm.LlmRuntimeFactory
import dev.aliziwa.todo.llm.ModelDownloader
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.scheduling.DueEngine
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * Object graph for the shared layer. Manual wiring, no DI framework.
 * Platform code only talks to [TodoApp] and the ports.
 */
class AppGraph internal constructor(
    val platform: PlatformServices,
    val database: TodoDatabase,
) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val zoneProvider: () -> TimeZone = { DateMath.zone(platform.clock.timeZoneId()) }

    val settingsRepository: SettingsRepository = SettingsRepository(database.settingsDao(), appScope)

    val taskRepository: TaskRepository = TaskRepository(
        taskDao = database.taskDao(),
        recurringDao = database.recurringCompletionDao(),
        alertDao = database.alertStateDao(),
        clock = platform.clock,
        scope = appScope,
    )

    val dueEngine: DueEngine = DueEngine(
        repo = taskRepository,
        settings = settingsRepository,
        platform = platform,
        scope = appScope,
    )

    val llmRuntimeFactory: LlmRuntimeFactory = LlmRuntimeFactory(platform)

    val modelDownloader: ModelDownloader = ModelDownloader(
        http = platform.http,
        paths = platform.paths,
        capabilities = platform.capabilities,
        scope = appScope,
    )

    val ruleBasedParser: RuleBasedParser = RuleBasedParser(zoneProvider)

    val assistant: AssistantService = AssistantService(
        repo = taskRepository,
        settings = settingsRepository,
        runtimeFactory = llmRuntimeFactory,
        resolver = PlanResolver(zoneProvider),
        executor = PlanExecutor(taskRepository, dueEngine),
        ruleParser = ruleBasedParser,
        platform = platform,
        scope = appScope,
    )

    fun zone(): TimeZone = zoneProvider()
}

/**
 * Single entry point for every platform.
 *
 * Lifecycle expectations:
 *  - [start] is called once per process, as early as possible (Application.onCreate / main() / AppDelegate),
 *    BEFORE any UI. It is cheap and never blocks. It is safe to call again (returns same graph).
 *  - [onAlarmFired] is called by platform alarm receivers (may happen with no UI).
 *  - [onDailyPromptFired] is called by platform when the morning prompt alarm fires or on first unlock/wake.
 *  - [onAppForegrounded] is called whenever the UI becomes visible (re-evaluates due tasks, daily prompt).
 *  - [resyncAlarms] is called after boot / wake / timezone change.
 *
 * Every entry point below works from [start] alone — no UI needs to exist.
 */
object TodoApp {
    private const val TAG = "TodoApp"
    private var graph: AppGraph? = null

    fun start(platform: PlatformServices, databaseBuilder: RoomDatabase.Builder<TodoDatabase>): AppGraph {
        graph?.let { return it }
        val db = databaseBuilder.buildTodoDatabase()
        val created = AppGraph(platform, db)
        graph = created
        created.dueEngine.start()
        Log.i(TAG, "started on ${platform.capabilities.platformName}")
        return created
    }

    fun graph(): AppGraph = graph ?: error("TodoApp.start() must be called before use")

    fun isStarted(): Boolean = graph != null

    private fun withEngine(name: String, block: suspend (AppGraph) -> Unit) {
        val g = graph
        if (g == null) {
            Log.w(TAG, "$name before start(); ignored")
            return
        }
        g.appScope.launch {
            runCatching { block(g) }.onFailure { Log.e(TAG, "$name failed", it) }
        }
    }

    /** Platform alarm fired for [taskId] (a task id, or "templateId@yyyy-MM-dd" for a recurring day). */
    fun onAlarmFired(taskId: String) = withEngine("onAlarmFired") { it.dueEngine.evaluate(taskId) }

    /** Morning "plan your day" alarm, or first unlock/wake of the day. */
    fun onDailyPromptFired() = withEngine("onDailyPromptFired") { it.dueEngine.onDailyPromptFired() }

    /** UI became visible: catch up on alarms that fired while we were dead and the daily prompt. */
    fun onAppForegrounded() = withEngine("onAppForegrounded") {
        it.dueEngine.evaluateAll()
        it.dueEngine.maybeRequestDailyPrompt()
    }

    /** Re-arm every pending future alarm (boot receiver, wake from sleep, clock/timezone change). */
    fun resyncAlarms() = withEngine("resyncAlarms") { it.dueEngine.resyncAlarms() }

    // ----------------- Actions platform notification buttons can invoke without opening UI -----------------

    suspend fun completeTask(taskId: String) {
        graph().dueEngine.complete(taskId)
    }

    suspend fun extendTask(taskId: String, byMinutes: Int) {
        graph().dueEngine.extend(taskId, byMinutes)
    }

    suspend fun dismissAlert(taskId: String) {
        graph().dueEngine.dismiss(taskId)
    }

    suspend fun deleteTask(taskId: String) {
        graph().dueEngine.delete(taskId)
    }

    // Fire-and-forget variants for callers with no coroutine scope (BroadcastReceiver, AppDelegate,
    // tray menu item). They run on the shared app scope and log failures.
    fun completeTaskAsync(taskId: String) = withEngine("completeTask") { it.dueEngine.complete(taskId) }
    fun extendTaskAsync(taskId: String, byMinutes: Int) =
        withEngine("extendTask") { it.dueEngine.extend(taskId, byMinutes) }

    fun dismissAlertAsync(taskId: String) = withEngine("dismissAlert") { it.dueEngine.dismiss(taskId) }
    fun deleteTaskAsync(taskId: String) = withEngine("deleteTask") { it.dueEngine.delete(taskId) }
}
