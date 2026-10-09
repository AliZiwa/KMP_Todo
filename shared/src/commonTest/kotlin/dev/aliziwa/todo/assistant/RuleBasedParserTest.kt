package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleBasedParserTest {
    private val zone = TimeZone.of("UTC")
    private val parser = RuleBasedParser { zone }

    // 2026-10-09 is a Friday; 10:00 UTC.
    private val today = LocalDate(2026, 10, 9)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)

    private fun single(text: String): AssistantOp {
        val plan = parser.parse(text, now)
        assertTrue(plan.ops.isNotEmpty(), "no ops for: $text")
        return plan.ops.first()
    }

    @Test
    fun plainTaskBecomesAnAdd() {
        val op = single("buy milk")
        assertEquals(AssistantOps.ADD, op.op)
        assertEquals("buy milk", op.title)
        assertEquals("day", op.scope)
        assertEquals(today.toString(), op.date)
    }

    @Test
    fun stripsLeadInVerbs() {
        assertEquals("call the dentist", single("remind me to call the dentist").title)
        assertEquals("pay rent", single("add pay rent").title)
        assertEquals("water plants", single("i need to water plants").title)
    }

    @Test
    fun tomorrowAndExplicitTime() {
        val op = single("gym tomorrow at 7am")
        assertEquals("2026-10-10", op.date)
        assertEquals("07:00", op.time)
        assertEquals("gym", op.title)
    }

    @Test
    fun relativeMinutesResolveToAClockTime() {
        val op = single("stretch in 30 min")
        assertEquals("10:30", op.time)
        assertEquals(today.toString(), op.date)
        assertEquals("stretch", op.title)
    }

    @Test
    fun relativeHoursCanRollOverIntoTheEvening() {
        val op = single("standup in 2 hours")
        assertEquals("12:00", op.time)
    }

    @Test
    fun bareHourPicksTheNextUpcomingOccurrence() {
        // now is 10:00, so "at 5" means 17:00 today, not 05:00.
        assertEquals("17:00", single("meeting at 5").time)
        // 11 is still ahead of 10:00 today
        assertEquals("11:00", single("meeting at 11").time)
    }

    @Test
    fun everydayBecomesRecurring() {
        val op = single("meditate every day at 06:30")
        assertEquals("recurring_daily", op.scope)
        assertEquals("06:30", op.time)
        assertEquals("meditate", op.title)
    }

    @Test
    fun priorityWordsAreRecognisedAndStripped() {
        val high = single("file taxes high priority")
        assertEquals("high", high.priority)
        assertEquals("file taxes", high.title)
        assertEquals("low", single("sort photos low priority").priority)
    }

    @Test
    fun weekAndMonthScopes() {
        assertEquals("week", single("clean garage this week").scope)
        assertEquals("month", single("renew passport next month").scope)
    }

    @Test
    fun weekdayNamesResolveForward() {
        val op = single("haircut on monday")
        assertEquals("2026-10-12", op.date)
    }

    @Test
    fun isoDatesAreUsedVerbatim() {
        assertEquals("2026-12-24", single("wrap presents 2026-12-24").date)
    }

    @Test
    fun completeIntent() {
        val op = single("done with laundry")
        assertEquals(AssistantOps.COMPLETE, op.op)
        assertEquals("laundry", op.title)
    }

    @Test
    fun deleteIntent() {
        val op = single("delete buy milk")
        assertEquals(AssistantOps.DELETE, op.op)
        assertEquals("buy milk", op.title)
    }

    @Test
    fun extendIntentWithUnits() {
        val op = single("extend laundry by 20 minutes")
        assertEquals(AssistantOps.EXTEND, op.op)
        assertEquals(20, op.minutes)
        val hours = single("push back standup by 2 hours")
        assertEquals(120, hours.minutes)
    }

    @Test
    fun listIntent() {
        assertEquals(AssistantOps.LIST, single("what's due today").op)
        assertEquals(AssistantOps.LIST, single("list my tasks").op)
    }

    @Test
    fun severalTasksInOneMessage() {
        val plan = parser.parse("buy milk\nwalk dog at 18:00", now)
        assertEquals(2, plan.ops.size)
        assertEquals("buy milk", plan.ops[0].title)
        assertEquals("18:00", plan.ops[1].time)
    }

    @Test
    fun gibberishAsksForClarification() {
        val plan = parser.parse("???", now)
        assertEquals(AssistantOps.CLARIFY, plan.ops.single().op)
        assertNotNull(plan.ops.single().question)
    }
}
