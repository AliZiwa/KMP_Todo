package dev.aliziwa.todo.data

import dev.aliziwa.todo.db.SettingEntity
import dev.aliziwa.todo.db.SettingsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsRepository(
    private val dao: SettingsDao,
    scope: CoroutineScope,
) {
    val settings: StateFlow<AppSettings> = dao.observeAll()
        .map { rows -> AppSettings.from(rows.associate { it.settingKey to it.settingValue }) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = AppSettings.from(dao.all().associate { it.settingKey to it.settingValue })

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val before = current()
        val after = transform(before)
        if (after == before) return before
        val beforeMap = before.toMap()
        after.toMap().forEach { (key, value) ->
            if (beforeMap[key] != value) dao.put(SettingEntity(key, value))
        }
        (beforeMap.keys - after.toMap().keys).forEach { dao.remove(it) }
        return after
    }

    suspend fun putRaw(key: String, value: String) = dao.put(SettingEntity(key, value))
    suspend fun getRaw(key: String): String? = dao.get(key)
}
