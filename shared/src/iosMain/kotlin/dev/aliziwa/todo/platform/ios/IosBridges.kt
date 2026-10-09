package dev.aliziwa.todo.platform.ios

import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Handle returned by the `observe*` helpers. Swift keeps it alive for as long as it wants
 * the callback and calls [cancel] on teardown (`Shared.Cancellable`).
 */
class Cancellable internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/**
 * StateFlow collection helpers. Kotlin Flows are awkward from Swift, so every stream the
 * iOS layer needs is exposed as "callback in, [Cancellable] out". Callbacks are always
 * delivered on the main dispatcher so Swift can touch UIKit/ActivityKit directly.
 */
object TodoObservers {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Fires immediately with the current value, then on every change. */
    fun observeStickySummary(block: (StickySummary) -> Unit): Cancellable =
        collect(TodoApp.graph().stickySummaryFlow(), block)

    /** `null` means "no alert showing". */
    fun observeCurrentAlert(block: (DueAlert?) -> Unit): Cancellable =
        collect(TodoApp.graph().currentAlertFlow(), block)

    /** Fires every time the shared layer asks for the daily "plan your day" sheet. */
    fun observeDailyPromptRequested(block: () -> Unit): Cancellable {
        val job = scope.launch {
            TodoApp.graph().dailyPromptRequestedFlow()
                .filter { it }
                .collect { block() }
        }
        return Cancellable(job)
    }

    private fun <T> collect(flow: StateFlow<T>, block: (T) -> Unit): Cancellable {
        val job = scope.launch {
            flow.collect { block(it) }
        }
        return Cancellable(job)
    }
}

/**
 * Non-suspending, Swift-callable facade over [TodoApp]. Notification actions and
 * `UNUserNotificationCenterDelegate` callbacks run on arbitrary threads and cannot await,
 * so everything here is fire-and-forget with an optional completion closure (call it from
 * the delegate's `completionHandler`).
 */
object TodoActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun onAlarmFired(taskId: String) = TodoApp.onAlarmFired(taskId)

    fun onDailyPromptFired() = TodoApp.onDailyPromptFired()

    fun onAppForegrounded() = TodoApp.onAppForegrounded()

    /** Re-arm every platform alarm (wake, timezone change, clock set backwards). */
    fun resyncAlarms() = TodoApp.resyncAlarms()

    fun completeTask(taskId: String, completion: (() -> Unit)?) = run(completion) {
        TodoApp.completeTask(taskId)
    }

    fun extendTask(taskId: String, byMinutes: Int, completion: (() -> Unit)?) = run(completion) {
        TodoApp.extendTask(taskId, byMinutes)
    }

    fun dismissAlert(taskId: String, completion: (() -> Unit)?) = run(completion) {
        TodoApp.dismissAlert(taskId)
    }

    fun deleteTask(taskId: String, completion: (() -> Unit)?) = run(completion) {
        TodoApp.deleteTask(taskId)
    }

    private fun run(completion: (() -> Unit)?, body: suspend () -> Unit) {
        scope.launch {
            try {
                body()
            } finally {
                completion?.invoke()
            }
        }
    }
}
