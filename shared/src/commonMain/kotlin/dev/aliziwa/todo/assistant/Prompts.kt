package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** Builds the system prompt (schema + today's context) and the repair prompt. */
object Prompts {

    /**
     * JSON-schema shaped, with real `enum` arrays instead of `a | b | c` placeholders — small
     * models copy prose placeholders verbatim ("op": "add_task | update_task | …"), which validates
     * as JSON and then gets rejected downstream.
     */
    val JSON_SCHEMA: String = """{
  "reply": { "type": "string", "description": "one short sentence for the user" },
  "ops": [
    {
      "op":       { "enum": [${AssistantOps.all.joinToString(", ") { "\"$it\"" }}] },
      "title":    { "type": "string", "description": "the task title, or the title of the task to act on" },
      "taskId":   { "type": "string", "description": "copy from the context list when you know it" },
      "notes":    { "type": "string" },
      "priority": { "enum": ["low", "medium", "high"] },
      "scope":    { "enum": ["day", "week", "month", "recurring_daily"] },
      "date":     { "type": "string", "description": "YYYY-MM-DD" },
      "time":     { "type": "string", "description": "HH:MM, 24-hour" },
      "minutes":  { "type": "integer", "description": "extend_task only" },
      "question": { "type": "string", "description": "clarify only" }
    }
  ]
}"""

    /** Concrete answers, so the model has a shape to copy instead of the schema itself. */
    const val EXAMPLES: String = """Example — user says "gym tomorrow at 7":
{"reply":"Adding gym tomorrow at 07:00.","ops":[{"op":"add_task","title":"Gym","scope":"day","date":"2026-01-02","time":"07:00"}]}

Example — user says "done with the laundry":
{"reply":"Marking laundry done.","ops":[{"op":"complete_task","taskId":"abc-123","title":"Laundry"}]}

Example — user says "thanks!":
{"reply":"Any time.","ops":[]}"""

    fun system(
        today: LocalDate,
        nowMillis: Long,
        zone: TimeZone,
        items: List<TaskItem>,
    ): String = buildString {
        appendLine("You are the offline assistant inside a personal to-do app. You turn the user's message into a JSON plan.")
        appendLine()
        appendLine("Answer with exactly ONE JSON object and nothing else: no prose, no markdown, no code fences.")
        appendLine("This is the schema of that object, NOT a template to copy:")
        appendLine(JSON_SCHEMA)
        appendLine()
        appendLine(EXAMPLES)
        appendLine()
        appendLine("Rules:")
        appendLine("- Never copy schema text into your answer. For every \"enum\" pick exactly ONE of the listed values; a value like \"add_task | update_task\" is invalid.")
        appendLine("- Omit fields you do not need. Use an empty \"ops\" list for small talk and put your answer in \"reply\".")
        appendLine("- Use \"add_task\" for each new task; several tasks in one message means several ops.")
        appendLine("- Use \"complete_task\", \"delete_task\", \"extend_task\" or \"update_task\" only for tasks that appear in the context below, and copy their taskId.")
        appendLine("- \"recurring_daily\" scope means every day; put the time of day in \"time\".")
        appendLine("- Resolve relative dates yourself into \"date\". Use \"clarify\" with a \"question\" when the request is genuinely ambiguous.")
        appendLine("- Never invent tasks that are not in the context when completing or deleting.")
        appendLine()
        appendLine("Now: ${DateMath.format(today)} (${today.dayOfWeek.name.lowercase()}), ${Format.timeOfDay(DateMath.minutesOfDay(nowMillis, zone))}, timezone ${zone.id}.")
        appendLine("Tomorrow is ${DateMath.format(today.plus(1, DateTimeUnit.DAY))}.")
        appendLine()
        appendLine(contextBlock(items))
    }

    fun contextBlock(items: List<TaskItem>): String = buildString {
        appendLine("Current tasks (taskId | title | scope | status | due):")
        if (items.isEmpty()) {
            appendLine("  (none)")
            return@buildString
        }
        items.take(MAX_CONTEXT_ITEMS).forEach { item ->
            val due = item.dueAt?.let { "due@$it" } ?: "no time"
            appendLine("  ${item.key} | ${item.title} | ${item.scope.name.lowercase()} | ${item.status.name.lowercase()} | $due")
        }
        if (items.size > MAX_CONTEXT_ITEMS) appendLine("  ... ${items.size - MAX_CONTEXT_ITEMS} more")
    }

    /** Fed back to the model after an unparsable answer (one repair attempt). */
    fun repair(previousOutput: String, error: String): String = buildString {
        appendLine("Your previous answer could not be parsed as the required JSON object.")
        appendLine("Error: $error")
        appendLine()
        appendLine("Previous answer (truncated):")
        appendLine(previousOutput.take(600))
        appendLine()
        appendLine("Answer again with ONE valid JSON object matching the schema. No other text.")
    }

    fun shortSummary(items: List<TaskItem>): String {
        val pending = items.filter { it.status == TaskStatus.PENDING }
        if (pending.isEmpty()) return "Nothing pending."
        return pending.take(8).joinToString("; ") { it.title }
    }

    private const val MAX_CONTEXT_ITEMS = 30
}
