package dev.aliziwa.todo.scheduling

import dev.aliziwa.todo.data.SettingsRepository
import dev.aliziwa.todo.data.TaskRepository
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskSnapshot
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.platform.StickySummary
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The brain behind alarms and alerts.
 *
 * Responsibilities:
 *  - keep [dev.aliziwa.todo.platform.AlarmScheduler] in sync with every pending future due time
 *  - decide when a task is "ringing" and push it to [dev.aliziwa.todo.platform.AlertPresenter] +
 *    [dev.aliziwa.todo.platform.SoundPlayer], one alert at a time (queue)
 *  - keep the platform's sticky surface up to date
 *  - own the once-per-day "plan your day" decision
 *
 * Everything here works with no UI alive: the platform may call into it straight from a broadcast
 * receiver or a notification action.
 */
class DueEngine(
    private val repo: TaskRepository,
    private val settings: SettingsRepository,
    private val platform: PlatformServices,
    private val scope: CoroutineScope,
) {
    private val tag = "DueEngine"

    private val _currentAlert = MutableStateFlow<DueAlert?>(null)
    val currentAlert: StateFlow<DueAlert?> = _currentAlert.asStateFlow()

    private val _stickySummary = MutableStateFlow(StickySummary(null, null, 0, 0, 0))
    val stickySummary: StateFlow<StickySummary> = _stickySummary.asStateFlow()

    private val _dailyPromptRequested = MutableStateFlow(false)
    val dailyPromptRequested: StateFlow<Boolean> = _dailyPromptRequested.asStateFlow()

    /** Coarse clock for the sticky surface; UI countdowns use their own 1s ticker. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val queue = ArrayDeque<String>()
    private val lock = Mutex()
    private val scheduled = mutableMapOf<String, ScheduledAlarm>()
    private var started = false
    private var soundPlaying = false

    private fun now() = platform.clock.nowEpochMillis()
    private fun zone() = DateMath.zone(platform.clock.timeZoneId())

    /** Idempotent. Safe to call from `TodoApp.start()` with no UI. */
    fun start() {
        if (started) return
        started = true
        _tick.value = now()

        scope.launch {
            repo.snapshot.collectLatest { snapshot ->
                syncAlarms(snapshot)
                evaluateSnapshot(snapshot)
                refreshSticky(snapshot)
            }
        }

        scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                _tick.value = now()
                runCatching {
                    val snapshot = repo.snapshot.value
                    syncAlarms(snapshot)
                    evaluateSnapshot(snapshot)
                    refreshSticky(snapshot)
                    maybeRequestDailyPrompt()
                }.onFailure { Log.e(tag, "tick failed", it) }
            }
        }

        scope.launch {
            settings.settings.drop(1).collectLatest { scheduleDailyPrompt() }
        }

        scope.launch {
            runCatching {
                repo.pruneAlertState()
                resyncAlarms()
                maybeRequestDailyPrompt()
            }.onFailure { Log.e(tag, "initial sync failed", it) }
        }
    }

    // ------------------------------------------------------------ alarm sync

    /** Re-arms every pending future alarm from scratch. Called at start and from BootReceiver. */
    suspend fun resyncAlarms() {
        val snapshot = repo.snapshotNow()
        lock.withLock {
            scheduled.clear()
            runCatching { platform.alarms.cancelAll() }
                .onFailure { Log.w(tag, "cancelAll failed", it) }
        }
        syncAlarms(snapshot)
        scheduleDailyPrompt()
        evaluateSnapshot(snapshot)
        refreshSticky(snapshot)
        Log.i(tag, "resyncAlarms: ${scheduled.size} alarm(s) armed")
    }

    private suspend fun syncAlarms(snapshot: TaskSnapshot) {
        val now = now()
        val zone = zone()
        val today = DateMath.today(now, zone)
        // Title travels with the alarm: iOS composes the notification body at schedule time.
        // For a recurring instance TaskItem.title is already the template's title.
        val desired: Map<String, ScheduledAlarm> = Boards.alarmCandidates(snapshot, today, zone)
            .filter { it.dueAt != null && it.dueAt > now }
            .associate { it.key to ScheduledAlarm(it.dueAt!!, it.title) }

        lock.withLock {
            (scheduled.keys - desired.keys).forEach { key ->
                runCatching { platform.alarms.cancel(key) }.onFailure { Log.w(tag, "cancel $key", it) }
                scheduled.remove(key)
            }
            desired.forEach { (key, alarm) ->
                if (scheduled[key] != alarm) {
                    runCatching { platform.alarms.schedule(key, alarm.dueAt, alarm.title) }
                        .onFailure { Log.w(tag, "schedule $key", it) }
                    scheduled[key] = alarm
                }
            }
        }
    }

    private suspend fun scheduleDailyPrompt() {
        val s = settings.current()
        runCatching {
            if (s.dailyPromptEnabled) {
                platform.alarms.scheduleDailyPrompt(s.dailyPromptHour, s.dailyPromptMinute)
            } else {
                platform.alarms.cancelDailyPrompt()
            }
        }.onFailure { Log.w(tag, "daily prompt scheduling failed", it) }
    }

    // ------------------------------------------------------------- evaluation

    /** Called by the platform when an alarm fires (possibly with no UI alive). */
    suspend fun evaluate(key: String) {
        val item = repo.resolveFresh(key)
        if (item == null) {
            Log.w(tag, "alarm for unknown key $key")
            runCatching { platform.alarms.cancel(key) }
            return
        }
        considerAlert(item)
        pump()
        refreshSticky(repo.snapshotNow())
    }

    /** Catches up after the process was dead, resumed from sleep, or the clock jumped. */
    suspend fun evaluateAll() {
        val snapshot = repo.snapshotNow()
        syncAlarms(snapshot)
        evaluateSnapshot(snapshot)
        refreshSticky(snapshot)
    }

    private suspend fun evaluateSnapshot(snapshot: TaskSnapshot) {
        val now = now()
        val zone = zone()
        val today = DateMath.today(now, zone)
        val ringing = Boards.alarmCandidates(snapshot, today, zone, horizonDays = 0)
            .filter { it.dueAt != null && it.dueAt <= now + DUE_TOLERANCE_MILLIS && it.status == TaskStatus.PENDING }
            .sortedBy { it.dueAt }
        ringing.forEach { considerAlert(it) }
        reconcileCurrentAlert(snapshot)
        pump()
    }

    private suspend fun considerAlert(item: TaskItem) {
        val dueAt = item.dueAt ?: return
        if (item.status != TaskStatus.PENDING) return
        // Platform alarms may fire a few hundred ms early; treat that as due rather than dropping
        // the alert until the next tick.
        if (dueAt > now() + DUE_TOLERANCE_MILLIS) return
        if (repo.alreadyAlerted(item.key, dueAt)) return
        repo.markAlerted(item.key, dueAt)
        lock.withLock {
            if (_currentAlert.value?.taskId != item.key && !queue.contains(item.key)) {
                queue.addLast(item.key)
            }
        }
        Log.i(tag, "queued alert for ${item.key} (${item.title})")
    }

    /** If the showing alert's task was acted on elsewhere, take it down. */
    private suspend fun reconcileCurrentAlert(snapshot: TaskSnapshot) {
        val showing = _currentAlert.value ?: return
        val item = Boards.resolve(snapshot, showing.taskId, zone())
        if (item == null ||
            item.status != TaskStatus.PENDING ||
            (item.dueAt ?: 0) > now() + DUE_TOLERANCE_MILLIS
        ) {
            clearAlert(showing.taskId)
        }
    }

    private suspend fun pump() {
        if (_currentAlert.value != null) return
        val nextKey = lock.withLock { queue.removeFirstOrNull() } ?: return
        val item = repo.resolveFresh(nextKey)
        if (item == null || item.status != TaskStatus.PENDING) {
            pump()
            return
        }
        val alert = DueAlert(
            taskId = item.key,
            title = item.title,
            dueAtEpochMillis = item.dueAt ?: now(),
            priority = item.priority.ordinal,
            isRecurring = item.isRecurringInstance,
            extensionCount = item.extensionCount,
        )
        _currentAlert.value = alert
        runCatching { platform.alerts.showDueAlert(alert) }.onFailure { Log.e(tag, "showDueAlert", it) }
        if (settings.settings.value.soundEnabled && !soundPlaying) {
            soundPlaying = true
            runCatching { platform.sound.playDoorbell(loop = true) }
                .onFailure { Log.w(tag, "doorbell failed", it); soundPlaying = false }
        }
    }

    private suspend fun clearAlert(key: String) {
        if (_currentAlert.value?.taskId != key) {
            lock.withLock { queue.remove(key) }
            runCatching { platform.alerts.dismissDueAlert(key) }
            return
        }
        _currentAlert.value = null
        runCatching { platform.alerts.dismissDueAlert(key) }.onFailure { Log.w(tag, "dismissDueAlert", it) }
        if (soundPlaying) {
            soundPlaying = false
            runCatching { platform.sound.stop() }.onFailure { Log.w(tag, "sound stop", it) }
        }
        pump()
    }

    // ---------------------------------------------------------------- actions

    suspend fun complete(key: String) {
        repo.complete(key)
        afterAction(key)
    }

    suspend fun extend(key: String, byMinutes: Int) {
        val newDue = repo.extend(key, byMinutes)
        val title = repo.resolveFresh(key)?.title
        afterAction(key)
        if (newDue != null && title != null) {
            runCatching { platform.alarms.schedule(key, newDue, title) }
                .onFailure { Log.w(tag, "re-arm $key", it) }
            lock.withLock { scheduled[key] = ScheduledAlarm(newDue, title) }
        }
    }

    suspend fun dismiss(key: String) {
        repo.dismiss(key)
        afterAction(key)
    }

    suspend fun delete(key: String): dev.aliziwa.todo.data.DeletedTask? {
        val deleted = repo.delete(key)
        afterAction(key)
        runCatching { platform.alarms.cancel(key) }
        lock.withLock { scheduled.remove(key) }
        return deleted
    }

    private suspend fun afterAction(key: String) {
        clearAlert(key)
        val snapshot = repo.snapshotNow()
        syncAlarms(snapshot)
        refreshSticky(snapshot)
    }

    /** User closed the in-app alert without choosing an action: keep the task, stop the noise. */
    suspend fun silenceCurrentAlert() {
        _currentAlert.value?.let { clearAlert(it.taskId) }
    }

    // ----------------------------------------------------------- daily prompt

    /** Called by the platform's morning alarm, or on first foreground of the day. */
    suspend fun onDailyPromptFired() = maybeRequestDailyPrompt(force = false)

    /**
     * Asks for the plan sheet when it is owed. The day is only marked as prompted once the user
     * actually *sees* it ([consumeDailyPrompt]), so a prompt they ignore comes back on the next
     * launch / wake instead of being silently lost for the day.
     */
    suspend fun maybeRequestDailyPrompt(force: Boolean = false) {
        // Already asking: don't push showDailyPrompt() at the platform again.
        if (_dailyPromptRequested.value) return
        val s = settings.current()
        if (!s.dailyPromptEnabled) return
        val now = now()
        val zone = zone()
        val today = DateMath.today(now, zone)
        if (!force && s.lastDailyPromptDate == today.toString()) return
        val promptMinutes = s.dailyPromptHour * 60 + s.dailyPromptMinute
        if (!force && DateMath.minutesOfDay(now, zone) < promptMinutes) return

        _dailyPromptRequested.value = true
        runCatching { platform.alerts.showDailyPrompt() }.onFailure { Log.w(tag, "showDailyPrompt", it) }
        Log.i(tag, "daily prompt requested for $today")
    }

    /**
     * UI calls this once it has shown (or the user dismissed) the plan sheet. Only now is the day
     * recorded as prompted.
     */
    fun consumeDailyPrompt() {
        if (!_dailyPromptRequested.value) return
        _dailyPromptRequested.value = false
        runCatching { platform.alerts.dismissDailyPrompt() }
        scope.launch {
            runCatching {
                val today = DateMath.today(now(), zone())
                settings.update { it.copy(lastDailyPromptDate = today.toString()) }
                Log.i(tag, "daily prompt consumed for $today")
            }.onFailure { Log.w(tag, "persisting daily prompt date failed", it) }
        }
    }

    // ---------------------------------------------------------------- sticky

    private fun refreshSticky(snapshot: TaskSnapshot) {
        val now = now()
        val zone = zone()
        val today = DateMath.today(now, zone)
        val board = Boards.day(snapshot, today, zone)
        val all = board.overdue + board.items
        val pending = all.filter { it.status == TaskStatus.PENDING }
        val next = pending.filter { it.dueAt != null && it.dueAt > now }.minByOrNull { it.dueAt!! }
        _stickySummary.value = StickySummary(
            nextTaskTitle = next?.title,
            nextDueAtEpochMillis = next?.dueAt,
            dueNowCount = pending.count { it.dueAt != null && it.dueAt <= now },
            remainingTodayCount = pending.size,
            completedTodayCount = all.count { it.status == TaskStatus.DONE },
        )
        runCatching { platform.alerts.updateSticky(_stickySummary.value) }
            .onFailure { Log.w(tag, "updateSticky", it) }
    }

    /** What we last handed to the platform scheduler for a key. */
    private data class ScheduledAlarm(val dueAt: Long, val title: String)

    private companion object {
        const val TICK_MILLIS = 20_000L

        /** Platform alarms are allowed to fire this much early and still count as due. */
        const val DUE_TOLERANCE_MILLIS = 1_500L
    }
}
