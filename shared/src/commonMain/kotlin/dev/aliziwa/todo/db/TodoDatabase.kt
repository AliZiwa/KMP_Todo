package dev.aliziwa.todo.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

const val TODO_DATABASE_VERSION = 2

@Database(
    entities = [
        TaskEntity::class,
        RecurringCompletionEntity::class,
        SettingEntity::class,
        AlertStateEntity::class,
    ],
    version = TODO_DATABASE_VERSION,
    exportSchema = true,
)
@ConstructedBy(TodoDatabaseConstructor::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun recurringCompletionDao(): RecurringCompletionDao
    abstract fun settingsDao(): SettingsDao
    abstract fun alertStateDao(): AlertStateDao
}

// Room's KSP processor generates the actual implementations per target.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object TodoDatabaseConstructor : RoomDatabaseConstructor<TodoDatabase> {
    override fun initialize(): TodoDatabase
}

/**
 * v1 (skeleton) -> v2: adds `recurringStartDate` to `tasks`, the `recurring_completions`,
 * `settings` and `alert_state` tables, and the task indices.
 *
 * The CREATE statements must match Room's exported schema exactly (see `shared/schemas`).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tasks` ADD COLUMN `recurringStartDate` TEXT")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_status_scheduledDate` ON `tasks` (`status`, `scheduledDate`)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_scopeType` ON `tasks` (`scopeType`)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_dueAtEpochMillis` ON `tasks` (`dueAtEpochMillis`)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_completions` (" +
                "`templateId` TEXT NOT NULL, `date` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`dueAtEpochMillis` INTEGER, `extensionCount` INTEGER NOT NULL, " +
                "`completedAtEpochMillis` INTEGER, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`templateId`, `date`))"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_completions_date` ON `recurring_completions` (`date`)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `settings` (" +
                "`settingKey` TEXT NOT NULL, `settingValue` TEXT NOT NULL, PRIMARY KEY(`settingKey`))"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `alert_state` (" +
                "`alertKey` TEXT NOT NULL, `alertedForDueAtEpochMillis` INTEGER NOT NULL, " +
                "`alertedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`alertKey`))"
        )
    }
}

/** Common finishing step for a platform-created builder (bundled SQLite on every platform). */
fun RoomDatabase.Builder<TodoDatabase>.buildTodoDatabase(): TodoDatabase =
    this.setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
