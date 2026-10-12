package dev.aliziwa.todo.data

import dev.aliziwa.todo.assistant.ChatRole
import dev.aliziwa.todo.assistant.PlanState
import dev.aliziwa.todo.assistant.PlannedAction
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskScope
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DemoDataTest {

    private val zone = TimeZone.of("Europe/London")

    /** 2026-06-10 is a Wednesday; 10:40 local leaves plenty of day in both directions. */
    private val now = DateMath.atMinutes(DateMath.parseDate("2026-06-10")!!, 10 * 60 + 40, zone)

    private fun plan() = DemoData.plan(now, zone)

    private fun bucket(bucket: DemoBucket) = plan().filter { it.bucket == bucket }

    @Test
    fun everyBucketIsFilled() {
        assertEquals(6, bucket(DemoBucket.TODAY_UPCOMING).size)
        assertEquals(1, bucket(DemoBucket.TODAY_OVERDUE).size)
        assertEquals(2, bucket(DemoBucket.TODAY_DONE).size)
        assertEquals(1, bucket(DemoBucket.TOMORROW).size)
        assertEquals(4, bucket(DemoBucket.THIS_WEEK).size)
        assertEquals(3, bucket(DemoBucket.THIS_MONTH).size)
        assertEquals(3, bucket(DemoBucket.RECURRING).size)
    }

    @Test
    fun theOnlyPendingThingInThePastIsTheOverdueTask() {
        val overdue = DemoData.overdueAfterSeeding(now, zone)

        assertEquals(listOf(DemoBucket.TODAY_OVERDUE), overdue.map { it.bucket })
        assertEquals("Post the parcel", overdue.single().draft.title)
    }

    /**
     * The store screenshots must show exactly ONE red row whatever time of day they are taken —
     * recurring instances whose time has gone by are seeded complete rather than left overdue.
     */
    @Test
    fun exactlyOneRowIsOverdueAtEveryHourOfTheDay() {
        val day = DateMath.parseDate("2026-06-10")!!
        (0..23).forEach { hour ->
            val at = DateMath.atMinutes(day, hour * 60 + 40, zone)
            val overdue = DemoData.overdueAfterSeeding(at, zone)
            assertEquals(1, overdue.size, "at ${hour}:40 overdue was ${overdue.map { it.draft.title }}")
            assertEquals("Post the parcel", overdue.single().draft.title)
        }
    }

    @Test
    fun recurringInstancesAlreadyPastTodayAreSeededComplete() {
        // 10:40 is after "Walk Biscuit" (07:30) and "Standup" (09:30) but before 18:00.
        val doneTitles = plan().filter { DemoData.isSeededDone(it, now, zone) }.map { it.draft.title }

        assertTrue("Walk Biscuit" in doneTitles)
        assertTrue("Standup" in doneTitles)
        assertTrue("Water the plants" !in doneTitles)
        // The two explicit "done today" tasks are still there, so the progress ring is never empty.
        assertTrue("Morning walk" in doneTitles)
        assertTrue("Pay the electricity bill" in doneTitles)
        // Never the overdue one.
        assertTrue("Post the parcel" !in doneTitles)
    }

    @Test
    fun earlyMorningLeavesEveryRoutineStillToDo() {
        val sixAm = DateMath.atMinutes(DateMath.parseDate("2026-06-10")!!, 6 * 60, zone)
        val recurring = DemoData.plan(sixAm, zone).filter { it.bucket == DemoBucket.RECURRING }

        assertTrue(recurring.none { DemoData.isSeededDone(it, sixAm, zone) })
        assertEquals(1, DemoData.overdueAfterSeeding(sixAm, zone).size)
    }

    @Test
    fun theFirstUpcomingTaskIsAboutTwentyFiveMinutesOut() {
        val nowMinutes = DateMath.minutesOfDay(now, zone)
        val times = bucket(DemoBucket.TODAY_UPCOMING).map { it.draft.timeMinutes!! }

        assertEquals(nowMinutes + 25, times.first())
        assertTrue(times.all { it > nowMinutes }, "every upcoming time must be after now: $times")
        assertTrue(times.all { it <= 23 * 60 + 59 }, "times must stay inside the day: $times")
        assertEquals(times.sorted(), times, "upcoming times should be in order")
    }

    @Test
    fun todaysTasksMixPriorities() {
        val priorities = bucket(DemoBucket.TODAY_UPCOMING).map { it.draft.priority }.toSet() +
            bucket(DemoBucket.TODAY_OVERDUE).map { it.draft.priority }
        assertEquals(setOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH), priorities)
    }

    @Test
    fun recurringTemplatesAllHaveATimeOfDay() {
        val recurring = bucket(DemoBucket.RECURRING)
        assertTrue(recurring.all { it.draft.scope == TaskScope.RECURRING_DAILY })
        assertEquals(
            listOf(7 * 60 + 30, 9 * 60 + 30, 18 * 60),
            recurring.map { it.draft.timeMinutes },
        )
        assertTrue(recurring.none { it.done })
    }

    @Test
    fun weekAndMonthRowsStayInsideTheirOwnRange() {
        val today = DateMath.today(now, zone)
        val (weekStart, weekEnd) = DateMath.weekRange(today)
        val (monthStart, monthEnd) = DateMath.monthRange(today)

        bucket(DemoBucket.THIS_WEEK).forEach { task ->
            assertEquals(TaskScope.WEEK, task.draft.scope)
            val date = assertNotNull(task.draft.date)
            assertTrue(date in weekStart..weekEnd, "$date outside $weekStart..$weekEnd")
        }
        bucket(DemoBucket.THIS_MONTH).forEach { task ->
            assertEquals(TaskScope.MONTH, task.draft.scope)
            val date = assertNotNull(task.draft.date)
            assertTrue(date in monthStart..monthEnd, "$date outside $monthStart..$monthEnd")
        }
    }

    @Test
    fun titlesAreShortAndNonEmpty() {
        plan().forEach { task ->
            assertTrue(task.draft.title.isNotBlank())
            assertTrue(task.draft.title.length <= 40, "too long: ${task.draft.title}")
        }
    }

    @Test
    fun theSeededTranscriptShowsAnAlreadyAppliedPlanForTwoRealTasks() {
        val today = DateMath.today(now, zone)
        val messages = DemoData.transcript(now, today)

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        val reply = messages.last()
        assertEquals(PlanState.CONFIRMED, reply.planState)
        val adds = assertNotNull(reply.plan).actions.filterIsInstance<PlannedAction.Add>()
        assertEquals(listOf("Gym", "Water the plants"), adds.map { it.draft.title })
        // The transcript must not claim anything the seeded data does not contain.
        val seededTitles = plan().map { it.draft.title }
        assertTrue(adds.all { it.draft.title in seededTitles }, "transcript references a missing task")
    }

    @Test
    fun aLateNightSeedStillKeepsEverythingUpcomingInsideTheDay() {
        val lateNight = DateMath.atMinutes(DateMath.parseDate("2026-06-10")!!, 23 * 60 + 50, zone)
        val nowMinutes = DateMath.minutesOfDay(lateNight, zone)
        val late = DemoData.plan(lateNight, zone)

        late.filter { it.bucket == DemoBucket.TODAY_UPCOMING }.forEach { task ->
            val minutes = task.draft.timeMinutes!!
            assertTrue(minutes in nowMinutes..(23 * 60 + 59), "late-night time out of range: $minutes")
        }
        // The overdue task is still strictly before now.
        val overdue = late.single { it.bucket == DemoBucket.TODAY_OVERDUE }
        assertTrue(overdue.draft.timeMinutes!! < nowMinutes)
    }
}
