package dev.aliziwa.todo.db

import androidx.room.Room
import androidx.room.RoomDatabase

fun iosDatabaseBuilder(databasePath: String): RoomDatabase.Builder<TodoDatabase> =
    Room.databaseBuilder<TodoDatabase>(name = databasePath)
