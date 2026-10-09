package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.TodoApp

/**
 * Thin adapter over the shared entry points the desktop shell drives.
 *
 * Originally this invoked `TodoApp.resyncAlarms()` reflectively because the shared agent had not
 * landed it yet; it now exists, so the call is direct and the adapter only adds logging plus a
 * guard so a shared-side failure can never take the resident process down.
 *
 * The shell consumes alert / sticky / daily-prompt state through the *push* side of the ports
 * (`AlertPresenter`) rather than `graph().dueEngine`'s StateFlows, so no extra coupling is needed.
 */
object SharedBridge {
    private const val TAG = "shared-bridge"

    /** Re-sync platform alarms with the DB (after wake, clock change, or launch-at-login start). */
    fun resyncAlarms() {
        runCatching { TodoApp.resyncAlarms() }
            .onFailure {
                DesktopLog.w(TAG, "TodoApp.resyncAlarms() failed; falling back to onAppForegrounded()", it)
                runCatching { TodoApp.onAppForegrounded() }
            }
    }
}
