package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Operation names the model must use. Flat shape — small models cope far better than with unions. */
object AssistantOps {
    const val ADD = "add_task"
    const val UPDATE = "update_task"
    const val COMPLETE = "complete_task"
    const val DELETE = "delete_task"
    const val EXTEND = "extend_task"
    const val LIST = "list_tasks"
    const val CLARIFY = "clarify"
    const val CHAT = "chat"

    val all = listOf(ADD, UPDATE, COMPLETE, DELETE, EXTEND, LIST, CLARIFY, CHAT)
}

@Serializable
data class AssistantOp(
    val op: String = AssistantOps.CHAT,
    val title: String? = null,
    val taskId: String? = null,
    val notes: String? = null,
    /** "low" | "medium" | "high" */
    val priority: String? = null,
    /** "day" | "week" | "month" | "recurring_daily" */
    val scope: String? = null,
    /** ISO "YYYY-MM-DD" */
    val date: String? = null,
    /** 24-hour "HH:MM" */
    val time: String? = null,
    /** Minutes, for extend_task. */
    val minutes: Int? = null,
    /** Question to ask the user, for clarify. */
    val question: String? = null,
)

@Serializable
data class AssistantPlan(
    val reply: String = "",
    val ops: List<AssistantOp> = emptyList(),
)

/** Where a plan came from — shown on the confirmation card. */
enum class PlanSource { LLM, RULES }

/**
 * Tolerant JSON extraction. Small local models wrap output in prose or code fences, emit trailing
 * commentary, or add stray keys — all of which we forgive.
 */
object PlanJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parse(raw: String): Result<AssistantPlan> {
        val candidate = extractJsonObject(raw)
            ?: return Result.failure(IllegalArgumentException("No JSON object found in model output"))
        return runCatching { json.decodeFromString<AssistantPlan>(candidate) }
            .onFailure { Log.w("PlanJson", "decode failed: ${it.message}") }
    }

    fun encode(plan: AssistantPlan): String = json.encodeToString(plan)

    /** First balanced `{...}` in [raw], ignoring braces inside strings. Strips code fences first. */
    fun extractJsonObject(raw: String): String? {
        val text = stripFences(raw)
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun stripFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            // Drop an optional language tag on the first line.
            val newline = text.indexOf('\n')
            if (newline in 0..20 && !text.substring(0, newline).contains('{')) {
                text = text.substring(newline + 1)
            }
            val closing = text.lastIndexOf("```")
            if (closing >= 0) text = text.substring(0, closing)
        }
        return text.trim()
    }
}
