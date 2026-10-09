package dev.aliziwa.todo.data

import dev.aliziwa.todo.db.AlertStateDao
import dev.aliziwa.todo.db.AlertStateEntity
import dev.aliziwa.todo.db.RecurringCompletionDao
import dev.aliziwa.todo.db.TaskDao
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.ExtensionRules
import dev.aliziwa.todo.domain.RecurringCompletion
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskKeys
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskSnapshot
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.domain.TaskValidation
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.util.Ids
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/** Result of a delete, so the UI can offer a 5-second undo. */
data class DeletedTask(
    val task: Task,
    val completions: List<RecurringCompletion>,
)

/**
 * Single source of truth for tasks. Exposes one [snapshot] StateFlow; every per-tab view is a pure
 * projection ([Boards]) of it, which keeps the UI cheap and the logic testable.
 */
class TaskRepository(
    private val taskDao: TaskDao,
    private val recurringDao: RecurringCompletionDao,
    private val alertDao: AlertStateDao,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val tag = "TaskRepository"

    val snapshot: StateFlow<TaskSnapshot> =
        combine(taskDao.observeAll(), recurringDao.observeAll()) { tasks, completions ->
            TaskSnapshot(
                tasks = tasks.map { it.toDomain() },
                completions = completions.mapNotNull { it.toDomain() },
            )
        }.stateIn(scope, SharingStarted.Eagerly, TaskSnapshot())

    private fun now() = clock.nowEpochMillis()
    private fun zone() = DateMath.zone(clock.timeZoneId())
    fun today(): LocalDate = DateMath.today(now(), zone())

    suspend fun snapshotNow(): TaskSnapshot = TaskSnapshot(
        tasks = taskDao.all().map { it.toDomain() },
        completions = recurringDao.all().mapNotNull { it.toDomain() },
    )

    // ------------------------------------------------------------------ writes

    /** Creates a task from a draft. Returns null when the title is empty after trimming. */
    suspend fun create(draft: TaskDraft): String? {
        val title = TaskValidation.normalizeTitle(draft.title) ?: return null
        val now = now()
        val zone = zone()
        val anchor = draft.date ?: DateMath.today(now, zone)
        val task = when (draft.scope) {
            TaskScope.RECURRING_DAILY -> Task(
                id = draft.id ?: Ids.newId(),
                title = title,
                notes = TaskValidation.normalizeNotes(draft.notes),
                priority = draft.priority,
                scope = TaskScope.RECURRING_DAILY,
                dueAt = null,
                scheduledDate = null,
                recurringTimeMinutes = draft.timeMinutes,
                recurringStartDate = anchor,
                createdAt = now,
                updatedAt = now,
            )
            else -> Task(
                id = draft.id ?: Ids.newId(),
                title = title,
                notes = TaskValidation.normalizeNotes(draft.notes),
                priority = draft.priority,
                scope = draft.scope,
                dueAt = draft.timeMinutes?.let { DateMath.atMinutes(anchor, it, zone) },
                scheduledDate = anchor,
                createdAt = now,
                updatedAt = now,
            )
        }
        taskDao.upsert(task.toEntity())
        Log.d(tag, "created ${task.id} (${task.scope}) due=${task.dueAt}")
        return task.id
    }

    /** Applies a draft to an existing task. Recurring time changes take effect from today. */
    suspend fun update(taskId: String, draft: TaskDraft): Boolean {
        val existing = taskDao.byId(taskId)?.toDomain() ?: return false
        val title = TaskValidation.normalizeTitle(draft.title) ?: return false
        val now = now()
        val zone = zone()
        val anchor = draft.date ?: existing.scheduledDate ?: DateMath.today(now, zone)
        val updated = when (draft.scope) {
            TaskScope.RECURRING_DAILY -> existing.copy(
                title = title,
                notes = TaskValidation.normalizeNotes(draft.notes),
                priority = draft.priority,
                scope = TaskScope.RECURRING_DAILY,
                dueAt = null,
                scheduledDate = null,
                recurringTimeMinutes = draft.timeMinutes,
                recurringStartDate = existing.recurringStartDate ?: anchor,
                updatedAt = now,
            )
            else -> existing.copy(
                title = title,
                notes = TaskValidation.normalizeNotes(draft.notes),
                priority = draft.priority,
                scope = draft.scope,
                dueAt = draft.timeMinutes?.let { DateMath.atMinutes(anchor, it, zone) },
                scheduledDate = anchor,
                recurringTimeMinutes = null,
                updatedAt = now,
            )
        }
        taskDao.upsert(updated.toEntity())
        if (updated.scope == TaskScope.RECURRING_DAILY &&
            existing.recurringTimeMinutes != updated.recurringTimeMinutes
        ) {
            // "time change applies from today": drop today's stale materialized due instant.
            val today = DateMath.today(now, zone)
            recurringDao.byKey(taskId, today.toString())?.let { row ->
                if (row.status == TaskStatus.PENDING.name) {
                    recurringDao.deleteFrom(taskId, today.toString())
                }
            }
            clearAlertStateFor(taskId)
        }
        return true
    }

    /** Idempotent: completing an already-done task is a no-op. */
    suspend fun complete(key: String): Boolean = setStatus(key, TaskStatus.DONE)

    suspend fun dismiss(key: String): Boolean = setStatus(key, TaskStatus.DISMISSED)

    suspend fun reopen(key: String): Boolean = setStatus(key, TaskStatus.PENDING)

    private suspend fun setStatus(key: String, status: TaskStatus): Boolean {
        val now = now()
        val taskId = TaskKeys.taskId(key)
        val instanceDate = TaskKeys.instanceDate(key)
        val task = taskDao.byId(taskId)?.toDomain() ?: return false

        if (task.scope == TaskScope.RECURRING_DAILY && instanceDate != null) {
            val existing = recurringDao.byKey(taskId, instanceDate.toString())?.toDomain()
            if (existing?.status == status) return true
            val dueAt = existing?.dueAt
                ?: task.recurringTimeMinutes?.let { DateMath.atMinutes(instanceDate, it, zone()) }
            recurringDao.upsert(
                RecurringCompletion(
                    templateId = taskId,
                    date = instanceDate,
                    status = status,
                    dueAt = dueAt,
                    extensionCount = existing?.extensionCount ?: 0,
                    completedAt = if (status == TaskStatus.DONE) now else null,
                    updatedAt = now,
                ).toEntity()
            )
            return true
        }

        if (task.status == status) return true
        taskDao.upsert(
            task.copy(
                status = status,
                completedAt = if (status == TaskStatus.DONE) now else null,
                updatedAt = now,
            ).toEntity()
        )
        return true
    }

    /** Pushes the due time forward and bumps the extension counter. Re-arming is the engine's job. */
    suspend fun extend(key: String, byMinutes: Int): Long? {
        val now = now()
        val taskId = TaskKeys.taskId(key)
        val instanceDate = TaskKeys.instanceDate(key)
        val task = taskDao.byId(taskId)?.toDomain() ?: return null

        if (task.scope == TaskScope.RECURRING_DAILY && instanceDate != null) {
            val existing = recurringDao.byKey(taskId, instanceDate.toString())?.toDomain()
            val currentDue = existing?.dueAt
                ?: task.recurringTimeMinutes?.let { DateMath.atMinutes(instanceDate, it, zone()) }
            val newDue = ExtensionRules.extendedDueAt(currentDue, now, byMinutes)
            recurringDao.upsert(
                RecurringCompletion(
                    templateId = taskId,
                    date = instanceDate,
                    status = TaskStatus.PENDING,
                    dueAt = newDue,
                    extensionCount = (existing?.extensionCount ?: 0) + 1,
                    completedAt = null,
                    updatedAt = now,
                ).toEntity()
            )
            clearAlertStateFor(key)
            return newDue
        }

        val newDue = ExtensionRules.extendedDueAt(task.dueAt, now, byMinutes)
        taskDao.upsert(
            task.copy(
                dueAt = newDue,
                status = TaskStatus.PENDING,
                completedAt = null,
                extensionCount = task.extensionCount + 1,
                updatedAt = now,
            ).toEntity()
        )
        clearAlertStateFor(key)
        return newDue
    }

    /**
     * Deletes a task. For a recurring instance key this deletes the *template* (per the spec) and
     * removes its future per-day rows while keeping history.
     */
    suspend fun delete(key: String): DeletedTask? {
        val taskId = TaskKeys.taskId(key)
        val task = taskDao.byId(taskId)?.toDomain() ?: return null
        val kept = if (task.scope == TaskScope.RECURRING_DAILY) {
            val today = today()
            val rows = recurringDao.byDate(today.toString()).mapNotNull { it.toDomain() }
                .filter { it.templateId == taskId }
            recurringDao.deleteFrom(taskId, today.toString())
            rows
        } else {
            emptyList()
        }
        taskDao.deleteById(taskId)
        clearAlertStateFor(key)
        clearAlertStateFor(taskId)
        return DeletedTask(task, kept)
    }

    /** Undo support: puts a deleted task (and its per-day rows) back. */
    suspend fun restore(deleted: DeletedTask) {
        taskDao.upsert(deleted.task.toEntity())
        deleted.completions.forEach { recurringDao.upsert(it.toEntity()) }
    }

    // ------------------------------------------------------------- alert state

    suspend fun alreadyAlerted(key: String, dueAt: Long): Boolean =
        alertDao.byKey(key)?.alertedForDueAtEpochMillis == dueAt

    suspend fun markAlerted(key: String, dueAt: Long) {
        alertDao.put(AlertStateEntity(key, dueAt, now()))
    }

    suspend fun clearAlertStateFor(key: String) = alertDao.remove(key)

    suspend fun pruneAlertState(olderThanMillis: Long = 7 * 24 * 60 * 60 * 1000L) {
        alertDao.pruneOlderThan(now() - olderThanMillis)
    }

    // ----------------------------------------------------------------- reads

    fun resolve(key: String): TaskItem? = Boards.resolve(snapshot.value, key, zone())

    suspend fun resolveFresh(key: String): TaskItem? {
        val taskId = TaskKeys.taskId(key)
        val task = taskDao.byId(taskId)?.toDomain() ?: return null
        val date = TaskKeys.instanceDate(key)
        return if (task.scope == TaskScope.RECURRING_DAILY && date != null) {
            val row = recurringDao.byKey(taskId, date.toString())?.toDomain()
            Boards.instanceOf(task, date, row, zone())
        } else {
            Boards.itemOf(task)
        }
    }
}
