package dev.aliziwa.todo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every rule the time field's free-text entry promises. */
class TimeTextTest {

    @Test
    fun parses_12_hour_text() {
        assertEquals(15 * 60, TimeText.parse("3pm"))
        assertEquals(15 * 60, TimeText.parse("3 PM"))
        assertEquals(15 * 60, TimeText.parse("3p.m."))
        assertEquals(15 * 60 + 45, TimeText.parse("3:45 pm"))
        assertEquals(7 * 60, TimeText.parse("7am"))
        assertEquals(0, TimeText.parse("12am"))
        assertEquals(12 * 60, TimeText.parse("12pm"))
        assertEquals(12 * 60 + 30, TimeText.parse("12:30pm"))
    }

    @Test
    fun parses_24_hour_text() {
        assertEquals(15 * 60 + 30, TimeText.parse("15:30"))
        assertEquals(0, TimeText.parse("00:00"))
        assertEquals(23 * 60 + 59, TimeText.parse("23:59"))
        assertEquals(9 * 60, TimeText.parse("9"))
        assertEquals(9 * 60 + 5, TimeText.parse("09:05"))
    }

    @Test
    fun parses_dot_and_h_separators() {
        assertEquals(7 * 60 + 45, TimeText.parse("7.45"))
        assertEquals(7 * 60 + 45, TimeText.parse("7h45"))
        assertEquals(19 * 60 + 45, TimeText.parse("7.45pm"))
        assertEquals(8 * 60 + 15, TimeText.parse("8 15"))
    }

    @Test
    fun parses_compact_digits() {
        assertEquals(9 * 60 + 30, TimeText.parse("930"))
        assertEquals(9 * 60 + 30, TimeText.parse("0930"))
        assertEquals(23 * 60 + 15, TimeText.parse("2315"))
    }

    @Test
    fun parses_words_and_noise() {
        assertEquals(12 * 60, TimeText.parse("noon"))
        assertEquals(12 * 60, TimeText.parse("Midday"))
        assertEquals(0, TimeText.parse("midnight"))
        assertEquals(18 * 60, TimeText.parse("evening"))
        assertEquals(17 * 60, TimeText.parse("at 17"))
        assertEquals(5 * 60, TimeText.parse("5 o'clock"))
    }

    @Test
    fun rejects_nonsense() {
        assertNull(TimeText.parse(null))
        assertNull(TimeText.parse(""))
        assertNull(TimeText.parse("   "))
        assertNull(TimeText.parse("abc"))
        assertNull(TimeText.parse("25:00"))
        assertNull(TimeText.parse("12:60"))
        assertNull(TimeText.parse("13pm"))
        assertNull(TimeText.parse("1:2:3"))
        assertNull(TimeText.parse("99999"))
    }

    @Test
    fun formats_both_clocks() {
        assertEquals("15:30", TimeText.format(15 * 60 + 30, use24Hour = true))
        assertEquals("3:30 PM", TimeText.format(15 * 60 + 30, use24Hour = false))
        assertEquals("12:00 AM", TimeText.format(0, use24Hour = false))
        assertEquals("12:05 PM", TimeText.format(12 * 60 + 5, use24Hour = false))
        assertEquals("No time", TimeText.formatOrNone(null))
        assertEquals("3pm", TimeText.formatCompact(15 * 60))
        assertEquals("3:05pm", TimeText.formatCompact(15 * 60 + 5))
        assertEquals("15:05", TimeText.formatCompact(15 * 60 + 5, use24Hour = true))
    }

    @Test
    fun round_trips_through_wheel_parts() {
        for (minutes in 0 until TimeText.MINUTES_PER_DAY step 7) {
            val hour24 = minutes / 60
            val wheelHour12 = TimeText.displayHour12(hour24)
            val rebuilt12 = TimeText.fromParts(
                wheelHour = wheelHour12,
                minute = minutes % 60,
                use24Hour = false,
                pm = TimeText.isPm(minutes),
            )
            assertEquals(minutes, rebuilt12, "12h round trip failed for $minutes")
            val rebuilt24 = TimeText.fromParts(hour24, minutes % 60, use24Hour = true, pm = false)
            assertEquals(minutes, rebuilt24, "24h round trip failed for $minutes")
        }
    }

    @Test
    fun shifting_and_rounding_wrap_around_midnight() {
        assertEquals(0, TimeText.shift(23 * 60 + 45, 15))
        assertEquals(23 * 60 + 45, TimeText.shift(0, -15))
        assertEquals(9 * 60 + 15, TimeText.roundUpTo(9 * 60 + 1, 15))
        assertEquals(9 * 60, TimeText.roundUpTo(9 * 60, 15))
        assertEquals(0, TimeText.roundUpTo(23 * 60 + 50, 15))
    }

    @Test
    fun wheel_values_cover_the_clock() {
        assertEquals(24, TimeText.hourWheelValues(use24Hour = true).size)
        val twelve = TimeText.hourWheelValues(use24Hour = false)
        assertEquals(12, twelve.size)
        assertEquals(12, twelve.first())
        assertTrue(twelve.containsAll((1..11).toList()))
        assertEquals("09", TimeText.hourWheelLabel(9, use24Hour = true))
        assertEquals("9", TimeText.hourWheelLabel(9, use24Hour = false))
    }
}
