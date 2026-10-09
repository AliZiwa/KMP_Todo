package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.next
import kotlinx.datetime.plus
import kotlinx.datetime.until

/**
 * Deterministic, offline, zero-dependency parser. It is both the fallback when the LLM fails twice
 * and the engine behind RULES_ONLY mode, so the app is always usable.
 *
 * It produces the same [AssistantPlan] shape as the model, which means one downstream code path.
 */
class RuleBasedParser(private val zoneProvider: () -> TimeZone) {

    private val zone: TimeZone get() = zoneProvider()

    fun parse(input: String, nowMillis: Long): AssistantPlan {
        val today = DateMath.today(nowMillis, zone)
        val segments = splitSegments(input)
        val ops = segments.mapNotNull { parseSegment(it, nowMillis, today) }
        if (ops.isEmpty()) {
            return AssistantPlan(
                reply = "I could not work out what to do with that.",
                ops = listOf(
                    AssistantOp(
                        op = AssistantOps.CLARIFY,
                        question = "What would you like me to add, complete or remove?",
                    )
                ),
            )
        }
        return AssistantPlan(reply = replyFor(ops), ops = ops)
    }

    // -------------------------------------------------------------- segments

    private fun splitSegments(input: String): List<String> =
        input.split('\n', ';')
            .flatMap { line -> line.split(Regex("""\s+and\s+then\s+""", RegexOption.IGNORE_CASE)) }
            .map { it.trim().trim('-', '*', '.', ' ') }
            .filter { it.isNotBlank() }

    private fun parseSegment(raw: String, nowMillis: Long, today: LocalDate): AssistantOp? {
        val lower = raw.lowercase()

        listRegex.find(lower)?.let {
            return AssistantOp(op = AssistantOps.LIST, scope = scopeWord(lower))
        }

        extendRegex.find(lower)?.let { match ->
            val amount = match.groupValues[2].toIntOrNull() ?: 15
            val unit = match.groupValues[3]
            val minutes = if (unit.startsWith("h")) amount * 60 else amount
            val title = cleanTitle(raw, stripPhrases = listOf(match.value))
                .removePrefix("extend").removePrefix("snooze").trim()
            return AssistantOp(op = AssistantOps.EXTEND, title = title.ifBlank { null }, minutes = minutes)
        }
        if (snoozeRegex.containsMatchIn(lower)) {
            val title = cleanTitle(raw, stripPhrases = listOf("snooze", "postpone", "push back"))
            return AssistantOp(op = AssistantOps.EXTEND, title = title.ifBlank { null }, minutes = 15)
        }

        completeRegex.find(lower)?.let { match ->
            val title = cleanTitle(raw, stripPhrases = listOf(match.value, "mark", "as done", "is done"))
            if (title.isNotBlank()) return AssistantOp(op = AssistantOps.COMPLETE, title = title)
        }

        deleteRegex.find(lower)?.let { match ->
            val title = cleanTitle(raw, stripPhrases = listOf(match.value))
            if (title.isNotBlank()) return AssistantOp(op = AssistantOps.DELETE, title = title)
        }

        return parseAdd(raw, nowMillis, today)
    }

    // ------------------------------------------------------------------- add

