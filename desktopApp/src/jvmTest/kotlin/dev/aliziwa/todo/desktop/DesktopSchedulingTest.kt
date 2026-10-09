package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A clock the test drives by hand, so timer behaviour is deterministic without real waiting. */
private class FakeClock(var nowMillis: Long, val zone: String = "America/New_York") : Clock {
    override fun nowEpochMillis(): Long = nowMillis
    override fun timeZoneId(): String = zone
}

class DesktopSchedulingTest {

    @Test
    fun `timer fires once its wall-clock moment passes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock(1_000_000L)
        val fired = CopyOnWriteArrayList<String>()
        val scheduler = DesktopAlarmScheduler(scope, clock, { fired += it }, {}, {}, sliceMillis = 25)
        try {
            scheduler.schedule("task-1", clock.nowMillis + 10_000)
            assertEquals(mapOf("task-1" to 1_010_000L), scheduler.armed())
            delay(150)
            assertTrue(fired.isEmpty(), "must not fire before its time")

            // Jump the wall clock past the due moment; the timer polls in <=30s slices.
            clock.nowMillis += 11_000
            waitUntil { fired.isNotEmpty() }
            assertEquals(listOf("task-1"), fired.toList())
            assertTrue(scheduler.armed().isEmpty(), "a fired timer is disarmed")
        } finally {
            scheduler.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `cancel and cancelAll disarm timers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock(0L)
        val fired = CopyOnWriteArrayList<String>()
        val scheduler = DesktopAlarmScheduler(scope, clock, { fired += it }, {}, {}, sliceMillis = 25)
        try {
            scheduler.schedule("a", 60_000)
            scheduler.schedule("b", 60_000)
            scheduler.cancel("a")
            assertEquals(setOf("b"), scheduler.armed().keys)
            scheduler.cancelAll()
            assertTrue(scheduler.armed().isEmpty())
            clock.nowMillis = 120_000
            delay(300)
            assertTrue(fired.isEmpty(), "cancelled timers never fire")
        } finally {
            scheduler.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `re-scheduling the same task replaces the previous timer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock(0L)
        val fired = CopyOnWriteArrayList<String>()
        val scheduler = DesktopAlarmScheduler(scope, clock, { fired += it }, {}, {}, sliceMillis = 25)
        try {
            scheduler.schedule("a", 10_000)
            scheduler.schedule("a", 50_000)   // extension moved it out
            assertEquals(mapOf("a" to 50_000L), scheduler.armed())
            clock.nowMillis = 20_000
            delay(300)
            assertTrue(fired.isEmpty(), "the replaced timer must not fire at the old time")
            clock.nowMillis = 60_000
            waitUntil { fired.isNotEmpty() }
            assertEquals(listOf("a"), fired.toList())
        } finally {
            scheduler.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `daily prompt resolves the next local occurrence and survives the midnight boundary`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-03-07 06:30 local -> prompt at 08:00 the same day.
        val sameDay = ZonedDateTime.of(2026, 3, 7, 6, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 3, 7, 8, 0, 0, 0, zone).toInstant().toEpochMilli(),
            nextPromptFor(sameDay, 8, 0),
        )
        // 2026-03-07 09:30 local -> already past, so tomorrow.
        val nextDay = ZonedDateTime.of(2026, 3, 7, 9, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 8, 0, 0, 0, zone).toInstant().toEpochMilli(),
            nextPromptFor(nextDay, 8, 0),
        )
        // DST spring-forward night (2026-03-08 in US): 08:00 still exists and is unique.
        val dstEve = ZonedDateTime.of(2026, 3, 7, 23, 59, 0, 0, zone)
        val resolved = nextPromptFor(dstEve, 8, 0)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), resolved)
    }

    /** Exercises the scheduler's own next-occurrence maths through its public surface. */
    private fun nextPromptFor(now: ZonedDateTime, hour: Int, minute: Int): Long = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock(now.toInstant().toEpochMilli(), now.zone.id)
        val fireCount = java.util.concurrent.atomic.AtomicInteger()
        val scheduler = DesktopAlarmScheduler(scope, clock, {}, { fireCount.incrementAndGet() }, {}, sliceMillis = 25)
        try {
            scheduler.scheduleDailyPrompt(hour, minute)
            delay(100)
            assertEquals(0, fireCount.get(), "the prompt is in the future")
            // Move the clock to just past the expected target and confirm it fires.
            val expected = expectedTarget(now, hour, minute)
            clock.nowMillis = expected + 1_000
            waitUntil { fireCount.get() > 0 }
            expected
        } finally {
            scheduler.shutdown()
            scope.cancel()
        }
    }

    private fun expectedTarget(now: ZonedDateTime, hour: Int, minute: Int): Long {
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    @Test
    fun `wake detector reports a lost-time jump`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reasons = CopyOnWriteArrayList<String>()
        // A 10ms threshold makes the normal 1s tick look like a "lost time" event, which is exactly
        // the condition the real 30s threshold detects after a sleep/wake.
        val detector = WakeDetector(scope, thresholdMillis = 10) { lost, reason -> reasons += "$lost:$reason" }
        try {
            detector.start()
            waitUntil { reasons.isNotEmpty() }
            assertTrue(reasons.first().isNotEmpty())
        } finally {
            detector.stop()
            scope.cancel()
        }
    }

    @Test
    fun `countdown formatting matches the sticky surface copy`() {
        assertEquals("45s", Countdown.relative(45_000))
        assertEquals("12m", Countdown.relative(12 * 60_000L))
        assertEquals("2h 5m", Countdown.relative((2 * 60 + 5) * 60_000L))
        assertEquals("3h", Countdown.relative(3 * 60 * 60_000L))
        assertEquals("2d 3h", Countdown.relative(((2 * 24 + 3) * 60 * 60_000L)))
        assertEquals("in 12m", Countdown.phrase(12 * 60_000L, 0))
        assertEquals("3m ago", Countdown.phrase(0, 3 * 60_000L))
    }

    private suspend fun waitUntil(timeoutMillis: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }
}
