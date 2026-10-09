package dev.aliziwa.todo.domain

import kotlinx.datetime.LocalDate

/** Ranking: HIGH first, then MEDIUM, then LOW. Ordinal mirrors [dev.aliziwa.todo.platform.DueAlert.priority]. */
enum class Priority { LOW, MEDIUM, HIGH }

/** Which tab a task lives in. */
enum class TaskScope { DAY, WEEK, MONTH, RECURRING_DAILY }

/** DISMISSED = the user silenced the alert without completing the task. */
enum class TaskStatus { PENDING, DONE, DISMISSED }

/**
 * A stored task. For [TaskScope.RECURRING_DAILY] this row is a *template*: the per-day state lives in
 * [RecurringCompletion] rows and is projected into [TaskItem] instances.
 */
data class Task(
    val id: String,
    val title: String,
    val notes: String = "",
    val priority: Priority = Priority.MEDIUM,
    val scope: TaskScope = TaskScope.DAY,
    /** Absolute due instant (epoch millis, UTC). Null when the task has no time. */
    val dueAt: Long? = null,
    /** Local date the task belongs to (anchor for WEEK/MONTH ranges). */
    val scheduledDate: LocalDate? = null,
    /** Minutes since local midnight, for recurring templates. */
    val recurringTimeMinutes: Int? = null,
    /** First local date a recurring template is active from. */
    val recurringStartDate: LocalDate? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val extensionCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
) {
    val isRecurringTemplate: Boolean get() = scope == TaskScope.RECURRING_DAILY
}

/** Per-day state of a recurring template. Absent row == PENDING with the template's time. */
data class RecurringCompletion(
    val templateId: String,
    val date: LocalDate,
    val status: TaskStatus,
    val dueAt: Long?,
    val extensionCount: Int = 0,
    val completedAt: Long? = null,
    val updatedAt: Long,
)

/**
 * Everything the UI and the [dev.aliziwa.todo.scheduling.DueEngine] work with: a concrete,
 * actionable item. Recurring templates expand into one item per materialized day.
 */
data class TaskItem(
    /** Stable key used for alarms, alerts and notification actions. See [TaskKeys]. */
    val key: String,
    val taskId: String,
    val title: String,
    val notes: String,
    val priority: Priority,
    val scope: TaskScope,
    val dueAt: Long?,
    val status: TaskStatus,
    val extensionCount: Int,
    val createdAt: Long,
    val completedAt: Long?,
    /** Non-null for recurring instances. */
    val instanceDate: LocalDate? = null,
    val scheduledDate: LocalDate? = null,
) {
    val isRecurringInstance: Boolean get() = scope == TaskScope.RECURRING_DAILY && instanceDate != null
    val isActionable: Boolean get() = status == TaskStatus.PENDING
}

/** Composite keys so one id space covers plain tasks and recurring instances. */
object TaskKeys {
    private const val SEP = "@"

    fun forTask(taskId: String): String = taskId
    fun forInstance(templateId: String, date: LocalDate): String = "$templateId$SEP$date"

    fun taskId(key: String): String = key.substringBefore(SEP)

    fun instanceDate(key: String): LocalDate? {
        val raw = key.substringAfter(SEP, "")
        if (raw.isEmpty()) return null
        return DateMath.parseDate(raw)
    }

    fun isInstance(key: String): Boolean = key.contains(SEP)
}

/** What the user is editing in the task sheet / what the assistant proposes. */
data class TaskDraft(
    val id: String? = null,
    val title: String = "",
    val notes: String = "",
    val priority: Priority = Priority.MEDIUM,
    val scope: TaskScope = TaskScope.DAY,
    val date: LocalDate? = null,
    /** Minutes since local midnight, or null for "no time". */
    val timeMinutes: Int? = null,
)

/** Title rules: trimmed, non-empty, capped. */
object TaskValidation {
    const val MAX_TITLE_LENGTH = 200
    const val MAX_NOTES_LENGTH = 4000

    fun normalizeTitle(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length > MAX_TITLE_LENGTH) trimmed.take(MAX_TITLE_LENGTH) else trimmed
    }

    fun normalizeNotes(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_NOTES_LENGTH) trimmed.take(MAX_NOTES_LENGTH) else trimmed
    }
}
