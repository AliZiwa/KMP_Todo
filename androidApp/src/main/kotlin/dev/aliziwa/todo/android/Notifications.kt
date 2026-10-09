package dev.aliziwa.todo.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

/** Notification channel ids. Bump the `_vN` suffix to re-create a channel with new settings. */
object Channels {
    /** Ongoing "next task + countdown" summary. Low importance: no sound, no heads-up. */
    const val STICKY = "sticky_v1"

    /** Task became due. High importance + doorbell + full-screen intent. */
    const val ALERTS = "alerts_v1"

    /**
     * Same as [ALERTS] but with no sound, used when [DoorbellPlayer] owns the audio.
     *
     * A second channel rather than `NotificationCompat.setSilent(true)`: on API 26+ the sound
     * comes from the channel and cannot be muted per-notification, and `setSilent` works by
     * setting `GROUP_ALERT_SUMMARY`, which the framework classifies as a suppressive group alert
     * behaviour and uses to cancel the full-screen intent (verified on API 37 - the alert screen
     * never appeared). Importance stays HIGH so the full-screen intent is still honoured.
     */
    const val ALERTS_SILENT = "alerts_silent_v1"

    /** Morning "plan your day" nudge. Default importance. */
    const val DAILY_PROMPT = "daily_prompt_v1"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val sticky = NotificationChannel(
            STICKY,
            context.getString(R.string.channel_sticky_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_sticky_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alerts = NotificationChannel(
            ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            setSound(doorbellUri(context), alarmAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        val alertsSilent = NotificationChannel(
            ALERTS_SILENT,
            context.getString(R.string.channel_alerts_silent_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_silent_desc)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        val prompt = NotificationChannel(
            DAILY_PROMPT,
            context.getString(R.string.channel_prompt_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_prompt_desc)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        nm.createNotificationChannels(listOf(sticky, alerts, alertsSilent, prompt))
    }

    fun doorbellUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.doorbell}")
}

object NotificationIds {
    const val STICKY = 1
    const val DAILY_PROMPT = 2
    private const val ALERT_BASE = 10_000

    /** Stable, collision-free-enough id per task so repeated alerts replace instead of stacking. */
    fun alert(taskId: String): Int = ALERT_BASE + (abs(taskId.hashCode()) % 100_000)
}

/** Whether the user has granted POST_NOTIFICATIONS (always true below API 33). */
fun Context.notificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

/** Human countdown used by the sticky notification and the alert screen. */
fun formatCountdown(targetEpochMillis: Long, nowEpochMillis: Long): String {
    val deltaMs = targetEpochMillis - nowEpochMillis
    val overdue = deltaMs < 0
    var minutes = (abs(deltaMs) + 59_999) / 60_000  // round up so "in 0m" never shows
    if (overdue) minutes = abs(deltaMs) / 60_000
    val days = minutes / (60 * 24)
    val hours = (minutes % (60 * 24)) / 60
    val mins = minutes % 60
    val core = when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        minutes <= 0L -> "now"
        else -> "${mins}m"
    }
    return if (overdue) {
        if (core == "now") "due now" else "$core overdue"
    } else {
        if (core == "now") "due now" else "in $core"
    }
}
