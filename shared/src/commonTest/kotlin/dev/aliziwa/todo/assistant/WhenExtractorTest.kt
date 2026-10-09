package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhenExtractorTest {
    private val zone = TimeZone.of("UTC")
    private val extractor = WhenExtractor { zone }

    // 2026-10-09 is a Friday, 10:00 UTC.
    private val today = LocalDate(2026, 10, 9)
    private val tomorrow = LocalDate(2026, 10, 10)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)

    private fun extract(text: String) = extractor.extract(text, now)

    @Test
    fun todayKeyword() {
        val match = extract("buy milk today at 3pm")
        assertEquals(today, match.date)
        assertEquals("today", match.datePhrase)
        assertEquals(15 * 60, match.timeMinutes)
        assertEquals("3pm", match.timePhrase)
    }

    @Test
    fun tomorrowKeyword() {
        val match = extract("gym tomorrow at 7am")
        assertEquals(tomorrow, match.date)
        assertEquals(7 * 60, match.timeMinutes)
    }

    @Test
    fun dayPartsImplyTodayAndATime() {
        assertEquals(today to 20 * 60, extract("call mum tonight").let { it.date to it.timeMinutes })
        assertEquals(today to 9 * 60, extract("stretch this morning").let { it.date to it.timeMinutes })
        assertEquals(today to 15 * 60, extract("walk this afternoon").let { it.date to it.timeMinutes })
        assertEquals(today to 20 * 60, extract("read this evening").let { it.date to it.timeMinutes })
    }

    @Test
    fun anExplicitTimeBeatsTheDayPartDefault() {
        val match = extract("call mum tonight at 21:30")
        assertEquals(today, match.date)
        assertEquals(21 * 60 + 30, match.timeMinutes)
    }

    @Test
    fun weekdayNamesResolveForward() {
        // Today is Friday 2026-10-09, so both forms mean the coming Monday.
        assertEquals(LocalDate(2026, 10, 12), extract("haircut on monday").date)
        assertEquals(LocalDate(2026, 10, 12), extract("haircut next monday").date)
        // ...and asking on the day itself skips to the following week.
        val onMonday = DateMath.atMinutes(LocalDate(2026, 10, 12), 10 * 60, zone)
        assertEquals(LocalDate(2026, 10, 19), extractor.extract("haircut next monday", onMonday).date)
    }

    @Test
    fun isoDatesAndRelativeOffsets() {
        assertEquals(LocalDate(2026, 12, 24), extract("wrap presents 2026-12-24").date)
        val relative = extract("stretch in 30 min")
        assertEquals(today, relative.date)
        assertEquals(10 * 60 + 30, relative.timeMinutes)
        assertEquals(LocalDate(2026, 10, 12), extract("review in 3 days").date)
    }

    @Test
    fun bareHoursPickTheNextUpcomingOccurrence() {
        assertEquals(17 * 60, extract("meeting at 5").timeMinutes)
        assertEquals(11 * 60, extract("meeting at 11").timeMinutes)
    }

    @Test
    fun twoDigitHoursAreTakenAs24Hour() {
        assertEquals(6 * 60 + 30, extract("meditate at 06:30").timeMinutes)
    }

    @Test
    fun textWithNoTemporalWordsYieldsNothing() {
        val match = extract("buy milk")
        assertNull(match.date)
        assertNull(match.timeMinutes)
        assertTrue(match.consumed.isEmpty())
        assertTrue(!match.hasDate && !match.hasTime)
    }

    @Test
    fun matchedPhrasesAreReportedSoTitlesCanBeCleaned() {
        val match = extract("gym tomorrow at 7am")
        assertTrue(match.consumed.any { it.contains("tomorrow") })
        assertTrue(match.consumed.any { it.contains("7am") })
    }

    @Test
    fun blankTextIsSafe() {
        assertEquals(WhenMatch.NONE, extractor.extract("   ", now))
    }
}
