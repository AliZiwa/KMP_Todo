package dev.aliziwa.todo.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A task row. For `scopeType = RECURRING_DAILY` this is a *template*; per-day state lives in
 * [RecurringCompletionEntity].
 *
 * All instants are epoch millis UTC. [scheduledDate] is the local `yyyy-MM-dd` the task belongs to.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["status", "scheduledDate"], name = "index_tasks_status_scheduledDate"),
        Index(value = ["scopeType"], name = "index_tasks_scopeType"),
        Index(value = ["dueAtEpochMillis"], name = "index_tasks_dueAtEpochMillis"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String = "",
    val priority: Int = 1,
    val scopeType: String = "DAY",          // DAY | WEEK | MONTH | RECURRING_DAILY
    val dueAtEpochMillis: Long? = null,
    val scheduledDate: String? = null,      // ISO yyyy-MM-dd local date the task belongs to
    val recurringTimeMinutes: Int? = null,  // minutes since midnight for recurring templates
    val recurringStartDate: String? = null, // first local date a recurring template is active
    val status: String = "PENDING",         // PENDING | DONE | DISMISSED
    val extensionCount: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
)

/** Per-day state of a recurring template. Missing row == pending at the template's time. */
@Entity(
    tableName = "recurring_completions",
    primaryKeys = ["templateId", "date"],
    indices = [Index(value = ["date"], name = "index_recurring_completions_date")],
)
data class RecurringCompletionEntity(
    val templateId: String,
    val date: String,                       // ISO yyyy-MM-dd
    val status: String = "PENDING",
    val dueAtEpochMillis: Long? = null,
    val extensionCount: Int = 0,
    val completedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

/** Simple key/value settings store. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val settingKey: String,
    val settingValue: String,
)

/**
 * Remembers which due instant we already alerted for, so a task alerts once per due time but
 * alerts again after an extension (its due instant changed).
 */
@Entity(tableName = "alert_state")
data class AlertStateEntity(
    /** Task id, or "templateId@yyyy-MM-dd" for recurring instances. */
    @PrimaryKey val alertKey: String,
    val alertedForDueAtEpochMillis: Long,
    val alertedAtEpochMillis: Long,
)
