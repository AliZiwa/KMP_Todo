package dev.aliziwa.todo.data

import dev.aliziwa.todo.db.RecurringCompletionEntity
import dev.aliziwa.todo.db.TaskEntity
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.RecurringCompletion
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus

internal fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    notes = notes,
    priority = Priority.entries.getOrElse(priority) { Priority.MEDIUM },
    scope = TaskScope.entries.firstOrNull { it.name == scopeType } ?: TaskScope.DAY,
    dueAt = dueAtEpochMillis,
    scheduledDate = DateMath.parseDate(scheduledDate),
    recurringTimeMinutes = recurringTimeMinutes,
    recurringStartDate = DateMath.parseDate(recurringStartDate),
    status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.PENDING,
    extensionCount = extensionCount,
    createdAt = createdAtEpochMillis,
    updatedAt = updatedAtEpochMillis,
    completedAt = completedAtEpochMillis,
)

internal fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    notes = notes,
    priority = priority.ordinal,
    scopeType = scope.name,
    dueAtEpochMillis = dueAt,
    scheduledDate = scheduledDate?.toString(),
    recurringTimeMinutes = recurringTimeMinutes,
    recurringStartDate = recurringStartDate?.toString(),
    status = status.name,
    extensionCount = extensionCount,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
    completedAtEpochMillis = completedAt,
)

internal fun RecurringCompletionEntity.toDomain(): RecurringCompletion? {
    val parsed = DateMath.parseDate(date) ?: return null
    return RecurringCompletion(
        templateId = templateId,
        date = parsed,
        status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.PENDING,
        dueAt = dueAtEpochMillis,
        extensionCount = extensionCount,
        completedAt = completedAtEpochMillis,
        updatedAt = updatedAtEpochMillis,
    )
}

internal fun RecurringCompletion.toEntity(): RecurringCompletionEntity = RecurringCompletionEntity(
    templateId = templateId,
    date = date.toString(),
    status = status.name,
    dueAtEpochMillis = dueAt,
    extensionCount = extensionCount,
    completedAtEpochMillis = completedAt,
    updatedAtEpochMillis = updatedAt,
)
