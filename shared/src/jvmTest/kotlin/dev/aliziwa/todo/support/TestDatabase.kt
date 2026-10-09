package dev.aliziwa.todo.support

import androidx.room.Room
import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.db.buildTodoDatabase
import java.nio.file.Files

/**
 * Real Room database on a temp file with the bundled SQLite driver — the same stack the apps use,
 * so DAO queries, indices and the migration path are genuinely exercised.
 */
object TestDatabase {
    fun create(): TodoDatabase {
        val dir = Files.createTempDirectory("personal-todo-test").toFile()
        dir.deleteOnExit()
        val file = java.io.File(dir, "todo.db")
        return Room.databaseBuilder<TodoDatabase>(name = file.absolutePath).buildTodoDatabase()
    }
}
