package dev.aliziwa.todo.llm

import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.fakes.FakeClock
import dev.aliziwa.todo.fakes.FakeLocalLlmEngine
import dev.aliziwa.todo.fakes.TestPlatform
import dev.aliziwa.todo.platform.BuiltInModel
import dev.aliziwa.todo.platform.EngineState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An engine whose model ships with the OS (Apple Intelligence) must resolve with no
 * `localModelPath` at all, and must present the OS model's display name — never a file name.
 */
class BuiltInModelRuntimeTest {

    private val appleIntelligence = BuiltInModel(
        id = "apple.foundation-models",
        displayName = "Apple Intelligence",
    )

    private fun factory(engine: FakeLocalLlmEngine): LlmRuntimeFactory =
        LlmRuntimeFactory(TestPlatform(FakeClock(0L), engineFake = engine).services)

    @Test
    fun builtInEngineResolvesWithoutAConfiguredModelPath() = runTest {
        val engine = FakeLocalLlmEngine(
            supportedExtensions = emptyList(),
            builtInModel = appleIntelligence,
        )
        val runtime = factory(engine).resolve(
            AppSettings(llmBackend = LlmBackend.LOCAL_ENGINE, localModelPath = null)
        )

        assertTrue(runtime.isSuccess, "expected a runtime, got ${runtime.exceptionOrNull()}")
        assertEquals("Apple Intelligence", runtime.getOrThrow().label)
        assertEquals(LlmBackend.LOCAL_ENGINE, runtime.getOrThrow().backend)
        // The built-in id is what gets loaded, and loading still happens exactly once.
        assertEquals(listOf(appleIntelligence.id), engine.loadedPaths)
        assertEquals(
            EngineState.Ready(appleIntelligence.id, appleIntelligence.id),
            engine.state.value,
        )
    }

    @Test
    fun theResolvedRuntimeIsCachedSoTheModelLoadsOnce() = runTest {
        val engine = FakeLocalLlmEngine(
            supportedExtensions = emptyList(),
            builtInModel = appleIntelligence,
        )
        val subject = factory(engine)
        val settings = AppSettings(llmBackend = LlmBackend.LOCAL_ENGINE)

        val first = subject.resolve(settings).getOrThrow()
        val second = subject.resolve(settings).getOrThrow()

        assertEquals(first, second)
        assertEquals(1, engine.loadedPaths.size)
    }

    @Test
    fun aFailingBuiltInEngineReportsItsOwnUserFacingReason() = runTest {
        val engine = FakeLocalLlmEngine(
            failLoadWith = "Apple Intelligence is turned off in Settings",
            supportedExtensions = emptyList(),
            builtInModel = appleIntelligence,
        )
        val result = factory(engine).resolve(AppSettings(llmBackend = LlmBackend.LOCAL_ENGINE))

        val reason = (result.exceptionOrNull() as LlmUnavailableException).reason
        assertEquals(
            LlmUnavailable.EngineError("Apple Intelligence is turned off in Settings"),
            reason,
        )
    }

    @Test
    fun aFileBasedEngineStillNeedsAModelPath() = runTest {
        val engine = FakeLocalLlmEngine()
        val result = factory(engine).resolve(
            AppSettings(llmBackend = LlmBackend.LOCAL_ENGINE, localModelPath = null)
        )

        assertEquals(
            LlmUnavailable.NoModelConfigured,
            (result.exceptionOrNull() as LlmUnavailableException).reason,
        )
    }

    @Test
    fun anEngineWithABuiltInModelHasNothingToDownload() {
        val engine = FakeLocalLlmEngine(
            supportedExtensions = emptyList(),
            builtInModel = appleIntelligence,
        )
        assertTrue(ModelCatalog.suggestionsFor(engine.supportedExtensions).isEmpty())
        assertEquals(null, ModelCatalog.defaultFor(engine.supportedExtensions))
    }
}
