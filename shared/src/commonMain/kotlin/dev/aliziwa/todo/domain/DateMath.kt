package dev.aliziwa.todo.domain

import dev.aliziwa.todo.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.nextOrSame
import kotlinx.datetime.plus
import kotlinx.datetime.previousOrSame
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toLocalIsoWeekDate
import kotlin.time.Instant

/**
 * All date/time math lives here. Times are stored as epoch millis (UTC); every rendering or
 * "which day does this belong to" decision goes through a [TimeZone] so DST and timezone changes
 * are handled by kotlinx-datetime rather than by us.
 */
object DateMath {

    fun zone(zoneId: String): TimeZone =
        runCatching { TimeZone.of(zoneId) }.getOrElse { TimeZone.currentSystemDefault() }

    fun today(nowMillis: Long, zone: TimeZone): LocalDate = dateTimeAt(nowMillis, zone).date

    fun dateTimeAt(millis: Long, zone: TimeZone): LocalDateTime =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)

    fun startOfDay(date: LocalDate, zone: TimeZone): Long =
        date.atStartOfDayIn(zone).toEpochMilliseconds()

    /** Exclusive end of the local day (handles 23h/25h DST days). */
    fun endOfDayExclusive(date: LocalDate, zone: TimeZone): Long =
        startOfDay(date.plus(1, DateTimeUnit.DAY), zone)

    /** Absolute instant for a local date + minutes-since-midnight. Gaps/overlaps resolved by the library. */
    fun atMinutes(date: LocalDate, minutesOfDay: Int, zone: TimeZone): Long {
        val clamped = ((minutesOfDay % 1440) + 1440) % 1440
        return date.atTime(clamped / 60, clamped % 60).toInstant(zone).toEpochMilliseconds()
    }

    fun minutesOfDay(millis: Long, zone: TimeZone): Int {
        val ldt = dateTimeAt(millis, zone)
        return ldt.hour * 60 + ldt.minute
    }

    /** Monday .. Sunday of the ISO week containing [date]. */
    fun weekRange(date: LocalDate): Pair<LocalDate, LocalDate> =
        date.previousOrSame(DayOfWeek.MONDAY) to date.nextOrSame(DayOfWeek.SUNDAY)

    /** First .. last day of the calendar month containing [date]. */
    fun monthRange(date: LocalDate): Pair<LocalDate, LocalDate> {
        val first = LocalDate(date.year, date.month, 1)
        val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        return first to last
    }

    /** "2026-W41" — identical for every date inside the same ISO week. */
    fun weekKey(date: LocalDate): String {
        val iso = date.toLocalIsoWeekDate()
        return "${iso.isoWeekYear}-W${Format.pad2(iso.isoWeekNumber)}"
    }

    /** "2026-10" */
    fun monthKey(date: LocalDate): String = "${date.year}-${Format.pad2(date.month.ordinal + 1)}"

    fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
    }

    fun format(date: LocalDate): String = date.toString()

    /** "HH:mm" -> minutes since midnight. Accepts "8", "8:30", "08:30", "8pm", "8:30 PM". */
    fun parseTimeOfDay(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().lowercase()
        val pm = text.endsWith("pm")
        val am = text.endsWith("am")
        val core = text.removeSuffix("pm").removeSuffix("am").trim()
        val parts = core.split(":", ".")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.trim()?.take(2)?.toIntOrNull() ?: 0
        if (minute !in 0..59) return null
        var h = hour
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h !in 0..23) return null
        return h * 60 + minute
    }

    /** "Tue 14 Oct" style short label. */
    fun shortLabel(date: LocalDate): String =
        "${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${date.day} " +
            date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

    fun isSameDay(millis: Long, date: LocalDate, zone: TimeZone): Boolean =
        dateTimeAt(millis, zone).date == date
}
