package dev.aliziwa.todo.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Builds a genuine v1 database by hand (the schema the skeleton shipped), then opens it with the
 * current [TodoDatabase]. Room runs [MIGRATION_1_2] and validates the resulting schema against the
 * exported `shared/schemas/.../2.json`, so a wrong CREATE statement fails here.
 */
class MigrationTest {

    private val v1IdentityHash = "37b5cded174e76f1c70c46de3c9fe0db"

    private val v1Tasks =
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`notes` TEXT NOT NULL, `priority` INTEGER NOT NULL, `scopeType` TEXT NOT NULL, " +
            "`dueAtEpochMillis` INTEGER, `scheduledDate` TEXT, `recurringTimeMinutes` INTEGER, " +
            "`status` TEXT NOT NULL, `extensionCount` INTEGER NOT NULL, " +
            "`createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
            "`completedAtEpochMillis` INTEGER, PRIMARY KEY(`id`))"

    @Test
    fun migratesV1DataForwardToV2(): Unit = runBlocking {
        val dir = Files.createTempDirectory("todo-migration").toFile()
        dir.deleteOnExit()
        val path = java.io.File(dir, "todo.db").absolutePath

        val connection = BundledSQLiteDriver().open(path)
        try {
            connection.execSQL(v1Tasks)
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$v1IdentityHash')"
            )
            connection.execSQL(
                "INSERT INTO tasks (id, title, notes, priority, scopeType, dueAtEpochMillis, scheduledDate, " +
                    "recurringTimeMinutes, status, extensionCount, createdAtEpochMillis, updatedAtEpochMillis, " +
                    "completedAtEpochMillis) VALUES " +
                    "('legacy-1', 'Old task', '', 2, 'DAY', 1700000000000, '2026-10-01', NULL, 'PENDING', 0, 1, 1, NULL)"
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }

        val db = Room.databaseBuilder<TodoDatabase>(name = path).buildTodoDatabase()
        try {
            val task = db.taskDao().byId("legacy-1")
            assertNotNull(task)
            assertEquals("Old task", task.title)
            assertNull(task.recurringStartDate)

            // The new tables must exist and be usable.
            db.settingsDao().put(SettingEntity("k", "v"))
            assertEquals("v", db.settingsDao().get("k"))
            db.alertStateDao().put(AlertStateEntity("legacy-1", 1700000000000, 1700000000001))
            assertNotNull(db.alertStateDao().byKey("legacy-1"))
            db.recurringCompletionDao().upsert(
                RecurringCompletionEntity(
                    templateId = "legacy-1",
                    date = "2026-10-09",
                    status = "DONE",
                    dueAtEpochMillis = 1700000000000,
                    extensionCount = 0,
                    completedAtEpochMillis = 1700000000002,
                    updatedAtEpochMillis = 1700000000002,
                )
            )
            assertNotNull(db.recurringCompletionDao().byKey("legacy-1", "2026-10-09"))
        } finally {
            db.close()
        }
    }

    @Test
    fun freshDatabaseIsAtTheCurrentVersion(): Unit = runBlocking {
        assertEquals(2, TODO_DATABASE_VERSION)
        val dir = Files.createTempDirectory("todo-fresh").toFile()
        dir.deleteOnExit()
        val db = Room.databaseBuilder<TodoDatabase>(name = java.io.File(dir, "fresh.db").absolutePath)
            .buildTodoDatabase()
        try {
            assertEquals(0, db.taskDao().count())
        } finally {
            db.close()
        }
    }
}
