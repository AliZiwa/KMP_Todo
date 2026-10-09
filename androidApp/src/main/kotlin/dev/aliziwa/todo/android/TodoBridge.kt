package dev.aliziwa.todo.android

import android.util.Log
import dev.aliziwa.todo.TodoApp

/**
 * Thin, never-throwing wrapper around [TodoApp].
 *
 * Android calls into this from BroadcastReceivers and the foreground service, where an exception
 * escaping means a system-visible ANR/crash in a context the user cannot see. Every entry point
 * therefore logs and swallows. [TodoApp]'s own `withEngine` already tolerates being called before
 * `start()`, but a receiver can still arrive before the Application is fully constructed.
 */
object TodoBridge {
    private const val TAG = "TodoBridge"

    fun resyncAlarms() = guard("resyncAlarms") { TodoApp.resyncAlarms() }
    fun onAlarmFired(taskId: String) = guard("onAlarmFired($taskId)") { TodoApp.onAlarmFired(taskId) }
    fun onDailyPromptFired() = guard("onDailyPromptFired") { TodoApp.onDailyPromptFired() }
    fun onAppForegrounded() = guard("onAppForegrounded") { TodoApp.onAppForegrounded() }

    suspend fun completeTask(taskId: String) = TodoApp.completeTask(taskId)
    suspend fun extendTask(taskId: String, byMinutes: Int) = TodoApp.extendTask(taskId, byMinutes)
    suspend fun dismissAlert(taskId: String) = TodoApp.dismissAlert(taskId)
    suspend fun deleteTask(taskId: String) = TodoApp.deleteTask(taskId)

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }
}
