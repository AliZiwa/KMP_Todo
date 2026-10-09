package dev.aliziwa.todo.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.aliziwa.todo.platform.AlertPresenter
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.StickySummary

/**
 * Android side of [AlertPresenter].
 *
 * Surfaces:
 *  - due alert  -> high-importance notification with a full-screen intent into [DueAlertActivity]
 *                  plus the looping doorbell. Full-screen intent is the only sanctioned way to
 *                  launch an Activity from the background on API 29+.
 *  - sticky     -> the ongoing notification owned by [StickyForegroundService].
 *  - daily plan -> a plain notification that deep-links to the `plan` route.
 *
 * Every method is idempotent: the shared layer may re-push the same alert on every re-evaluation.
 */
class AndroidAlertPresenter(
    private val context: Context,
    private val doorbell: DoorbellPlayer,
) : AlertPresenter {

    private val nm: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    override fun showDueAlert(alert: DueAlert) {
        Log.i(TAG, "showDueAlert(${alert.taskId}) '${alert.title}'")
        AlertBus.addAlert(alert)

        // Keep the process foregrounded so the doorbell and the alert survive.
        StickyForegroundService.ensureRunning(context)
        doorbell.playDoorbell(loop = true)

        val fullScreen = DueAlertActivity.pendingIntent(context, alert.taskId)
        val priorityLabel = when (alert.priority) {
            2 -> "High priority"
            1 -> "Medium priority"
            else -> "Low priority"
        }
        val extended = if (alert.extensionCount > 0) " · extended ${alert.extensionCount}x" else ""

        // When the looping MediaPlayer is up, post on the silent twin channel so the two do not
        // play the same 2s chime out of phase. If it failed to start, fall back to the channel
        // sound, which FLAG_INSISTENT then repeats for us.
        val ringing = doorbell.isPlaying
        val notification = NotificationCompat.Builder(
            context,
            if (ringing) Channels.ALERTS_SILENT else Channels.ALERTS,
        )
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(alert.title.ifBlank { "Task due" })
            .setContentText("Due now · $priorityLabel$extended")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.dueAtEpochMillis)
            .setUsesChronometer(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(DueAlertActivity.pendingIntent(context, alert.taskId))
            .addAction(action(R.drawable.ic_notification, "Done", TaskActionReceiver.ACTION_DONE, alert.taskId))
            .addAction(action(R.drawable.ic_notification, "+15m", TaskActionReceiver.ACTION_EXTEND, alert.taskId, 15))
            .addAction(action(R.drawable.ic_notification, "Dismiss", TaskActionReceiver.ACTION_DISMISS, alert.taskId))
            .addAction(action(R.drawable.ic_notification, "Delete", TaskActionReceiver.ACTION_DELETE, alert.taskId))
            .build()
        // FLAG_INSISTENT makes the framework repeat the channel sound until the notification is
        // cancelled - only useful on the fallback path, where the channel owns the doorbell.
        if (!ringing) notification.flags = notification.flags or Notification.FLAG_INSISTENT

        runCatching { nm?.notify(NotificationIds.alert(alert.taskId), notification) }
            .onFailure { Log.e(TAG, "failed to post alert notification", it) }
    }

    override fun dismissDueAlert(taskId: String) {
        Log.i(TAG, "dismissDueAlert($taskId)")
        AlertBus.removeAlert(taskId)
        runCatching { nm?.cancel(NotificationIds.alert(taskId)) }
        // Only silence once nothing is still ringing (two tasks due in the same minute).
        if (AlertBus.alerts.value.isEmpty()) doorbell.stop()
    }

    override fun updateSticky(summary: StickySummary) {
        AlertBus.setSticky(summary)
        StickyForegroundService.ensureRunning(context)
    }

    override fun showDailyPrompt() {
        Log.i(TAG, "showDailyPrompt()")
        AlertBus.setDailyPromptVisible(true)
        val notification = NotificationCompat.Builder(context, Channels.DAILY_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Plan your day")
            .setContentText("Tap to set up today's tasks.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(MainActivity.pendingIntent(context, route = "plan", requestCode = 20_001))
            .build()
        runCatching { nm?.notify(NotificationIds.DAILY_PROMPT, notification) }
            .onFailure { Log.e(TAG, "failed to post daily prompt", it) }
    }

    override fun dismissDailyPrompt() {
        AlertBus.setDailyPromptVisible(false)
        runCatching { nm?.cancel(NotificationIds.DAILY_PROMPT) }
    }

    private fun action(icon: Int, label: String, action: String, taskId: String, minutes: Int = 0) =
        NotificationCompat.Action.Builder(
            icon,
            label,
            TaskActionReceiver.pendingIntent(context, action, taskId, minutes),
        ).build()

    private companion object {
        const val TAG = "TodoAlerts"
    }
}
