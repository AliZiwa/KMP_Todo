package dev.aliziwa.todo.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import dev.aliziwa.todo.platform.AlarmScheduler
import java.util.Calendar

/**
 * Exact wakeups via [AlarmManager.setAlarmClock].
 *
 * Why `setAlarmClock` and not `setExactAndAllowWhileIdle`:
 *  - exact to the minute even in Doze, and
 *  - needs NO `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permission on any API level, and
 *  - surfaces the system alarm icon, which is honest about what the app is doing.
 * The cost is that the next alarm is user-visible in the status bar, which is fine for a to-do app.
 *
 * Request codes: [PendingIntent] identity ignores `extras`, so each task needs its own request
 * code or cancelling one alarm would cancel all of them. Codes are handed out from a monotonic
 * counter and persisted in SharedPreferences, so a task keeps its code across process death and
 * reboots (and therefore `cancel()` after a restart still targets the right alarm).
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val am: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ----------------------------------------------------------------- tasks

    override fun schedule(taskId: String, atEpochMillis: Long) {
        val alarmManager = am ?: return
        val fire = firePendingIntent(taskId)
        val show = MainActivity.pendingIntent(context, route = "alert/$taskId", requestCode = requestCode(taskId) + SHOW_OFFSET)
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atEpochMillis, show), fire)
            prefs.edit().putLong(keyWhen(taskId), atEpochMillis).apply()
            Log.i(TAG, "scheduled $taskId at $atEpochMillis (code ${requestCode(taskId)})")
        } catch (t: Throwable) {
            // SecurityException should be impossible with setAlarmClock, but never crash a
            // scheduling pass: fall back to the inexact-but-permissionless path.
            Log.w(TAG, "setAlarmClock failed for $taskId, falling back", t)
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, fire)
            }.onFailure {
                runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, atEpochMillis, fire) }
            }
        }
    }

    override fun cancel(taskId: String) {
        am?.cancel(firePendingIntent(taskId))
        prefs.edit().remove(keyWhen(taskId)).apply()
        Log.i(TAG, "cancelled $taskId")
    }

    override fun cancelAll() {
        val ids = prefs.all.keys.filter { it.startsWith(PREFIX_WHEN) }.map { it.removePrefix(PREFIX_WHEN) }
        ids.forEach { am?.cancel(firePendingIntent(it)) }
        prefs.edit().apply { ids.forEach { remove(keyWhen(it)) } }.apply()
        Log.i(TAG, "cancelAll (${ids.size} alarms)")
    }

    /** Task ids that currently believe they have an alarm armed (used by [BootReceiver] logging). */
    fun knownTaskIds(): List<String> =
        prefs.all.keys.filter { it.startsWith(PREFIX_WHEN) }.map { it.removePrefix(PREFIX_WHEN) }

    // ---------------------------------------------------------- daily prompt

    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) {
        val alarmManager = am ?: return
        prefs.edit()
            .putInt(KEY_PROMPT_HOUR, hourOfDay)
            .putInt(KEY_PROMPT_MINUTE, minute)
            .apply()
        val at = nextOccurrence(hourOfDay, minute)
        val fire = dailyPromptPendingIntent()
        val show = MainActivity.pendingIntent(context, route = "plan", requestCode = DAILY_PROMPT_CODE + SHOW_OFFSET)
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
            Log.i(TAG, "daily prompt scheduled for $at ($hourOfDay:$minute)")
        } catch (t: Throwable) {
            Log.w(TAG, "daily prompt setAlarmClock failed", t)
            runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire) }
        }
    }

    override fun cancelDailyPrompt() {
        am?.cancel(dailyPromptPendingIntent())
        prefs.edit().remove(KEY_PROMPT_HOUR).remove(KEY_PROMPT_MINUTE).apply()
        Log.i(TAG, "daily prompt cancelled")
    }

    /** Called by [DailyPromptReceiver] after firing, and by [BootReceiver] after a restart. */
    fun rearmDailyPromptIfConfigured() {
        val hour = prefs.getInt(KEY_PROMPT_HOUR, -1)
        val minute = prefs.getInt(KEY_PROMPT_MINUTE, -1)
        if (hour in 0..23 && minute in 0..59) scheduleDailyPrompt(hour, minute)
    }

    private fun nextOccurrence(hourOfDay: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Strictly in the future, and leave a 5s cushion so "scheduled at exactly now" still fires.
        if (cal.timeInMillis <= System.currentTimeMillis() + 5_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ------------------------------------------------------------- internals

    private fun firePendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TASK_DUE)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            // Make the Intent's "identity" unique too, belt-and-braces with the request code.
            .setData(android.net.Uri.parse("todo://alarm/$taskId"))
        return PendingIntent.getBroadcast(context, requestCode(taskId), intent, FLAGS)
    }

    private fun dailyPromptPendingIntent(): PendingIntent {
        val intent = Intent(context, DailyPromptReceiver::class.java)
            .setAction(DailyPromptReceiver.ACTION_DAILY_PROMPT)
        return PendingIntent.getBroadcast(context, DAILY_PROMPT_CODE, intent, FLAGS)
    }

    /** Stable per-task request code, persisted so it survives process death. */
    private fun requestCode(taskId: String): Int {
        val key = PREFIX_CODE + taskId
        prefs.getInt(key, -1).let { if (it != -1) return it }
        val next = prefs.getInt(KEY_NEXT_CODE, CODE_BASE)
        prefs.edit().putInt(key, next).putInt(KEY_NEXT_CODE, next + 1).apply()
        return next
    }

    private fun keyWhen(taskId: String) = PREFIX_WHEN + taskId

    companion object {
        private const val TAG = "TodoAlarms"
        private const val PREFS = "alarm_codes"
        private const val PREFIX_CODE = "code:"
        private const val PREFIX_WHEN = "when:"
        private const val KEY_NEXT_CODE = "next_code"
        private const val KEY_PROMPT_HOUR = "prompt_hour"
        private const val KEY_PROMPT_MINUTE = "prompt_minute"

        /** Task alarm codes start here; +500000 is the paired "show alarm" activity intent. */
        private const val CODE_BASE = 1_000
        private const val SHOW_OFFSET = 500_000
        private const val DAILY_PROMPT_CODE = 777

        private val FLAGS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
    }
}
