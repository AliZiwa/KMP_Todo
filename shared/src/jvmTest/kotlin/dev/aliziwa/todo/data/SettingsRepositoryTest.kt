package dev.aliziwa.todo.data

import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.support.TestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: SettingsRepository

    @BeforeTest
    fun setUp() {
        db = TestDatabase.create()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = SettingsRepository(db.settingsDao(), scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun defaultsApplyToAnEmptyTable(): Unit = runBlocking {
        val settings = repo.current()
        assertEquals(LlmBackend.RULES_ONLY, settings.llmBackend)
        assertEquals(8, settings.dailyPromptHour)
        assertTrue(settings.soundEnabled)
        assertNull(settings.localModelPath)
    }

    @Test
    fun updatesRoundTripThroughTheDatabase(): Unit = runBlocking {
        repo.update {
            it.copy(
                llmBackend = LlmBackend.OLLAMA,
                ollamaBaseUrl = "http://192.168.1.10:11434",
                ollamaModel = "qwen2.5",
                dailyPromptHour = 6,
                dailyPromptMinute = 45,
                soundEnabled = false,
            )
        }
        val reloaded = repo.current()
        assertEquals(LlmBackend.OLLAMA, reloaded.llmBackend)
        assertEquals("http://192.168.1.10:11434", reloaded.ollamaBaseUrl)
        assertEquals("qwen2.5", reloaded.ollamaModel)
        assertEquals(6, reloaded.dailyPromptHour)
        assertEquals(45, reloaded.dailyPromptMinute)
        assertTrue(!reloaded.soundEnabled)
    }

    @Test
    fun corruptValuesFallBackToDefaults(): Unit = runBlocking {
        repo.putRaw(AppSettings.Keys.LLM_BACKEND, "NOT_A_BACKEND")
        repo.putRaw(AppSettings.Keys.DAILY_PROMPT_HOUR, "not-a-number")
        repo.putRaw(AppSettings.Keys.SOUND_ENABLED, "maybe")
        val settings = repo.current()
        assertEquals(LlmBackend.RULES_ONLY, settings.llmBackend)
        assertEquals(8, settings.dailyPromptHour)
        assertTrue(settings.soundEnabled)
    }

    @Test
    fun clearingAnOptionalValueRemovesTheRow(): Unit = runBlocking {
        repo.update { it.copy(localModelPath = "/models/a.gguf") }
        assertEquals("/models/a.gguf", repo.current().localModelPath)
        repo.update { it.copy(localModelPath = null) }
        assertNull(repo.current().localModelPath)
        assertNull(repo.getRaw(AppSettings.Keys.LOCAL_MODEL_PATH))
    }
}
