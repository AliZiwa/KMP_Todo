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

    /**
     * First-run defaults that depend on the platform rather than on the data class.
     *
     * A platform whose engine ships an OS-provided model (Apple Intelligence) has nothing to
     * download, so the assistant should be on out of the box: when no backend has ever been
     * persisted, store [LlmBackend.LOCAL_ENGINE]. Every other platform keeps
     * [LlmBackend.RULES_ONLY] until the user picks a model. Explicit user choices are never
     * overwritten — the key's presence is the "the user has been here" marker.
     */
    suspend fun applyFirstRunDefaults(engineHasBuiltInModel: Boolean): AppSettings {
        if (engineHasBuiltInModel && dao.get(AppSettings.Keys.LLM_BACKEND) == null) {
            dao.put(SettingEntity(AppSettings.Keys.LLM_BACKEND, LlmBackend.LOCAL_ENGINE.name))
        }
        return current()
    }
}
