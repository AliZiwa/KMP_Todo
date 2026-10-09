package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.ExtensionRules
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskSnapshot
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** A validated, human-reviewable operation. Shown on the confirmation card before anything runs. */
sealed interface PlannedAction {
    val summary: String

    data class Add(val draft: TaskDraft, override val summary: String) : PlannedAction
    data class Update(val taskId: String, val draft: TaskDraft, override val summary: String) : PlannedAction
    data class Complete(val key: String, override val summary: String) : PlannedAction
    data class Delete(val key: String, override val summary: String) : PlannedAction
    data class Extend(val key: String, val minutes: Int, override val summary: String) : PlannedAction
    data class ListTasks(val scope: TaskScope?, override val summary: String) : PlannedAction
}

/** The confirmation card's content. */
data class ResolvedPlan(
    val reply: String,
    val actions: List<PlannedAction>,
    val warnings: List<String> = emptyList(),
    val clarification: String? = null,
    val source: PlanSource = PlanSource.LLM,
) {
    val isEmpty: Boolean get() = actions.isEmpty()
    val needsConfirmation: Boolean get() = actions.any { it !is PlannedAction.ListTasks }
}

/**
 * Ops that legitimately produce no action: small talk and asking the user a question.
 * Everything else is supposed to change something.
 */
private val NON_ACTION_OPS = setOf(AssistantOps.CHAT, AssistantOps.CLARIFY)

/** True when the plan asked for at least one op that is supposed to change something. */
fun AssistantPlan.hasActionableOps(): Boolean =
    ops.any { it.op.lowercase().trim() !in NON_ACTION_OPS }

/**
 * True when the model asked for real work but every single op was rejected during validation —
 * e.g. it echoed the schema's enum placeholder as the op name, or named tasks that do not exist.
 *
 * That is a *failure*, not a successful empty plan: without this check the user gets a confident
 * "I'll add that" with nothing created and no confirmation card to notice.
 */
fun ResolvedPlan.isFullRejectionOf(plan: AssistantPlan): Boolean =
    plan.hasActionableOps() && actions.isEmpty()

/**
 * Turns raw model/rule output into actions, validating every reference against the database.
 * Hallucinated task ids or titles become warnings (or a clarification), never silent no-ops.
 */
class PlanResolver(private val zoneProvider: () -> TimeZone) {

    private val zone: TimeZone get() = zoneProvider()

    fun resolve(
        plan: AssistantPlan,
        snapshot: TaskSnapshot,
        nowMillis: Long,
        source: PlanSource,
    ): ResolvedPlan {
        val today = DateMath.today(nowMillis, zone)
        val universe = candidateItems(snapshot, today)
        val actions = mutableListOf<PlannedAction>()
        val warnings = mutableListOf<String>()
        var clarification: String? = null

        plan.ops.forEach { op ->
            when (op.op.lowercase().trim()) {
                AssistantOps.ADD -> resolveAdd(op, today, nowMillis)?.let { actions += it }
                    ?: warnings.add("Skipped an add with no title.")

                AssistantOps.UPDATE -> {
                    val target = match(universe, op)
                    if (target == null) {
                        warnings += "Could not find a task matching \"${op.title ?: op.taskId ?: "?"}\" to change."
                    } else {
                        val draft = draftFor(op, today, nowMillis, base = target)
                        actions += PlannedAction.Update(
                            taskId = target.taskId,
                            draft = draft,
                            summary = "Change \"${target.title}\"" + draftDetail(draft),
                        )
                    }
                }

                AssistantOps.COMPLETE -> {
                    val target = match(universe, op)
                    if (target == null) {
                        warnings += "No task matching \"${op.title ?: op.taskId ?: "?"}\" to complete."
                    } else {
                        actions += PlannedAction.Complete(target.key, "Mark \"${target.title}\" done")
                    }
                }

                AssistantOps.DELETE -> {
                    val target = match(universe, op)
                    if (target == null) {
                        warnings += "No task matching \"${op.title ?: op.taskId ?: "?"}\" to delete."
                    } else {
                        actions += PlannedAction.Delete(target.key, "Delete \"${target.title}\"")
                    }
                }

                AssistantOps.EXTEND -> {
                    val target = match(universe, op)
                    val minutes = ExtensionRules.sanitize(op.minutes ?: 15)
                    if (target == null) {
                        warnings += "No task matching \"${op.title ?: op.taskId ?: "?"}\" to push back."
                    } else {
                        actions += PlannedAction.Extend(
                            target.key,
                            minutes,
                            "Push \"${target.title}\" back by ${minutes}m",
                        )
                    }
                }

                AssistantOps.LIST -> actions += PlannedAction.ListTasks(
                    scope = parseScope(op.scope),
                    summary = "Show tasks",
                )

                AssistantOps.CLARIFY -> clarification = op.question?.takeIf { it.isNotBlank() }
                    ?: "Could you give me a bit more detail?"

                AssistantOps.CHAT -> Unit

                else -> warnings += "Ignored unknown operation \"${op.op}\"."
            }
        }

        return ResolvedPlan(
            reply = plan.reply.trim().ifBlank { defaultReply(actions, clarification) },
            actions = actions,
            warnings = warnings,
            clarification = clarification,
            source = source,
        )
    }

