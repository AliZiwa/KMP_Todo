package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.AlarmScheduler
import dev.aliziwa.todo.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects "the machine was asleep" / "the clock jumped" by watching the divergence between the
 * wall clock and the monotonic clock on a 1-second ticker.
 *
 * Two independent signals, either of which means we lost time:
 *  - the ticker itself was starved for more than [thresholdMillis] (sleep usually freezes timers), and
 *  - wall-clock delta and monotonic delta disagree by more than [thresholdMillis] (sleep on some
 *    machines keeps `nanoTime` ticking, and a manual clock change moves only the wall clock).
 */
class WakeDetector(
    private val scope: CoroutineScope,
    private val thresholdMillis: Long = 30_000,
    private val onWake: (lostMillis: Long, reason: String) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastWall = System.currentTimeMillis()
            var lastNano = System.nanoTime()
            while (isActive) {
                delay(1_000)
                val wall = System.currentTimeMillis()
                val nano = System.nanoTime()
                val wallDelta = wall - lastWall
                val nanoDelta = (nano - lastNano) / 1_000_000
                lastWall = wall
                lastNano = nano
                val divergence = wallDelta - nanoDelta
                when {
                    wallDelta > thresholdMillis ->
                        onWake(wallDelta, "timer starved for ${wallDelta}ms (sleep/suspend)")
                    kotlin.math.abs(divergence) > thresholdMillis ->
                        onWake(divergence, "wall clock moved ${divergence}ms relative to monotonic clock")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * In-process [AlarmScheduler] for the resident macOS app.
 *
 * There is no OS alarm service we can use from a plain JVM process, so every pending task with a
 * due time owns a coroutine timer keyed by task id. Each timer polls in bounded slices (<= 30s)
 * against the **wall clock** rather than sleeping for the whole remaining interval; that makes it
 * correct across DST shifts, manual clock changes and laptop sleep without relying on the wake
 * detector. The [WakeDetector] is still used as a second line of defence: on wake it re-fires
 * every overdue timer immediately and tells the shared layer that the app came back
 * (`TodoApp.onAppForegrounded()`), which is what catches alarms whose moment passed while the lid
 * was closed.
 */
class DesktopAlarmScheduler(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val onAlarm: (taskId: String) -> Unit,
    private val onDailyPrompt: () -> Unit,
    private val onWake: (lostMillis: Long) -> Unit,
    /**
     * Polling granularity for every timer. 30s in production: short enough that sleep/wake or a
     * clock change is noticed promptly, long enough to stay free. Tests shorten it.
     */
    private val sliceMillis: Long = DEFAULT_SLICE_MILLIS,
) : AlarmScheduler {

    private class Timer(val atEpochMillis: Long, val job: Job)

    private val timers = ConcurrentHashMap<String, Timer>()
    private val titles = ConcurrentHashMap<String, String>()
    private val dailyPromptAt = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
    private var dailyPromptJob: Job? = null
    private val started = AtomicBoolean(false)

    private val wakeDetector = WakeDetector(scope) { lost, reason ->
        DesktopLog.i(TAG, "wake/clock jump: $reason")
        handleWake(lost)
    }

    fun start() {
        if (started.compareAndSet(false, true)) wakeDetector.start()
    }

    fun shutdown() {
        wakeDetector.stop()
        cancelAll()
        cancelDailyPrompt()
    }

    /** Timers currently armed — exposed for diagnostics and tests. */
    fun armed(): Map<String, Long> = timers.mapValues { it.value.atEpochMillis }

    override fun schedule(taskId: String, atEpochMillis: Long) {
        timers.remove(taskId)?.job?.cancel()
        val job = scope.launch {
            while (isActive) {
                val remaining = atEpochMillis - clock.nowEpochMillis()
                if (remaining <= 0) break
                // Bounded slices so sleep/suspend or a clock change cannot leave us parked forever.
                delay(remaining.coerceAtMost(sliceMillis))
            }
            if (isActive) fire(taskId)
        }
        timers[taskId] = Timer(atEpochMillis, job)
        DesktopLog.i(
            TAG,
            "armed ${titles[taskId] ?: taskId} ($taskId) for ${java.time.Instant.ofEpochMilli(atEpochMillis)} " +
                "(${timers.size} armed)",
        )
    }

    /**
     * Title-carrying overload the shared `DueEngine` always calls. The desktop composes its alert
     * window from the live DB rather than from the scheduled payload, so the title is only kept for
     * the log — which is what makes the alarm log readable while debugging.
     */
    override fun schedule(taskId: String, atEpochMillis: Long, title: String) {
        titles[taskId] = title
        schedule(taskId, atEpochMillis)
    }

    /** Title last scheduled for [taskId], for diagnostics. */
    fun titleOf(taskId: String): String? = titles[taskId]

    override fun cancel(taskId: String) {
        titles.remove(taskId)
        timers.remove(taskId)?.let {
            it.job.cancel()
            DesktopLog.i(TAG, "cancelled $taskId")
        }
    }

    override fun cancelAll() {
        if (timers.isEmpty()) return
        DesktopLog.i(TAG, "cancelling ${timers.size} timers")
        timers.values.forEach { it.job.cancel() }
        timers.clear()
        titles.clear()
    }

    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) {
        dailyPromptAt.set(hourOfDay to minute)
        armDailyPrompt()
    }

    override fun cancelDailyPrompt() {
        dailyPromptAt.set(null)
        dailyPromptJob?.cancel()
        dailyPromptJob = null
    }

    private fun armDailyPrompt() {
        dailyPromptJob?.cancel()
        val (hour, minute) = dailyPromptAt.get() ?: return
        dailyPromptJob = scope.launch {
            while (isActive) {
                val target = nextDailyPromptEpochMillis(hour, minute)
                while (isActive) {
                    val remaining = target - clock.nowEpochMillis()
                    if (remaining <= 0) break
                    delay(remaining.coerceAtMost(sliceMillis))
                }
                if (!isActive) return@launch
                DesktopLog.i(TAG, "daily prompt firing (scheduled $hour:$minute)")
                runCatching { onDailyPrompt() }
                    .onFailure { DesktopLog.e(TAG, "daily prompt handler failed", it) }
                // Guard against immediately re-firing for the same minute, then re-arm for tomorrow.
                delay(61_000)
            }
        }
        DesktopLog.i(
            TAG,
            "daily prompt armed for ${java.time.Instant.ofEpochMilli(nextDailyPromptEpochMillis(hour, minute))}",
        )
    }

    /** Next occurrence of hh:mm in the user's current zone, strictly in the future. */
    private fun nextDailyPromptEpochMillis(hour: Int, minute: Int): Long {
        val zone = runCatching { ZoneId.of(clock.timeZoneId()) }.getOrElse { ZoneId.systemDefault() }
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.nowEpochMillis()), zone)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var candidate = resolveLocal(now.toLocalDate(), time, zone)
        if (!candidate.isAfter(now)) candidate = resolveLocal(now.toLocalDate().plusDays(1), time, zone)
        return candidate.toInstant().toEpochMilli()
    }

    /**
     * DST-safe: `ZonedDateTime.of` already shifts a time that does not exist on a spring-forward
     * day and picks the earlier offset on a fall-back day, so the prompt still happens once.
     */
    private fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(date, time, zone)

    private fun fire(taskId: String) {
        timers.remove(taskId)
        DesktopLog.i(TAG, "alarm fired for $taskId")
        runCatching { onAlarm(taskId) }
            .onFailure { DesktopLog.e(TAG, "alarm handler failed for $taskId", it) }
    }

    private fun handleWake(lostMillis: Long) {
        val now = clock.nowEpochMillis()
        val overdue = timers.entries.filter { it.value.atEpochMillis <= now }.map { it.key }
        if (overdue.isNotEmpty()) {
            DesktopLog.i(TAG, "re-firing ${overdue.size} timers missed while asleep: $overdue")
            overdue.forEach { id ->
                timers.remove(id)?.job?.cancel()
                runCatching { onAlarm(id) }.onFailure { DesktopLog.e(TAG, "re-fire failed for $id", it) }
            }
        }
        // Re-arm the daily prompt: its target may now be in the past or on a new day.
        if (dailyPromptAt.get() != null) armDailyPrompt()
        runCatching { onWake(lostMillis) }.onFailure { DesktopLog.e(TAG, "wake handler failed", it) }
    }

    companion object {
        private const val TAG = "alarms"
        const val DEFAULT_SLICE_MILLIS = 30_000L
    }
}
