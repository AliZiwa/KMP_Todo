package dev.aliziwa.todo.scheduling

import dev.aliziwa.todo.data.SettingsRepository
import dev.aliziwa.todo.data.TaskRepository
import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskKeys
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.fakes.FakeClock
import dev.aliziwa.todo.fakes.TestPlatform
import dev.aliziwa.todo.support.TestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DueEngineTest {
    private lateinit var db: TodoDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TaskRepository
    private lateinit var settings: SettingsRepository
    private lateinit var platform: TestPlatform
    private lateinit var engine: DueEngine
    private lateinit var clock: FakeClock

    private val zone = TimeZone.of("UTC")
    private val today = LocalDate(2026, 10, 9)

    @BeforeTest
    fun setUp() {
        db = TestDatabase.create()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        clock = FakeClock(DateMath.atMinutes(today, 10 * 60, zone), "UTC")
        platform = TestPlatform(clock)
        repo = TaskRepository(db.taskDao(), db.recurringCompletionDao(), db.alertStateDao(), clock, scope)
        settings = SettingsRepository(db.settingsDao(), scope)
        engine = DueEngine(repo, settings, platform.services, scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun addTask(title: String, atMinutes: Int?, scope: TaskScope = TaskScope.DAY): String =
        repo.create(TaskDraft(title = title, scope = scope, date = today, timeMinutes = atMinutes))!!

    /** consumeDailyPrompt() persists on the engine scope, so tests wait for it. */
    private suspend fun awaitUntil(timeoutMillis: Long = 3_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    @Test
    fun resyncArmsEveryPendingFutureAlarmAndCancelsFirst(): Unit = runBlocking {
        val future = addTask("Future", 15 * 60)
        addTask("Past", 9 * 60)
        addTask("NoTime", null)
        val done = addTask("DoneFuture", 16 * 60)
        repo.complete(done)

        engine.resyncAlarms()

        assertEquals(1, platform.alarmsFake.cancelAllCount)
        assertEquals(setOf(future), platform.alarmsFake.scheduled.keys)
        assertEquals(DateMath.atMinutes(today, 15 * 60, zone), platform.alarmsFake.scheduled[future])
    }

    @Test
    fun alarmsCarryTheTaskTitleForNotificationBodies(): Unit = runBlocking {
        val plain = addTask("Submit timesheet", 15 * 60)
        val template = addTask("Vitamins", 18 * 60, TaskScope.RECURRING_DAILY)
        engine.resyncAlarms()

        assertEquals("Submit timesheet", platform.alarmsFake.titles[plain])
        // A recurring instance carries the template's title.
        assertEquals("Vitamins", platform.alarmsFake.titles[TaskKeys.forInstance(template, today)])
    }

    @Test
    fun extendRearmsWithTheTitle(): Unit = runBlocking {
        val id = addTask("Call plumber", 9 * 60)
        engine.evaluate(id)
        engine.extend(id, 15)
        assertEquals("Call plumber", platform.alarmsFake.titles[id])
        assertEquals(clock.nowMillis + 15 * 60_000L, platform.alarmsFake.scheduled[id])
    }

    @Test
    fun renamingATaskRearmsTheAlarmSoTheBodyStaysCorrect(): Unit = runBlocking {
        val id = addTask("Old name", 15 * 60)
        engine.resyncAlarms()
        assertEquals("Old name", platform.alarmsFake.titles[id])

        repo.update(
            id,
            TaskDraft(title = "New name", scope = TaskScope.DAY, date = today, timeMinutes = 15 * 60),
        )
        engine.evaluateAll()
        assertEquals("New name", platform.alarmsFake.titles[id])
    }

    @Test
    fun aSchedulerThatOnlyImplementsTheTwoArgOverloadStillGetsAlarms(): Unit = runBlocking {
        val legacy = dev.aliziwa.todo.fakes.LegacyAlarmScheduler()
        val legacyPlatform = dev.aliziwa.todo.platform.PlatformServices(
            alarms = legacy,
            alerts = platform.alertsFake,
            sound = platform.soundFake,
            paths = dev.aliziwa.todo.fakes.FakePaths(),
            clock = clock,
            http = dev.aliziwa.todo.fakes.FakeHttp(),
            capabilities = platform.capabilitiesFake,
            localLlmEngine = null,
        )
        val legacyEngine = DueEngine(repo, settings, legacyPlatform, scope)
        val id = addTask("Legacy path", 15 * 60)
        legacyEngine.resyncAlarms()
        assertEquals(DateMath.atMinutes(today, 15 * 60, zone), legacy.scheduled[id])
    }

    @Test
    fun resyncAlsoArmsTodaysRecurringInstance(): Unit = runBlocking {
        val template = addTask("Vitamins", 18 * 60, TaskScope.RECURRING_DAILY)
        engine.resyncAlarms()
        assertTrue(platform.alarmsFake.scheduled.keys.contains(TaskKeys.forInstance(template, today)))
    }

    @Test
    fun recurringTemplateItselfIsNeverArmedDirectly(): Unit = runBlocking {
        val template = addTask("Vitamins", 18 * 60, TaskScope.RECURRING_DAILY)
        engine.resyncAlarms()
        assertTrue(!platform.alarmsFake.scheduled.keys.contains(template))
    }

    @Test
    fun anOverdueTaskAlertsAndRingsTheDoorbell(): Unit = runBlocking {
        val id = addTask("Standup", 9 * 60)
        engine.evaluate(id)

        assertEquals(1, platform.alertsFake.shown.size)
        assertEquals("Standup", platform.alertsFake.shown.single().title)
        assertEquals(id, engine.currentAlert.value?.taskId)
        assertTrue(platform.soundFake.playing)
    }

    @Test
    fun actingOnTheAlertDismissesItAndStopsTheSound(): Unit = runBlocking {
        val id = addTask("Standup", 9 * 60)
        engine.evaluate(id)
        engine.complete(id)

        assertNull(engine.currentAlert.value)
        assertTrue(platform.alertsFake.dismissed.contains(id))
        assertTrue(!platform.soundFake.playing)
        assertEquals(TaskStatus.DONE.name, db.taskDao().byId(id)!!.status)
    }

    @Test
    fun theSameDueInstantOnlyAlertsOnce(): Unit = runBlocking {
        val id = addTask("Standup", 9 * 60)
        engine.evaluate(id)
        engine.evaluate(id)
        engine.evaluateAll()
        assertEquals(1, platform.alertsFake.shown.size)
    }

    @Test
    fun twoDueTasksAlertOneAtATime(): Unit = runBlocking {
        val a = addTask("First", 8 * 60)
        val b = addTask("Second", 9 * 60)

        engine.evaluateAll()
        assertEquals(1, platform.alertsFake.shown.size)
        val firstShown = engine.currentAlert.value!!.taskId

        engine.dismiss(firstShown)
        assertEquals(2, platform.alertsFake.shown.size)
        val secondShown = engine.currentAlert.value!!.taskId
        assertTrue(setOf(a, b) == setOf(firstShown, secondShown))
        // Only one doorbell instance is ever started at a time.
        assertEquals(2, platform.soundFake.playCount)
    }

    @Test
    fun extendingClearsTheAlertRearmsTheAlarmAndAllowsAFutureRealert(): Unit = runBlocking {
        val id = addTask("Call", 9 * 60)
        engine.evaluate(id)
        engine.extend(id, 15)

        assertNull(engine.currentAlert.value)
        val newDue = clock.nowMillis + 15 * 60_000L
        assertEquals(newDue, platform.alarmsFake.scheduled[id])
        assertTrue(!repo.alreadyAlerted(id, newDue))

        clock.nowMillis = newDue
        engine.evaluate(id)
        assertEquals(2, platform.alertsFake.shown.size)
    }

    @Test
    fun deletingFromAnAlertCancelsTheAlarm(): Unit = runBlocking {
        val id = addTask("Gone", 9 * 60)
        engine.evaluate(id)
        val deleted = engine.delete(id)
        assertNotNull(deleted)
        assertNull(engine.currentAlert.value)
        assertNull(platform.alarmsFake.scheduled[id])
        assertNull(db.taskDao().byId(id))
    }

    @Test
    fun alarmForAnUnknownKeyIsCancelledNotCrashed(): Unit = runBlocking {
        engine.evaluate("does-not-exist")
        assertTrue(platform.alarmsFake.cancelled.contains("does-not-exist"))
        assertNull(engine.currentAlert.value)
    }

    @Test
    fun soundStaysSilentWhenTheUserTurnedItOff(): Unit = runBlocking {
        settings.update { it.copy(soundEnabled = false) }
        // Let the settings StateFlow catch up.
        while (settings.settings.value.soundEnabled) { kotlinx.coroutines.delay(10) }
        val id = addTask("Quiet", 9 * 60)
        engine.evaluate(id)
        assertEquals(1, platform.alertsFake.shown.size)
        assertTrue(!platform.soundFake.playing)
    }

    @Test
    fun stickySummaryCountsNextRemainingAndCompleted(): Unit = runBlocking {
        addTask("Later", 15 * 60)
        addTask("Even later", 18 * 60)
        val overdue = addTask("Overdue", 9 * 60)
        val done = addTask("Finished", 8 * 60)
        repo.complete(done)

        engine.evaluateAll()
        val sticky = platform.alertsFake.lastSticky
        assertNotNull(sticky)
        assertEquals("Later", sticky.nextTaskTitle)
        assertEquals(DateMath.atMinutes(today, 15 * 60, zone), sticky.nextDueAtEpochMillis)
        assertEquals(1, sticky.dueNowCount)
        assertEquals(3, sticky.remainingTodayCount)
        assertEquals(1, sticky.completedTodayCount)
        assertEquals(overdue, engine.currentAlert.value?.taskId)
    }

    @Test
    fun dailyPromptFiresOnceADayAfterTheConfiguredTime(): Unit = runBlocking {
        settings.update { it.copy(dailyPromptHour = 8, dailyPromptMinute = 0) }
        engine.maybeRequestDailyPrompt()
        assertTrue(engine.dailyPromptRequested.value)
        assertEquals(1, platform.alertsFake.dailyPromptShown)
        // The day is NOT yet marked as prompted — the user has not seen it.
        assertNull(settings.current().lastDailyPromptDate)

        // While it is still pending, asking again must not re-fire the platform surface.
        engine.maybeRequestDailyPrompt()
        assertEquals(1, platform.alertsFake.dailyPromptShown)

        engine.consumeDailyPrompt()
        assertTrue(!engine.dailyPromptRequested.value)
        awaitUntil { settings.current().lastDailyPromptDate == today.toString() }

        // Now it is done for the day.
        engine.maybeRequestDailyPrompt()
        assertTrue(!engine.dailyPromptRequested.value)
        assertEquals(1, platform.alertsFake.dailyPromptShown)
    }

    @Test
    fun anIgnoredPromptIsRequestedAgainAfterRestart(): Unit = runBlocking {
        settings.update { it.copy(dailyPromptHour = 8, dailyPromptMinute = 0) }
        engine.maybeRequestDailyPrompt()
        assertTrue(engine.dailyPromptRequested.value)
        assertEquals(1, platform.alertsFake.dailyPromptShown)
        // User ignores it: never consumed, so nothing is persisted.
        assertNull(settings.current().lastDailyPromptDate)

        // Simulate a fresh process: a brand-new engine over the same database.
        val restarted = DueEngine(repo, settings, platform.services, scope)
        restarted.maybeRequestDailyPrompt()
        assertTrue(restarted.dailyPromptRequested.value)
        assertEquals(2, platform.alertsFake.dailyPromptShown)

        restarted.consumeDailyPrompt()
        awaitUntil { settings.current().lastDailyPromptDate == today.toString() }

        val afterConsume = DueEngine(repo, settings, platform.services, scope)
        afterConsume.maybeRequestDailyPrompt()
        assertTrue(!afterConsume.dailyPromptRequested.value)
        assertEquals(2, platform.alertsFake.dailyPromptShown)
    }

    @Test
    fun anAlarmThatFiresSlightlyEarlyStillAlerts(): Unit = runBlocking {
        val id = addTask("Nearly due", 11 * 60)
        // 800ms before the due instant — inside the tolerance window.
        clock.nowMillis = DateMath.atMinutes(today, 11 * 60, zone) - 800
        engine.evaluate(id)
        assertEquals(id, engine.currentAlert.value?.taskId)
        assertTrue(platform.soundFake.playing)
    }

    @Test
    fun anAlarmThatFiresFarTooEarlyIsIgnored(): Unit = runBlocking {
        val id = addTask("Not yet", 11 * 60)
        engine.resyncAlarms()
        clock.nowMillis = DateMath.atMinutes(today, 11 * 60, zone) - 30_000
        engine.evaluate(id)
        assertNull(engine.currentAlert.value)
        assertTrue(!platform.soundFake.playing)
        // And it is still armed for its real time.
        assertEquals(DateMath.atMinutes(today, 11 * 60, zone), platform.alarmsFake.scheduled[id])
    }

    @Test
    fun anEarlyAlertSurvivesTheNextSnapshotReconcile(): Unit = runBlocking {
        val id = addTask("Nearly due", 11 * 60)
        clock.nowMillis = DateMath.atMinutes(today, 11 * 60, zone) - 800
        engine.evaluate(id)
        assertEquals(id, engine.currentAlert.value?.taskId)
        // A re-evaluation at the same instant must not tear the alert down again.
        engine.evaluateAll()
        assertEquals(id, engine.currentAlert.value?.taskId)
    }

    @Test
    fun dailyPromptWaitsUntilTheConfiguredTime(): Unit = runBlocking {
        settings.update { it.copy(dailyPromptHour = 23, dailyPromptMinute = 30) }
        engine.maybeRequestDailyPrompt()
        assertTrue(!engine.dailyPromptRequested.value)
        assertNull(settings.current().lastDailyPromptDate)
    }

    @Test
    fun dailyPromptCanBeDisabled(): Unit = runBlocking {
        settings.update { it.copy(dailyPromptEnabled = false) }
        engine.resyncAlarms()
        assertNull(platform.alarmsFake.dailyPrompt)
        engine.maybeRequestDailyPrompt()
        assertTrue(!engine.dailyPromptRequested.value)
    }

    @Test
    fun resyncSchedulesTheDailyPromptAtTheConfiguredTime(): Unit = runBlocking {
        settings.update { it.copy(dailyPromptHour = 7, dailyPromptMinute = 15) }
        engine.resyncAlarms()
        assertEquals(7 to 15, platform.alarmsFake.dailyPrompt)
    }

    @Test
    fun aClockJumpBackwardsDoesNotLoseTheAlarm(): Unit = runBlocking {
        val id = addTask("Later", 15 * 60)
        engine.resyncAlarms()
        clock.nowMillis = DateMath.atMinutes(today, 6 * 60, zone)
        engine.resyncAlarms()
        assertEquals(DateMath.atMinutes(today, 15 * 60, zone), platform.alarmsFake.scheduled[id])
        assertNull(engine.currentAlert.value)
    }

    @Test
    fun catchUpAfterTheProcessWasDeadStillAlerts(): Unit = runBlocking {
        val id = addTask("Missed while dead", 11 * 60)
        engine.resyncAlarms()
        assertNotNull(platform.alarmsFake.scheduled[id])

        clock.nowMillis = DateMath.atMinutes(today, 12 * 60, zone)
        engine.evaluateAll()
        assertEquals(id, engine.currentAlert.value?.taskId)
        assertNull(platform.alarmsFake.scheduled[id])
    }

    @Test
    fun silencingAnAlertKeepsTheTaskPending(): Unit = runBlocking {
        val id = addTask("Still mine", 9 * 60)
        engine.evaluate(id)
        engine.silenceCurrentAlert()
        assertNull(engine.currentAlert.value)
        assertTrue(!platform.soundFake.playing)
        assertEquals(TaskStatus.PENDING.name, db.taskDao().byId(id)!!.status)
    }
}
