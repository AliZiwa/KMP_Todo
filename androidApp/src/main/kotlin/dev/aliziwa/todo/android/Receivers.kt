package dev.aliziwa.todo.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/** Scope for `goAsync()` work. Survives the receiver instance; dies with the process. */
internal val ReceiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private const val TAG = "TodoReceiver"

/**
 * Fired by [AndroidAlarmScheduler] when a task becomes due. Runs with no UI and possibly a
 * cold-started process (Application.onCreate -> TodoApp.start has already run by the time we
 * get here, which is why the shared layer is safe to touch).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.w(TAG, "AlarmReceiver without $EXTRA_TASK_ID: ${intent.action}")
            return
        }
        Log.i(TAG, "alarm fired for $taskId")
        val pending = goAsync()
        val app = context.applicationContext
        ReceiverScope.launch {
            try {
                // Promote the process out of "cached" so the alert/doorbell survive this receiver.
                StickyForegroundService.ensureRunning(app)
                TodoBridge.onAlarmFired(taskId)
                // The shared DueEngine works asynchronously; hold the broadcast open until it has
                // actually raised the alert (bounded, well under the ~10s receiver budget).
                withTimeoutOrNull(8_000) {
                    AlertBus.alerts.first { list -> list.any { it.taskId == taskId } }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "alarm handling failed for $taskId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TASK_DUE = "dev.aliziwa.todo.action.TASK_DUE"
        const val EXTRA_TASK_ID = "taskId"
    }
}

/** Daily "plan your day" alarm. Re-arms itself for the next day after firing. */
class DailyPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "daily prompt alarm fired")
        val pending = goAsync()
        val app = context.applicationContext
        ReceiverScope.launch {
            try {
                StickyForegroundService.ensureRunning(app)
                TodoBridge.onDailyPromptFired()
                // Repeat tomorrow. setAlarmClock is one-shot by design, so re-arm here rather
                // than using setRepeating (which is inexact).
                AndroidServices.alarms?.rearmDailyPromptIfConfigured()
                // If the shared layer has not taken over the prompt yet, still show it so the
                // user is nudged; showDailyPrompt is idempotent.
                withTimeoutOrNull(3_000) { AlertBus.dailyPromptVisible.first { it } }
                    ?: AndroidServices.alerts?.showDailyPrompt()
            } catch (t: Throwable) {
                Log.e(TAG, "daily prompt handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY_PROMPT = "dev.aliziwa.todo.action.DAILY_PROMPT"
    }
}

/**
 * Re-arms everything after the device or the app restarted, or after the wall clock moved.
 * `setAlarmClock` alarms do NOT survive a reboot, a package replace, or a backwards clock change.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver: ${intent.action}")
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            // Drop the cached JVM default so AndroidClock.timeZoneId() reports the new zone.
            runCatching { TimeZone.setDefault(null) }
        }
        val pending = goAsync()
        val app = context.applicationContext
        ReceiverScope.launch {
            try {
                val known = AndroidServices.alarms?.knownTaskIds()?.size ?: 0
                Log.i(TAG, "resyncing alarms (had $known persisted)")
                AndroidServices.alarms?.rearmDailyPromptIfConfigured()
                TodoBridge.resyncAlarms()
                StickyForegroundService.ensureRunning(app)
            } catch (t: Throwable) {
                Log.e(TAG, "resync failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Notification / full-screen-alert action buttons. These must work with the UI dead, so the work
 * happens here (in `goAsync`) rather than in an Activity.
 *
 * The local alert state is cleared immediately — before awaiting the shared layer — so the
 * doorbell stops the instant the user taps, even if the repository write is slow.
 */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.w(TAG, "action $action without $EXTRA_TASK_ID")
            return
        }
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 15)
        Log.i(TAG, "action $action for $taskId (minutes=$minutes)")

        val pending = goAsync()
        ReceiverScope.launch {
            try {
                if (action != ACTION_OPEN) AndroidServices.alerts?.dismissDueAlert(taskId)
                when (action) {
                    ACTION_DONE -> TodoBridge.completeTask(taskId)
                    ACTION_EXTEND -> TodoBridge.extendTask(taskId, minutes)
                    ACTION_DISMISS -> TodoBridge.dismissAlert(taskId)
                    ACTION_DELETE -> TodoBridge.deleteTask(taskId)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "action $action failed for $taskId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "dev.aliziwa.todo.action.DONE"
        const val ACTION_EXTEND = "dev.aliziwa.todo.action.EXTEND"
        const val ACTION_DISMISS = "dev.aliziwa.todo.action.DISMISS"
        const val ACTION_DELETE = "dev.aliziwa.todo.action.DELETE"
        private const val ACTION_OPEN = "dev.aliziwa.todo.action.OPEN"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_MINUTES = "minutes"

        fun intent(context: Context, action: String, taskId: String, minutes: Int = 0): Intent =
            Intent(context, TaskActionReceiver::class.java)
                .setAction(action)
                .setData(android.net.Uri.parse("todo://action/$taskId/$action/$minutes"))
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_MINUTES, minutes)

        /**
         * One PendingIntent per (task, action, minutes). The data URI above makes them distinct
         * even though PendingIntent identity ignores extras.
         */
        fun pendingIntent(
            context: Context,
            action: String,
            taskId: String,
            minutes: Int = 0,
        ): android.app.PendingIntent {
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            val code = 30_000 + (kotlin.math.abs((taskId + action + minutes).hashCode()) % 100_000)
            return android.app.PendingIntent.getBroadcast(
                context,
                code,
                intent(context, action, taskId, minutes),
                flags,
            )
        }
    }
}
