package dev.aliziwa.todo

import androidx.room.RoomDatabase
import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.db.buildTodoDatabase
import dev.aliziwa.todo.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Object graph for the shared layer. The shared agent expands this (repositories, use cases,
 * schedulers, assistant). Platform code only talks to [TodoApp] and the ports.
 */
class AppGraph internal constructor(
    val platform: PlatformServices,
    val database: TodoDatabase,
) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
 */
object TodoApp {
    private var graph: AppGraph? = null

    fun start(platform: PlatformServices, databaseBuilder: RoomDatabase.Builder<TodoDatabase>): AppGraph {
        graph?.let { return it }
        val db = databaseBuilder.buildTodoDatabase()
        return AppGraph(platform, db).also { graph = it }
    }

    fun graph(): AppGraph = graph ?: error("TodoApp.start() must be called before use")

    fun onAlarmFired(taskId: String) { /* shared agent: evaluate task, trigger alerts */ }
    fun onDailyPromptFired() { /* shared agent: decide whether prompt is still needed today */ }
    fun onAppForegrounded() { /* shared agent: re-evaluate due state */ }

    /** Actions platform notification buttons can invoke without opening UI. */
    suspend fun completeTask(taskId: String) {}
    suspend fun extendTask(taskId: String, byMinutes: Int) {}
    suspend fun dismissAlert(taskId: String) {}
    suspend fun deleteTask(taskId: String) {}
}
