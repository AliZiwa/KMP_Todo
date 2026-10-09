package dev.aliziwa.todo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SKELETON entity so the Room/KSP pipeline is verified on every target.
 * The shared agent owns and will evolve this schema (bump version + migrations).
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String = "",
    val priority: Int = 1,
    val scopeType: String = "DAY",          // DAY | WEEK | MONTH | RECURRING_DAILY
    val dueAtEpochMillis: Long? = null,
    val scheduledDate: String? = null,      // ISO yyyy-MM-dd local date the task belongs to
    val recurringTimeMinutes: Int? = null,  // minutes since midnight for recurring templates
    val status: String = "PENDING",         // PENDING | DONE | DISMISSED
    val extensionCount: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
)
