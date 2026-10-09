package dev.aliziwa.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** Everything persisted, in one immutable shot. All board projections are pure functions of this. */
data class TaskSnapshot(
    val tasks: List<Task> = emptyList(),
    val completions: List<RecurringCompletion> = emptyList(),
) {
    val byId: Map<String, Task> get() = tasks.associateBy { it.id }
}

data class DayBoard(
    val date: LocalDate,
    /** DAY tasks from previous days that are still not done. */
    val overdue: List<TaskItem> = emptyList(),
    val items: List<TaskItem> = emptyList(),
)

data class RangeBoard(
    val start: LocalDate,
    val end: LocalDate,
    val label: String,
    val items: List<TaskItem> = emptyList(),
)

/**
 * Projects stored rows into actionable [TaskItem]s. Recurring templates are materialized lazily:
 * a missing `recurring_completions` row simply means "pending, at the template's time".
 */
object Boards {

    fun itemOf(task: Task): TaskItem = TaskItem(
        key = TaskKeys.forTask(task.id),
        taskId = task.id,
        title = task.title,
        notes = task.notes,
        priority = task.priority,
        scope = task.scope,
        dueAt = task.dueAt,
        status = task.status,
        extensionCount = task.extensionCount,
        createdAt = task.createdAt,
        completedAt = task.completedAt,
        instanceDate = null,
        scheduledDate = task.scheduledDate,
    )

    /** The instance of a recurring [template] for [date]; [completion] may be null (never touched). */
    fun instanceOf(
        template: Task,
        date: LocalDate,
        completion: RecurringCompletion?,
        zone: TimeZone,
    ): TaskItem {
        val minutes = template.recurringTimeMinutes
        val defaultDue = minutes?.let { DateMath.atMinutes(date, it, zone) }
        return TaskItem(
            key = TaskKeys.forInstance(template.id, date),
            taskId = template.id,
            title = template.title,
            notes = template.notes,
            priority = template.priority,
            scope = TaskScope.RECURRING_DAILY,
            dueAt = completion?.dueAt ?: defaultDue,
            status = completion?.status ?: TaskStatus.PENDING,
            extensionCount = completion?.extensionCount ?: 0,
            createdAt = template.createdAt,
            completedAt = completion?.completedAt,
            instanceDate = date,
            scheduledDate = date,
        )
    }

    fun recurringTemplates(snapshot: TaskSnapshot): List<Task> =
        snapshot.tasks.filter { it.scope == TaskScope.RECURRING_DAILY }

    fun isTemplateActiveOn(template: Task, date: LocalDate): Boolean {
        val start = template.recurringStartDate ?: return true
        return date >= start
    }

    fun instancesFor(snapshot: TaskSnapshot, date: LocalDate, zone: TimeZone): List<TaskItem> {
        val completions = snapshot.completions.associateBy { it.templateId to it.date }
        return recurringTemplates(snapshot)
            .filter { isTemplateActiveOn(it, date) }
            .map { instanceOf(it, date, completions[it.id to date], zone) }
    }

    fun day(snapshot: TaskSnapshot, date: LocalDate, zone: TimeZone): DayBoard {
        val dayTasks = snapshot.tasks.filter { it.scope == TaskScope.DAY }
        val todays = dayTasks.filter { it.scheduledDate == date }.map(::itemOf)
        val overdue = dayTasks
            .filter { it.scheduledDate != null && it.scheduledDate < date && it.status != TaskStatus.DONE }
            .map(::itemOf)
        // Tasks with no date at all behave like "today" so they are never invisible.
        val undated = dayTasks.filter { it.scheduledDate == null }.map(::itemOf)
        val instances = instancesFor(snapshot, date, zone)
        return DayBoard(
            date = date,
            overdue = overdue.ranked(),
            items = (todays + undated + instances).ranked(),
        )
    }

    fun week(snapshot: TaskSnapshot, anchor: LocalDate): RangeBoard {
        val (start, end) = DateMath.weekRange(anchor)
        val items = snapshot.tasks
            .filter { it.scope == TaskScope.WEEK && it.scheduledDate.inRange(start, end) }
            .map(::itemOf)
            .ranked()
        return RangeBoard(start, end, DateMath.weekKey(anchor), items)
    }

    fun month(snapshot: TaskSnapshot, anchor: LocalDate): RangeBoard {
        val (start, end) = DateMath.monthRange(anchor)
        val items = snapshot.tasks
            .filter { it.scope == TaskScope.MONTH && it.scheduledDate.inRange(start, end) }
            .map(::itemOf)
            .ranked()
        return RangeBoard(start, end, DateMath.monthKey(anchor), items)
    }

    /** Recurring tab: today's instance of each template, so the user can tick off today. */
    fun recurring(snapshot: TaskSnapshot, date: LocalDate, zone: TimeZone): List<TaskItem> =
        instancesFor(snapshot, date, zone).ranked()

    /**
     * Every item that may need an alarm: pending plain tasks with a due time plus recurring
     * instances inside a small horizon (today..today+[horizonDays]).
     */
    fun alarmCandidates(
        snapshot: TaskSnapshot,
        today: LocalDate,
        zone: TimeZone,
        horizonDays: Int = 2,
    ): List<TaskItem> {
        val plain = snapshot.tasks
            .filter { it.scope != TaskScope.RECURRING_DAILY && it.status == TaskStatus.PENDING && it.dueAt != null }
            .map(::itemOf)
        val recurring = (0..horizonDays).flatMap { offset ->
            instancesFor(snapshot, today.plus(offset, DateTimeUnit.DAY), zone)
        }.filter { it.status == TaskStatus.PENDING && it.dueAt != null }
        return plain + recurring
    }

    /** Resolve a key back to an item, materializing the recurring instance when needed. */
    fun resolve(snapshot: TaskSnapshot, key: String, zone: TimeZone): TaskItem? {
        val taskId = TaskKeys.taskId(key)
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return null
        val date = TaskKeys.instanceDate(key)
        return if (task.scope == TaskScope.RECURRING_DAILY && date != null) {
            instanceOf(task, date, snapshot.completions.firstOrNull { it.templateId == taskId && it.date == date }, zone)
        } else {
            itemOf(task)
        }
    }
}

private fun LocalDate?.inRange(start: LocalDate, end: LocalDate): Boolean =
    this != null && this >= start && this <= end