    /** Everything the assistant is allowed to reference: today, yesterday's leftovers, and the ranges. */
    fun candidateItems(snapshot: TaskSnapshot, today: LocalDate): List<TaskItem> {
        val day = Boards.day(snapshot, today, zone)
        val tomorrow = Boards.instancesFor(snapshot, today.plus(1, DateTimeUnit.DAY), zone)
        val week = Boards.week(snapshot, today).items
        val month = Boards.month(snapshot, today).items
        return (day.overdue + day.items + week + month + tomorrow).distinctBy { it.key }
    }

    private fun resolveAdd(op: AssistantOp, today: LocalDate, nowMillis: Long): PlannedAction.Add? {
        val title = op.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val draft = draftFor(op, today, nowMillis, base = null).copy(title = title)
        return PlannedAction.Add(draft, "Add \"$title\"" + draftDetail(draft))
    }

    private fun draftFor(op: AssistantOp, today: LocalDate, nowMillis: Long, base: TaskItem?): TaskDraft {
        val scope = parseScope(op.scope) ?: base?.scope ?: TaskScope.DAY
        val date = DateMath.parseDate(op.date) ?: base?.scheduledDate ?: today
        val timeMinutes = DateMath.parseTimeOfDay(op.time)
            ?: base?.dueAt?.let { DateMath.minutesOfDay(it, zone) }
        return TaskDraft(
            id = base?.taskId,
            title = op.title?.trim()?.takeIf { it.isNotEmpty() } ?: base?.title ?: "",
            notes = op.notes?.trim() ?: base?.notes ?: "",
            priority = parsePriority(op.priority) ?: base?.priority ?: Priority.MEDIUM,
            scope = scope,
            date = date,
            timeMinutes = timeMinutes,
        )
    }

    private fun draftDetail(draft: TaskDraft): String {
        val bits = mutableListOf<String>()
        when (draft.scope) {
            TaskScope.RECURRING_DAILY -> bits += "every day"
            TaskScope.WEEK -> bits += "this week"
            TaskScope.MONTH -> bits += "this month"
            TaskScope.DAY -> draft.date?.let { bits += DateMath.shortLabel(it) }
        }
        draft.timeMinutes?.let { bits += "at ${Format.timeOfDay(it)}" }
        if (draft.priority != Priority.MEDIUM) bits += draft.priority.name.lowercase() + " priority"
        return if (bits.isEmpty()) "" else " — " + bits.joinToString(", ")
    }

    private fun defaultReply(actions: List<PlannedAction>, clarification: String?): String = when {
        clarification != null -> clarification
        actions.isEmpty() -> "Nothing to change."
        actions.size == 1 -> actions.first().summary + "?"
        else -> "Apply these ${actions.size} changes?"
    }

    private fun parseScope(raw: String?): TaskScope? = when (raw?.lowercase()?.trim()) {
        "day", "today" -> TaskScope.DAY
        "week" -> TaskScope.WEEK
        "month" -> TaskScope.MONTH
        "recurring_daily", "recurring", "daily" -> TaskScope.RECURRING_DAILY
        else -> null
    }

    private fun parsePriority(raw: String?): Priority? = when (raw?.lowercase()?.trim()) {
        "low" -> Priority.LOW
        "medium", "normal" -> Priority.MEDIUM
        "high", "urgent" -> Priority.HIGH
        else -> null
    }

    // ---------------------------------------------------------- fuzzy matching

    /** Exact key/id first, then exact title, then containment, then token overlap. */
    fun match(items: List<TaskItem>, op: AssistantOp): TaskItem? {
        val id = op.taskId?.trim()
        if (!id.isNullOrEmpty()) {
            items.firstOrNull { it.key == id }?.let { return it }
            items.firstOrNull { it.taskId == id }?.let { return it }
        }
        val title = op.title?.trim()?.lowercase() ?: return null
        if (title.isEmpty()) return null

        val pending = items.sortedBy { if (it.status == TaskStatus.PENDING) 0 else 1 }
        pending.firstOrNull { it.title.lowercase() == title }?.let { return it }
        pending.firstOrNull { it.title.lowercase().contains(title) }?.let { return it }
        pending.firstOrNull { title.contains(it.title.lowercase()) }?.let { return it }

        val needleTokens = tokens(title)
        if (needleTokens.isEmpty()) return null
        var best: TaskItem? = null
        var bestScore = 0.0
        pending.forEach { item ->
            val itemTokens = tokens(item.title.lowercase())
            if (itemTokens.isEmpty()) return@forEach
            val shared = needleTokens.count { it in itemTokens }
            val score = shared.toDouble() / maxOf(needleTokens.size, itemTokens.size)
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return if (bestScore >= 0.5) best else null
    }

    private fun tokens(text: String): Set<String> =
        text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }.toSet()
}
