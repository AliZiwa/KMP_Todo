package dev.aliziwa.todo.android

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "app is always watching" surface: an ongoing notification with the next due task, a
 * countdown that ticks every minute, and Open / Add actions.
 *
 * Foreground service type:
 *  - API 34+ uses `specialUse`. `dataSync` is capped at 6 hours per day on 34+, which would kill
 *    a permanently-running reminder service. The required
 *    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest property explains the use to reviewers.
 *  - API 29..33 has no `specialUse`, so it starts as `dataSync`.
 *  - API 26..28 predates typed foreground services entirely (type 0).
 */
class StickyForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Channels.createAll(this)
        // Re-render on every push from the shared layer AND once a minute so the countdown moves.
        scope.launch {
            while (true) {
                runCatching {
                    Log.d(TAG, "minute tick")
                    pushNotification(AlertBus.sticky.value)
                }
                delay(nextMinuteBoundaryDelay())
            }
        }
        scope.launch {
            AlertBus.sticky.collect { summary -> runCatching { pushNotification(summary) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground(buildStickyNotification(this, AlertBus.sticky.value, System.currentTimeMillis()))
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(this, NotificationIds.STICKY, notification, foregroundServiceType())
            started = true
        } catch (t: Throwable) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when a background
            // start races the restriction window. The alarm path still works; we simply have no
            // ongoing notification until the next start attempt.
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun pushNotification(summary: StickySummary?) {
        val notification = buildStickyNotification(this, summary, System.currentTimeMillis())
        if (!started) {
            enterForeground(notification)
        } else {
            getSystemService(NotificationManager::class.java)
                ?.notify(NotificationIds.STICKY, notification)
        }
    }

    private fun foregroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** Wake up just after the next wall-clock minute so the countdown flips on time. */
    private fun nextMinuteBoundaryDelay(): Long {
        val now = System.currentTimeMillis()
        return 60_000L - (now % 60_000L) + 250L
    }

    companion object {
        private const val TAG = "TodoSticky"
        const val ACTION_STOP = "dev.aliziwa.todo.action.STOP_STICKY"

        /** Idempotent: starting an already-running foreground service just re-delivers the intent. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, StickyForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "could not start sticky service", t)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, StickyForegroundService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

/**
 * The ongoing notification. Kept as a free function so the service and any cold-start path build
 * exactly the same thing.
 */
internal fun buildStickyNotification(
    context: Context,
    summary: StickySummary?,
    nowEpochMillis: Long,
): Notification {
    val title: String
    val text: String
    if (summary == null) {
        title = "Personal Todo"
        text = "Watching for due tasks"
    } else {
        val next = summary.nextTaskTitle
        val nextAt = summary.nextDueAtEpochMillis
        title = when {
            summary.dueNowCount > 0 ->
                if (summary.dueNowCount == 1) "Due now: ${next ?: "1 task"}"
                else "${summary.dueNowCount} tasks due now"
            next != null && nextAt != null -> "Next: $next"
            next != null -> "Next: $next"
            else -> "Nothing scheduled"
        }
        val parts = buildList {
            if (nextAt != null && summary.dueNowCount == 0) add(formatCountdown(nextAt, nowEpochMillis))
            add("${summary.remainingTodayCount} left")
            if (summary.completedTodayCount > 0) add("${summary.completedTodayCount} done")
        }
        text = parts.joinToString(" · ")
    }

    return NotificationCompat.Builder(context, Channels.STICKY)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(MainActivity.pendingIntent(context, route = "today", requestCode = 10_001))
        .addAction(
            R.drawable.ic_notification,
            "Open",
            MainActivity.pendingIntent(context, route = "today", requestCode = 10_002),
        )
        .addAction(
            R.drawable.ic_notification,
            "Add",
            MainActivity.pendingIntent(context, route = "add", requestCode = 10_003),
        )
        .build()
}
