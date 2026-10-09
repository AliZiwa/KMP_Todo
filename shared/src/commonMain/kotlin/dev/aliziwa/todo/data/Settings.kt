package dev.aliziwa.todo.data

/** Which engine answers the assistant. */
enum class LlmBackend { LOCAL_ENGINE, OLLAMA, RULES_ONLY }

/**
 * All user settings, stored as strings in the `settings` table so adding a field never needs a
 * schema migration.
 */
data class AppSettings(
    val llmBackend: LlmBackend = LlmBackend.RULES_ONLY,
    val localModelPath: String? = null,
    val ollamaBaseUrl: String = "http://localhost:11434",
    val ollamaModel: String = "llama3.2",
    val dailyPromptHour: Int = 8,
    val dailyPromptMinute: Int = 0,
    val dailyPromptEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val launchAtLogin: Boolean = false,
    /** ISO date of the last day the "plan your day" prompt was shown. */
    val lastDailyPromptDate: String? = null,
    /** Set to false by the 2-strike failure policy; the user re-enables it in settings. */
    val assistantEnabled: Boolean = true,
    val generationTimeoutMillis: Long = 60_000,
) {
    fun toMap(): Map<String, String> = buildMap {
        put(Keys.LLM_BACKEND, llmBackend.name)
        localModelPath?.let { put(Keys.LOCAL_MODEL_PATH, it) }
        put(Keys.OLLAMA_BASE_URL, ollamaBaseUrl)
        put(Keys.OLLAMA_MODEL, ollamaModel)
        put(Keys.DAILY_PROMPT_HOUR, dailyPromptHour.toString())
        put(Keys.DAILY_PROMPT_MINUTE, dailyPromptMinute.toString())
        put(Keys.DAILY_PROMPT_ENABLED, dailyPromptEnabled.toString())
        put(Keys.SOUND_ENABLED, soundEnabled.toString())
        put(Keys.LAUNCH_AT_LOGIN, launchAtLogin.toString())
        lastDailyPromptDate?.let { put(Keys.LAST_DAILY_PROMPT_DATE, it) }
        put(Keys.ASSISTANT_ENABLED, assistantEnabled.toString())
        put(Keys.GENERATION_TIMEOUT, generationTimeoutMillis.toString())
    }

    object Keys {
        const val LLM_BACKEND = "llm.backend"
        const val LOCAL_MODEL_PATH = "llm.localModelPath"
        const val OLLAMA_BASE_URL = "llm.ollamaBaseUrl"
        const val OLLAMA_MODEL = "llm.ollamaModel"
        const val DAILY_PROMPT_HOUR = "prompt.hour"
        const val DAILY_PROMPT_MINUTE = "prompt.minute"
        const val DAILY_PROMPT_ENABLED = "prompt.enabled"
        const val SOUND_ENABLED = "sound.enabled"
        const val LAUNCH_AT_LOGIN = "system.launchAtLogin"
        const val LAST_DAILY_PROMPT_DATE = "prompt.lastShownDate"
        const val ASSISTANT_ENABLED = "assistant.enabled"
        const val GENERATION_TIMEOUT = "assistant.timeoutMillis"
    }

    companion object {
        fun from(values: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                llmBackend = values[Keys.LLM_BACKEND]
                    ?.let { name -> LlmBackend.entries.firstOrNull { it.name == name } }
                    ?: defaults.llmBackend,
                localModelPath = values[Keys.LOCAL_MODEL_PATH]?.takeIf { it.isNotBlank() },
                ollamaBaseUrl = values[Keys.OLLAMA_BASE_URL]?.takeIf { it.isNotBlank() } ?: defaults.ollamaBaseUrl,
                ollamaModel = values[Keys.OLLAMA_MODEL]?.takeIf { it.isNotBlank() } ?: defaults.ollamaModel,
                dailyPromptHour = values[Keys.DAILY_PROMPT_HOUR]?.toIntOrNull()?.coerceIn(0, 23)
                    ?: defaults.dailyPromptHour,
                dailyPromptMinute = values[Keys.DAILY_PROMPT_MINUTE]?.toIntOrNull()?.coerceIn(0, 59)
                    ?: defaults.dailyPromptMinute,
                dailyPromptEnabled = values[Keys.DAILY_PROMPT_ENABLED]?.toBooleanStrictOrNull()
                    ?: defaults.dailyPromptEnabled,
                soundEnabled = values[Keys.SOUND_ENABLED]?.toBooleanStrictOrNull() ?: defaults.soundEnabled,
                launchAtLogin = values[Keys.LAUNCH_AT_LOGIN]?.toBooleanStrictOrNull() ?: defaults.launchAtLogin,
                lastDailyPromptDate = values[Keys.LAST_DAILY_PROMPT_DATE]?.takeIf { it.isNotBlank() },
                assistantEnabled = values[Keys.ASSISTANT_ENABLED]?.toBooleanStrictOrNull()
                    ?: defaults.assistantEnabled,
                generationTimeoutMillis = values[Keys.GENERATION_TIMEOUT]?.toLongOrNull()
                    ?: defaults.generationTimeoutMillis,
            )
        }
    }
}
