package dev.aliziwa.todo.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateMathTest {
    private val utc = TimeZone.of("UTC")
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun todayUsesTheZone() {
        // 2026-01-01T02:30Z is still 2025-12-31 in New York.
        val millis = 1_767_234_600_000L // 2026-01-01T02:30:00Z
        assertEquals(LocalDate(2026, 1, 1), DateMath.today(millis, utc))
        assertEquals(LocalDate(2025, 12, 31), DateMath.today(millis, newYork))
    }

    @Test
    fun atMinutesRoundTrips() {
        val date = LocalDate(2026, 6, 15)
        val millis = DateMath.atMinutes(date, 9 * 60 + 30, newYork)
        assertEquals(9 * 60 + 30, DateMath.minutesOfDay(millis, newYork))
        assertEquals(date, DateMath.today(millis, newYork))
    }

    @Test
    fun dstSpringForwardDayIsShorter() {
        // US DST starts 2026-03-08: that local day is 23 hours long.
        val date = LocalDate(2026, 3, 8)
        val start = DateMath.startOfDay(date, newYork)
        val end = DateMath.endOfDayExclusive(date, newYork)
        assertEquals(23 * 60 * 60 * 1000L, end - start)
    }

    @Test
    fun dstFallBackDayIsLonger() {
        val date = LocalDate(2026, 11, 1)
        val start = DateMath.startOfDay(date, newYork)
        val end = DateMath.endOfDayExclusive(date, newYork)
        assertEquals(25 * 60 * 60 * 1000L, end - start)
    }

    @Test
    fun weekRangeIsMondayToSunday() {
        val (start, end) = DateMath.weekRange(LocalDate(2026, 10, 9)) // a Friday
        assertEquals(LocalDate(2026, 10, 5), start)
        assertEquals(LocalDate(2026, 10, 11), end)
    }

    @Test
    fun weekKeyIsStableInsideAWeekAndChangesAcross() {
        val monday = DateMath.weekKey(LocalDate(2026, 10, 5))
        val sunday = DateMath.weekKey(LocalDate(2026, 10, 11))
        val nextMonday = DateMath.weekKey(LocalDate(2026, 10, 12))
        assertEquals(monday, sunday)
        assertNotEquals(monday, nextMonday)
    }

    @Test
    fun monthRangeCoversWholeMonth() {
        val (start, end) = DateMath.monthRange(LocalDate(2026, 2, 17))
        assertEquals(LocalDate(2026, 2, 1), start)
        assertEquals(LocalDate(2026, 2, 28), end)
        assertEquals("2026-02", DateMath.monthKey(LocalDate(2026, 2, 17)))
    }

    @Test
    fun parseTimeOfDayHandlesCommonForms() {
        assertEquals(17 * 60, DateMath.parseTimeOfDay("17:00"))
        assertEquals(17 * 60, DateMath.parseTimeOfDay("5pm"))
        assertEquals(8 * 60 + 30, DateMath.parseTimeOfDay("8:30"))
        assertEquals(0, DateMath.parseTimeOfDay("12am"))
        assertEquals(12 * 60, DateMath.parseTimeOfDay("12pm"))
        assertNull(DateMath.parseTimeOfDay("not a time"))
        assertNull(DateMath.parseTimeOfDay("99:99"))
    }

    @Test
    fun parseDateRejectsGarbage() {
        assertEquals(LocalDate(2026, 10, 9), DateMath.parseDate("2026-10-09"))
        assertNull(DateMath.parseDate("tomorrow"))
        assertNull(DateMath.parseDate(null))
    }

    @Test
    fun unknownZoneFallsBackInsteadOfThrowing() {
        assertTrue(DateMath.zone("Not/AZone").id.isNotBlank())
    }
}
