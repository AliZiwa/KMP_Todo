package dev.aliziwa.todo

import dev.aliziwa.todo.platform.SystemClock
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun clockWorks() {
        assertTrue(SystemClock.nowEpochMillis() > 0)
        assertTrue(SystemClock.timeZoneId().isNotBlank())
    }
}
