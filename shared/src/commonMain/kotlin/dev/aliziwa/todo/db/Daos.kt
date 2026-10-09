package dev.aliziwa.todo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY priority DESC, dueAtEpochMillis ASC, createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE scopeType = :scope AND scheduledDate = :date")
    suspend fun byScopeAndDate(scope: String, date: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND dueAtEpochMillis IS NOT NULL AND dueAtEpochMillis >= :fromMillis")
    suspend fun pendingWithFutureDue(fromMillis: Long): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int
}

@Dao
interface RecurringCompletionDao {
    @Upsert
    suspend fun upsert(row: RecurringCompletionEntity)

    @Query("SELECT * FROM recurring_completions")
    fun observeAll(): Flow<List<RecurringCompletionEntity>>

    @Query("SELECT * FROM recurring_completions")
    suspend fun all(): List<RecurringCompletionEntity>

    @Query("SELECT * FROM recurring_completions WHERE templateId = :templateId AND date = :date")
    suspend fun byKey(templateId: String, date: String): RecurringCompletionEntity?

    @Query("SELECT * FROM recurring_completions WHERE date = :date")
    suspend fun byDate(date: String): List<RecurringCompletionEntity>

    /** Deleting a template removes its *future* rows; history is kept for stats. */
    @Query("DELETE FROM recurring_completions WHERE templateId = :templateId AND date >= :fromDate")
    suspend fun deleteFrom(templateId: String, fromDate: String)

    @Query("DELETE FROM recurring_completions WHERE templateId = :templateId")
    suspend fun deleteAllFor(templateId: String)
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(entity: SettingEntity)

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Query("SELECT settingValue FROM settings WHERE settingKey = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM settings WHERE settingKey = :key")
    suspend fun remove(key: String)
}

@Dao
interface AlertStateDao {
    @Upsert
    suspend fun put(entity: AlertStateEntity)

    @Query("SELECT * FROM alert_state WHERE alertKey = :key")
    suspend fun byKey(key: String): AlertStateEntity?

    @Query("SELECT * FROM alert_state")
    suspend fun all(): List<AlertStateEntity>

    @Query("DELETE FROM alert_state WHERE alertKey = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM alert_state WHERE alertedAtEpochMillis < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long)
}
