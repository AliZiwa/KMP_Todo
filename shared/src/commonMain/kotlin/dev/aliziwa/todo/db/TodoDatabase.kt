package dev.aliziwa.todo.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
@ConstructedBy(TodoDatabaseConstructor::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}

// Room's KSP processor generates the actual implementations per target.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object TodoDatabaseConstructor : RoomDatabaseConstructor<TodoDatabase> {
    override fun initialize(): TodoDatabase
}

/** Common finishing step for a platform-created builder (bundled SQLite on every platform). */
fun RoomDatabase.Builder<TodoDatabase>.buildTodoDatabase(): TodoDatabase =
    this.setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
