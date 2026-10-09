package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.next
import kotlinx.datetime.plus

/**
 * What the user's own words pin down about *when*, plus the phrases that said so.
 *
 * [consumed] is every matched phrase, so a caller building a title can strip them out.
 */
data class WhenMatch(
    val date: LocalDate? = null,
    /** The literal phrase that fixed [date], e.g. "today", "next monday". */
    val datePhrase: String? = null,
    val timeMinutes: Int? = null,
    /** The literal phrase that fixed [timeMinutes], e.g. "3pm", "06:30". */
    val timePhrase: String? = null,
    val consumed: List<String> = emptyList(),
) {
    val hasDate: Boolean get() = date != null
    val hasTime: Boolean get() = timeMinutes != null

    companion object {
        val NONE = WhenMatch()
    }
}

/**
 * THE single source of truth for reading dates and times out of free text.
 *
 * Used by [RuleBasedParser] to build ops offline, and by [PlanResolver] to overrule an LLM that
 * disagrees with an explicit keyword the user typed (small models routinely put "today at 3pm" on
 * tomorrow).
 */
class WhenExtractor(private val zoneProvider: () -> TimeZone) {

    private val zone: TimeZone get() = zoneProvider()

    fun extract(text: String, nowMillis: Long): WhenMatch {
        if (text.isBlank()) return WhenMatch.NONE
        val lower = text.lowercase()
        val today = DateMath.today(nowMillis, zone)
        val consumed = mutableListOf<String>()

        var date: LocalDate? = null
        var datePhrase: String? = null
        var time: Int? = null
        var timePhrase: String? = null
        // A day-part default ("tonight" -> 20:00) is a guess; an explicit clock time must beat it.
        var timeIsDayPartDefault = false

        // "in 30 min" / "in 2 hours" / "in 3 days" fixes both the day and the clock time.
        relativeRegex.find(lower)?.let { match ->
            consumed += match.value
            val amount = match.groupValues[1].toIntOrNull() ?: 0
            val unit = match.groupValues[2]
            val deltaMinutes = when {
                unit.startsWith("h") -> amount * 60
                unit.startsWith("d") -> amount * 60 * 24
                else -> amount
            }
            val target = nowMillis + deltaMinutes * 60_000L
            date = DateMath.today(target, zone)
            datePhrase = match.value
            time = DateMath.minutesOfDay(target, zone)
            timePhrase = match.value
        }

        if (date == null) {
            isoDateRegex.find(lower)?.let { match ->
                DateMath.parseDate(match.value)?.let { parsed ->
                    consumed += match.value
                    date = parsed
                    datePhrase = match.value
                }
            }
        }
        if (date == null) {
            inDaysRegex.find(lower)?.let { match ->
                consumed += match.value
                date = today.plus(match.groupValues[1].toIntOrNull() ?: 0, DateTimeUnit.DAY)
                datePhrase = match.value
            }
        }
        // "tonight" / "this morning" / "this afternoon" / "this evening" imply today *and* a time.
        if (date == null) {
            dayPartRegex.find(lower)?.let { match ->
                consumed += match.value
                date = today
                datePhrase = match.value
                if (time == null) {
                    time = dayPartMinutes(match.value)
                    timePhrase = match.value
                    timeIsDayPartDefault = true
                }
            }
        }
        if (date == null) {
            tomorrowRegex.find(lower)?.let { match ->
                consumed += match.value
                date = today.plus(1, DateTimeUnit.DAY)
                datePhrase = match.value
            }
        }
        if (date == null) {
            todayRegex.find(lower)?.let { match ->
                consumed += match.value
                date = today
                datePhrase = match.value
            }
        }
        if (date == null) {
            weekdayRegex.find(lower)?.let { match ->
                weekdayOf(match.groupValues[2])?.let { day ->
                    consumed += match.value
                    date = if (match.groupValues[1].contains("next")) {
                        today.plus(1, DateTimeUnit.DAY).next(day)
                    } else {
                        today.next(day)
                    }
                    datePhrase = match.value
                }
            }
        }

        if (time == null || timeIsDayPartDefault) {
            atTimeRegex.find(lower)?.let { match ->
                resolveTime(match.groupValues[1], nowMillis, date, today)?.let { resolved ->
                    consumed += match.value
                    time = resolved
                    timePhrase = match.groupValues[1].trim()
                    timeIsDayPartDefault = false
                }
            }
        }
        if (time == null || timeIsDayPartDefault) {
            bareTimeRegex.find(lower)?.let { match ->
                resolveTime(match.value, nowMillis, date, today)?.let { resolved ->
                    consumed += match.value
                    time = resolved
                    timePhrase = match.value.trim()
                    timeIsDayPartDefault = false
                }
            }
        }

        return WhenMatch(
            date = date,
            datePhrase = datePhrase,
            timeMinutes = time,
            timePhrase = timePhrase,
            consumed = consumed,
        )
    }

    /** "at 5" with no am/pm: pick whichever of 5am / 5pm comes next. */
    private fun resolveTime(raw: String, nowMillis: Long, date: LocalDate?, today: LocalDate): Int? {
        val text = raw.trim()
        val explicit = text.contains("am") || text.contains("pm")
        val parsed = DateMath.parseTimeOfDay(text) ?: return null
        if (explicit || parsed >= 13 * 60) return parsed
        // A two-digit hour written with a colon ("06:30", "09:00") is an unambiguous 24-hour time.
        if (text.contains(':') && twoDigitHourRegex.containsMatchIn(text)) return parsed
        val isToday = date == null || date == today
        if (!isToday) return if (parsed < 7 * 60) parsed + 12 * 60 else parsed
        val nowMinutes = DateMath.minutesOfDay(nowMillis, zone)
        val alternative = (parsed + 12 * 60) % (24 * 60)
        return when {
            parsed > nowMinutes -> parsed
            alternative > nowMinutes -> alternative
            else -> parsed
        }
    }

    private fun dayPartMinutes(phrase: String): Int = when {
        phrase.contains("morning") -> 9 * 60
        phrase.contains("afternoon") -> 15 * 60
        else -> 20 * 60 // tonight / this evening
    }

    private fun weekdayOf(word: String): DayOfWeek? = when (word.take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    internal companion object {
        val relativeRegex = Regex("""\bin\s+(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d)\b""", RegexOption.IGNORE_CASE)
        val inDaysRegex = Regex("""\bin\s+(\d+)\s+days?\b""", RegexOption.IGNORE_CASE)
        val isoDateRegex = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
        val dayPartRegex = Regex("""\b(tonight|this morning|this afternoon|this evening)\b""", RegexOption.IGNORE_CASE)
        val tomorrowRegex = Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE)
        val todayRegex = Regex("""\btoday\b""", RegexOption.IGNORE_CASE)
        val weekdayRegex = Regex("""\b(next\s+|this\s+|on\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""", RegexOption.IGNORE_CASE)
        val atTimeRegex = Regex("""\bat\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\b""", RegexOption.IGNORE_CASE)
        val bareTimeRegex = Regex("""\b\d{1,2}:\d{2}\s*(?:am|pm)?\b|\b\d{1,2}\s*(?:am|pm)\b""", RegexOption.IGNORE_CASE)
        val twoDigitHourRegex = Regex("""^\s*\d{2}:""")
    }
}
