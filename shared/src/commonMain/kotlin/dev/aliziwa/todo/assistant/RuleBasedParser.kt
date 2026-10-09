package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.next
import kotlinx.datetime.plus

/**
 * Deterministic, offline, zero-dependency parser. It is both the fallback when the LLM fails twice
 * and the engine behind RULES_ONLY mode, so the app is always usable.
 *
 * It produces the same [AssistantPlan] shape as the model, which means one downstream code path.
 */
class RuleBasedParser(private val zoneProvider: () -> TimeZone) {

    private val zone: TimeZone get() = zoneProvider()

    /** Date/time reading is shared with [PlanResolver] — one source of truth. */
    private val whenExtractor = WhenExtractor(zoneProvider)

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

        val whenMatch = whenExtractor.extract(raw, nowMillis)
        strip += whenMatch.consumed
        var date = whenMatch.date
        val time = whenMatch.timeMinutes

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
            time = time?.let { Format.timeOfDay(it) },
        )
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

    private companion object {
        val listRegex = Regex("""^\s*(list|show|what('?s| is)? (on|due|left|next)|my tasks|agenda)\b""", RegexOption.IGNORE_CASE)
        val extendRegex = Regex("""\b(extend|snooze|postpone|push back|delay)\b[^0-9]*(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE)
        val snoozeRegex = Regex("""\b(snooze|postpone|push back)\b""", RegexOption.IGNORE_CASE)
        val completeRegex = Regex("""^\s*(mark\s+)?(complete|completed|done with|done|finish|finished|tick off|check off)\b""", RegexOption.IGNORE_CASE)
        val deleteRegex = Regex("""^\s*(delete|remove|cancel|drop|forget)\b""", RegexOption.IGNORE_CASE)
        val highRegex = Regex("""\b(high priority|urgent|important|asap|critical)\b""", RegexOption.IGNORE_CASE)
        val lowRegex = Regex("""\b(low priority|whenever|someday|no rush)\b""", RegexOption.IGNORE_CASE)
        val recurringRegex = Regex("""\b(every ?day|everyday|daily|each day|every morning|every evening)\b""", RegexOption.IGNORE_CASE)
        val thisWeekRegex = Regex("""\bthis week\b""", RegexOption.IGNORE_CASE)
        val nextWeekRegex = Regex("""\bnext week\b""", RegexOption.IGNORE_CASE)
        val monthRegex = Regex("""\b(this|next) month\b""", RegexOption.IGNORE_CASE)
        val meaningfulRegex = Regex("""[\p{L}\p{N}]""")
        val leadInRegex = Regex("""^\s*(please\s+)?(add|create|new task|make|remind me to|remind me|i need to|i have to|i must|need to|todo|to-do|task)\b[:\s]*""", RegexOption.IGNORE_CASE)
    }
}
