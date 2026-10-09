package dev.aliziwa.todo.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun countdownBuckets() {
        assertEquals("due now", Format.countdown(0))
        assertEquals("in 45s", Format.countdown(45_000))
        assertEquals("in 12m", Format.countdown(12 * 60_000L))
        assertEquals("in 3h 12m", Format.countdown((3 * 60 + 12) * 60_000L))
        assertEquals("in 2d 3h", Format.countdown((2 * 24 * 60 + 3 * 60) * 60_000L))
    }

    @Test
    fun overdueReadsAsOverdue() {
        assertEquals("overdue by 3m", Format.countdown(-3 * 60_000L))
        assertEquals("overdue by 1d", Format.countdown(-24 * 60 * 60_000L))
    }

    @Test
    fun timeOfDayIsPadded() {
        assertEquals("08:05", Format.timeOfDay(8 * 60 + 5))
        assertEquals("00:00", Format.timeOfDay(0))
        assertEquals("23:59", Format.timeOfDay(23 * 60 + 59))
        assertEquals("00:30", Format.timeOfDay(24 * 60 + 30))
    }
}