    private fun parseAdd(raw: String, nowMillis: Long, today: LocalDate): AssistantOp? {
        val lower = raw.lowercase()
        val strip = mutableListOf<String>()

        val priority = when {
            highRegex.containsMatchIn(lower) -> "high".also { highRegex.find(lower)?.let { m -> strip += m.value } }
            lowRegex.containsMatchIn(lower) -> "low".also { lowRegex.find(lower)?.let { m -> strip += m.value } }
            else -> null
        }

        val recurring = recurringRegex.find(lower)?.also { strip += it.value } != null

        var date: LocalDate? = null
        var time: Int? = null

        relativeRegex.find(lower)?.let { match ->
            strip += match.value
            val amount = match.groupValues[1].toIntOrNull() ?: 0
            val unit = match.groupValues[2]
            val deltaMinutes = when {
                unit.startsWith("h") -> amount * 60
                unit.startsWith("d") -> amount * 60 * 24
                else -> amount
            }
            val target = nowMillis + deltaMinutes * 60_000L
            date = DateMath.today(target, zone)
            time = DateMath.minutesOfDay(target, zone)
        }

        if (date == null) {
            isoDateRegex.find(lower)?.let { match ->
                DateMath.parseDate(match.value)?.let { parsed ->
                    date = parsed
                    strip += match.value
                }
            }
        }
        if (date == null) {
            inDaysRegex.find(lower)?.let { match ->
                strip += match.value
                val days = match.groupValues[1].toIntOrNull() ?: 0
                date = today.plus(days, DateTimeUnit.DAY)
            }
        }
        if (date == null) {
            when {
                tomorrowRegex.containsMatchIn(lower) -> {
                    tomorrowRegex.find(lower)?.let { strip += it.value }
                    date = today.plus(1, DateTimeUnit.DAY)
                }
                todayRegex.containsMatchIn(lower) -> {
                    todayRegex.find(lower)?.let { strip += it.value }
                    date = today
                }
                tonightRegex.containsMatchIn(lower) -> {
                    tonightRegex.find(lower)?.let { strip += it.value }
                    date = today
                    if (time == null) time = 20 * 60
                }
            }
        }
        if (date == null) {
            weekdayRegex.find(lower)?.let { match ->
                strip += match.value
                val day = weekdayOf(match.groupValues[2]) ?: return@let
                val next = if (match.groupValues[1].contains("next")) {
                    today.plus(1, DateTimeUnit.DAY).next(day)
                } else {
                    today.next(day)
                }
                date = next
            }
        }

        if (time == null) {
            atTimeRegex.find(lower)?.let { match ->
                strip += match.value
                time = resolveTime(match.groupValues[1], nowMillis, date, today)
            }
        }
        if (time == null) {
            bareTimeRegex.find(lower)?.let { match ->
                strip += match.value
                time = resolveTime(match.value, nowMillis, date, today)
            }
        }

        val scope = when {
            recurring -> "recurring_daily"
            nextWeekRegex.containsMatchIn(lower) -> "week".also { nextWeekRegex.find(lower)?.let { m -> strip += m.value } }
            thisWeekRegex.containsMatchIn(lower) -> "week".also { thisWeekRegex.find(lower)?.let { m -> strip += m.value } }
            monthRegex.containsMatchIn(lower) -> "month".also { monthRegex.find(lower)?.let { m -> strip += m.value } }
            else -> "day"
        }
        if (scope == "week" && nextWeekRegex.containsMatchIn(lower) && date == null) {
            date = today.plus(1, DateTimeUnit.DAY).next(DayOfWeek.MONDAY)
        }
        if (scope == "month" && monthRegex.find(lower)?.value?.contains("next") == true && date == null) {
            date = today.plus(1, DateTimeUnit.MONTH)
        }

        if (scope == "day" && date == null) date = today

        val title = cleanTitle(raw, strip)
        if (!meaningfulRegex.containsMatchIn(title)) return null

        return AssistantOp(
            op = AssistantOps.ADD,
            title = title,
            priority = priority,
            scope = scope,
            date = date?.toString(),
            time = time?.let { minutes -> "${pad(minutes / 60)}:${pad(minutes % 60)}" },
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

    private fun cleanTitle(raw: String, stripPhrases: List<String>): String {
        var text = raw
        stripPhrases.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { phrase ->
            text = text.replace(phrase, " ", ignoreCase = true)
        }
        text = leadInRegex.replace(text, " ")
        text = text.replace(Regex("""\s+"""), " ").trim()
        text = text.trim(' ', ',', '.', ':', ';', '-', '"', '\'')
        return text
    }

    private fun replyFor(ops: List<AssistantOp>): String {
        val adds = ops.count { it.op == AssistantOps.ADD }
        val others = ops.size - adds
        return when {
            adds > 0 && others == 0 && adds == 1 -> "Add this task?"
            adds > 1 && others == 0 -> "Add these $adds tasks?"
            ops.size == 1 -> when (ops.first().op) {
                AssistantOps.COMPLETE -> "Mark it done?"
                AssistantOps.DELETE -> "Delete it?"
                AssistantOps.EXTEND -> "Push it back?"
                AssistantOps.LIST -> "Here is what you have."
                else -> "Apply this?"
            }
            else -> "Apply these ${ops.size} changes?"
        }
    }

    private fun scopeWord(lower: String): String? = when {
        thisWeekRegex.containsMatchIn(lower) || nextWeekRegex.containsMatchIn(lower) -> "week"
        monthRegex.containsMatchIn(lower) -> "month"
        recurringRegex.containsMatchIn(lower) -> "recurring_daily"
        else -> null
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

    private fun pad(value: Int) = if (value < 10) "0$value" else value.toString()

    private companion object {
        val listRegex = Regex("""^\s*(list|show|what('?s| is)? (on|due|left|next)|my tasks|agenda)\b""", RegexOption.IGNORE_CASE)
        val extendRegex = Regex("""\b(extend|snooze|postpone|push back|delay)\b[^0-9]*(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE)
        val snoozeRegex = Regex("""\b(snooze|postpone|push back)\b""", RegexOption.IGNORE_CASE)
        val completeRegex = Regex("""^\s*(mark\s+)?(complete|completed|done with|done|finish|finished|tick off|check off)\b""", RegexOption.IGNORE_CASE)
        val deleteRegex = Regex("""^\s*(delete|remove|cancel|drop|forget)\b""", RegexOption.IGNORE_CASE)
        val highRegex = Regex("""\b(high priority|urgent|important|asap|critical)\b""", RegexOption.IGNORE_CASE)
        val lowRegex = Regex("""\b(low priority|whenever|someday|no rush)\b""", RegexOption.IGNORE_CASE)
        val recurringRegex = Regex("""\b(every ?day|everyday|daily|each day|every morning|every evening)\b""", RegexOption.IGNORE_CASE)
        val relativeRegex = Regex("""\bin\s+(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d)\b""", RegexOption.IGNORE_CASE)
        val inDaysRegex = Regex("""\bin\s+(\d+)\s+days?\b""", RegexOption.IGNORE_CASE)
        val isoDateRegex = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
        val tomorrowRegex = Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE)
        val todayRegex = Regex("""\btoday\b""", RegexOption.IGNORE_CASE)
        val tonightRegex = Regex("""\btonight\b""", RegexOption.IGNORE_CASE)
        val weekdayRegex = Regex("""\b(next\s+|this\s+|on\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""", RegexOption.IGNORE_CASE)
        val atTimeRegex = Regex("""\bat\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\b""", RegexOption.IGNORE_CASE)
        val bareTimeRegex = Regex("""\b\d{1,2}:\d{2}\s*(?:am|pm)?\b|\b\d{1,2}\s*(?:am|pm)\b""", RegexOption.IGNORE_CASE)
        val thisWeekRegex = Regex("""\bthis week\b""", RegexOption.IGNORE_CASE)
        val nextWeekRegex = Regex("""\bnext week\b""", RegexOption.IGNORE_CASE)
        val monthRegex = Regex("""\b(this|next) month\b""", RegexOption.IGNORE_CASE)
        val twoDigitHourRegex = Regex("""^\s*\d{2}:""")
        val meaningfulRegex = Regex("""[\p{L}\p{N}]""")
        val leadInRegex = Regex("""^\s*(please\s+)?(add|create|new task|make|remind me to|remind me|i need to|i have to|i must|need to|todo|to-do|task)\b[:\s]*""", RegexOption.IGNORE_CASE)
    }
}
