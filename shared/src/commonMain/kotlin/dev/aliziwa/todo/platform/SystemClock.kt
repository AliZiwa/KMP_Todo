package dev.aliziwa.todo.platform

import kotlinx.datetime.TimeZone
import kotlin.time.Clock as KClock

/** Default clock usable on every platform. */
object SystemClock : Clock {
    override fun nowEpochMillis(): Long = KClock.System.now().toEpochMilliseconds()
    override fun timeZoneId(): String = TimeZone.currentSystemDefault().id
}
