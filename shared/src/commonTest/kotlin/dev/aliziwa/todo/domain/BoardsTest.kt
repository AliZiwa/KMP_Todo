package dev.aliziwa.todo.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardsTest {
    private val zone = TimeZone.of("UTC")
    private val today = LocalDate(2026, 10, 9)
    private val yesterday = LocalDate(2026, 10, 8)

    private fun task(
        id: String,
        scope: TaskScope = TaskScope.DAY,
        date: LocalDate? = today,
        status: TaskStatus = TaskStatus.PENDING,
        dueAt: Long? = null,
        recurringMinutes: Int? = null,
        recurringStart: LocalDate? = null,
    ) = Task(
        id = id,
        title = id,
        scope = scope,
        scheduledDate = date,
        status = status,
        dueAt = dueAt,
        recurringTimeMinutes = recurringMinutes,
        recurringStartDate = recurringStart,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun overdueFromPreviousDaysRollsIntoToday() {
        val snapshot = TaskSnapshot(
            tasks = listOf(
                task("old", date = yesterday),
                task("oldDone", date = yesterday, status = TaskStatus.DONE),
                task("todays"),
            )
        )
        val board = Boards.day(snapshot, today, zone)
        assertEquals(listOf("old"), board.overdue.map { it.key })
        assertEquals(listOf("todays"), board.items.map { it.key })
    }

    @Test
    fun undatedDayTasksShowUnderToday() {
        val snapshot = TaskSnapshot(tasks = listOf(task("floating", date = null)))
        assertEquals(listOf("floating"), Boards.day(snapshot, today, zone).items.map { it.key })
    }

    @Test
    fun recurringInstanceIsMaterializedWithoutARow() {
        val snapshot = TaskSnapshot(
            tasks = listOf(task("routine", scope = TaskScope.RECURRING_DAILY, date = null, recurringMinutes = 7 * 60))
        )
        val items = Boards.recurring(snapshot, today, zone)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(TaskKeys.forInstance("routine", today), item.key)
        assertEquals(TaskStatus.PENDING, item.status)
        assertEquals(DateMath.atMinutes(today, 7 * 60, zone), item.dueAt)
        assertTrue(item.isRecurringInstance)
    }

    @Test
    fun completingTodayDoesNotAffectTomorrow() {
        val snapshot = TaskSnapshot(
            tasks = listOf(task("routine", scope = TaskScope.RECURRING_DAILY, date = null, recurringMinutes = 480)),
            completions = listOf(
                RecurringCompletion(
                    templateId = "routine",
                    date = today,
                    status = TaskStatus.DONE,
                    dueAt = DateMath.atMinutes(today, 480, zone),
                    completedAt = 5,
                    updatedAt = 5,
                )
            ),
        )
        assertEquals(TaskStatus.DONE, Boards.recurring(snapshot, today, zone).first().status)
        val tomorrow = LocalDate(2026, 10, 10)
        assertEquals(TaskStatus.PENDING, Boards.instancesFor(snapshot, tomorrow, zone).first().status)
    }

    @Test
    fun templateIsInactiveBeforeItsStartDate() {
        val snapshot = TaskSnapshot(
            tasks = listOf(
                task(
                    "future",
                    scope = TaskScope.RECURRING_DAILY,
                    date = null,
                    recurringMinutes = 600,
                    recurringStart = LocalDate(2026, 10, 20),
                )
            )
        )
        assertTrue(Boards.recurring(snapshot, today, zone).isEmpty())
        assertEquals(1, Boards.recurring(snapshot, LocalDate(2026, 10, 21), zone).size)
    }

    @Test
    fun weekAndMonthBoardsFilterByRange() {
        val snapshot = TaskSnapshot(
            tasks = listOf(
                task("thisWeek", scope = TaskScope.WEEK, date = LocalDate(2026, 10, 7)),
                task("nextWeek", scope = TaskScope.WEEK, date = LocalDate(2026, 10, 14)),
                task("thisMonth", scope = TaskScope.MONTH, date = LocalDate(2026, 10, 30)),
                task("nextMonth", scope = TaskScope.MONTH, date = LocalDate(2026, 11, 2)),
            )
        )
        assertEquals(listOf("thisWeek"), Boards.week(snapshot, today).items.map { it.key })
        assertEquals(listOf("thisMonth"), Boards.month(snapshot, today).items.map { it.key })
    }

    @Test
    fun alarmCandidatesIncludeRecurringHorizonAndSkipDone() {
        val snapshot = TaskSnapshot(
            tasks = listOf(
                task("timed", dueAt = 123_456),
                task("untimed"),
                task("doneTimed", status = TaskStatus.DONE, dueAt = 99),
                task("routine", scope = TaskScope.RECURRING_DAILY, date = null, recurringMinutes = 540),
            )
        )
        val keys = Boards.alarmCandidates(snapshot, today, zone, horizonDays = 1).map { it.key }
        assertTrue("timed" in keys)
        assertTrue("untimed" !in keys)
        assertTrue("doneTimed" !in keys)
        assertTrue(TaskKeys.forInstance("routine", today) in keys)
        assertTrue(TaskKeys.forInstance("routine", LocalDate(2026, 10, 10)) in keys)
    }

    @Test
    fun resolveFindsBothPlainAndInstanceKeys() {
        val snapshot = TaskSnapshot(
            tasks = listOf(
                task("plain", dueAt = 10),
                task("routine", scope = TaskScope.RECURRING_DAILY, date = null, recurringMinutes = 540),
            )
        )
        assertNotNull(Boards.resolve(snapshot, "plain", zone))
        val instance = Boards.resolve(snapshot, TaskKeys.forInstance("routine", today), zone)
        assertNotNull(instance)
        assertEquals(today, instance.instanceDate)
    }
}
