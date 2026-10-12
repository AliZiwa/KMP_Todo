package dev.aliziwa.todo.data

import dev.aliziwa.todo.fakes.FakeSettingsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun userNameRoundTripsThroughTheKeyValueMap() {
        val saved = AppSettings(userName = "Sam")
        assertEquals("Sam", AppSettings.from(saved.toMap()).userName)
    }

    @Test
    fun aMissingOrBlankUserNameKeyReadsAsNull() {
        assertNull(AppSettings.from(emptyMap()).userName)
        assertNull(AppSettings.from(mapOf(AppSettings.Keys.USER_NAME to "   ")).userName)
        // Clearing the name removes the key entirely rather than storing "".
        assertTrue(AppSettings.Keys.USER_NAME !in AppSettings(userName = null).toMap())
    }

    @Test
    fun aStoredUserNameIsTrimmed() {
        assertEquals("Sam", AppSettings.from(mapOf(AppSettings.Keys.USER_NAME to "  Sam  ")).userName)
    }

    @Test
    fun everyOtherFieldStillRoundTrips() {
        val saved = AppSettings(
            llmBackend = LlmBackend.OLLAMA,
            localModelPath = "/models/qwen.gguf",
            ollamaBaseUrl = "http://10.0.0.4:11434",
            ollamaModel = "qwen2.5",
            dailyPromptHour = 7,
            dailyPromptMinute = 45,
            dailyPromptEnabled = false,
            soundEnabled = false,
            launchAtLogin = true,
            use24HourClock = true,
            lastDailyPromptDate = "2026-10-12",
            assistantEnabled = false,
            generationTimeoutMillis = 30_000,
            userName = "Sam",
        )
        assertEquals(saved, AppSettings.from(saved.toMap()))
    }

    // ----------------------------------------------------- first-run backend default

    /**
     * The repository eagerly shares a never-ending flow, so it gets a scope of its own that the
     * test cancels; otherwise `runTest` would wait forever for that collector.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private inline fun withRepo(dao: FakeSettingsDao, block: (SettingsRepository) -> Unit) {
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher())
        try {
            block(SettingsRepository(dao, scope))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun firstRunDefaultsToTheOnDeviceEngineWhenTheOsProvidesTheModel() = runTest {
        val dao = FakeSettingsDao()
        withRepo(dao) { repo ->
            val settings = repo.applyFirstRunDefaults(engineHasBuiltInModel = true)
            assertEquals(LlmBackend.LOCAL_ENGINE, settings.llmBackend)
            assertEquals(LlmBackend.LOCAL_ENGINE.name, dao.rows[AppSettings.Keys.LLM_BACKEND])
        }
    }

    @Test
    fun firstRunStaysRulesOnlyWithoutABuiltInModel() = runTest {
        val dao = FakeSettingsDao()
        withRepo(dao) { repo ->
            val settings = repo.applyFirstRunDefaults(engineHasBuiltInModel = false)
            assertEquals(LlmBackend.RULES_ONLY, settings.llmBackend)
            assertNull(dao.rows[AppSettings.Keys.LLM_BACKEND])
        }
    }

    @Test
    fun anExplicitBackendChoiceIsNeverOverwritten() = runTest {
        val dao = FakeSettingsDao(mapOf(AppSettings.Keys.LLM_BACKEND to LlmBackend.RULES_ONLY.name))
        withRepo(dao) { repo ->
            val settings = repo.applyFirstRunDefaults(engineHasBuiltInModel = true)
            assertEquals(LlmBackend.RULES_ONLY, settings.llmBackend)
        }
    }
}
