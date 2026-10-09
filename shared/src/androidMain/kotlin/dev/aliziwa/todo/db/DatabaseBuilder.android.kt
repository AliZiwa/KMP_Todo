package dev.aliziwa.todo.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun androidDatabaseBuilder(context: Context, databasePath: String): RoomDatabase.Builder<TodoDatabase> =
    Room.databaseBuilder<TodoDatabase>(context = context.applicationContext, name = databasePath)
