package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.assistant.AssistantMode
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.desktop.llm.LlamaCppEngine
import dev.aliziwa.todo.platform.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A model path saved in settings must come back up by itself: nothing in the desktop shell calls
 * `engine.load()`, so a restart with `LOCAL_ENGINE` selected has to be the shared layer's job
 * (`LlmRuntimeFactory.ensureLoaded`). These tests persist settings with one graph and then watch
 * what a *fresh* `TodoApp.start` on the same database does.
 */
class LocalModelAutoLoadTest {

    private val gguf = File(
        System.getProperty("user.home"),
        "Library/Application Support/PersonalTodo/models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    )

    private fun persistLocalEngineSettings(dir: File, modelPath: String) {
        DesktopTestHarness.withGraph(localLlmEngine = null, dir = dir, keepDir = true) { h ->
            runBlocking {
                h.graph.settingsRepository.update {
                    it.copy(
                        llmBackend = LlmBackend.LOCAL_ENGINE,
                        localModelPath = modelPath,
                        generationTimeoutMillis = 180_000,
                    )
                }
            }
        }
    }

    @Test
    fun `a saved local model path is loaded automatically on a fresh start`() {
        if (!gguf.isFile) {
            println("[skip] ${gguf.name} not downloaded; see LlamaCppEngineTest's KDoc")
            return
        }
        val dir = Files.createTempDirectory("todo-autoload-real").toFile()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(engineScope, contextSize = 2_048)
        try {
            persistLocalEngineSettings(dir, gguf.absolutePath)
            assertTrue(engine.state.value is EngineState.NotLoaded, "fresh engine must start NotLoaded")

            DesktopTestHarness.withGraph(localLlmEngine = engine, dir = dir, keepDir = true) { h ->
                runBlocking {
                    // Nobody in this test calls engine.load().
                    waitFor("engine Ready by itself", 120_000) { engine.state.value is EngineState.Ready }
                    waitFor("assistant READY", 30_000) {
                        h.graph.assistant.mode.value == AssistantMode.READY
                    }
                    waitFor("status line shows the model name", 10_000) {
                        h.graph.assistant.statusLine.value == gguf.name
                    }
                    val ready = h.graph.assistant.statusLine.value
                    println("[autoload] engineState=${engine.state.value} statusLine='$ready'")
                    assertEquals(gguf.name, ready)
                }
            }
        } finally {
            runBlocking { engine.unload() }
            engineScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the status line reports loading and then the model name`() {
        val dir = Files.createTempDirectory("todo-autoload-fake").toFile()
        val engine = SlowFakeEngine(loadDelayMillis = 1_200)
        try {
            persistLocalEngineSettings(dir, File(dir, "pretend-model.gguf").absolutePath)

            DesktopTestHarness.withGraph(localLlmEngine = engine, dir = dir, keepDir = true) { h ->
                runBlocking {
                    val seen = CopyOnWriteArrayList<String>()
                    val watcher = launch { h.graph.assistant.statusLine.collect { seen += it } }
                    waitFor("engine Ready by itself", 30_000) { engine.state.value is EngineState.Ready }
                    waitFor("assistant READY", 15_000) {
                        h.graph.assistant.mode.value == AssistantMode.READY
                    }
                    waitFor("status line shows the model name", 10_000) {
                        h.graph.assistant.statusLine.value == "pretend-model.gguf"
                    }
                    watcher.cancel()
                    println("[autoload] statusLine transitions=$seen")
                    assertTrue(
                        seen.any { it.contains("Loading the model", ignoreCase = true) },
                        "expected a loading banner, saw $seen",
                    )
                    assertEquals("pretend-model.gguf", seen.last())
                    assertEquals(1, engine.loadCount, "the model should be loaded exactly once")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `plan prompts carry the AssistantPlan schema and plain prompts do not`() {
        val dir = Files.createTempDirectory("todo-schema-flags").toFile()
        val engine = SlowFakeEngine(loadDelayMillis = 0, reply = """{"reply":"ok","ops":[]}""")
        try {
            persistLocalEngineSettings(dir, File(dir, "pretend-model.gguf").absolutePath)
            DesktopTestHarness.withGraph(localLlmEngine = engine, dir = dir, keepDir = true) { h ->
                runBlocking {
                    waitFor("assistant READY", 15_000) {
                        h.graph.assistant.mode.value == AssistantMode.READY
                    }
                    engine.optionsSeen.clear()

                    // The Settings "Test" probe: plain text, must not be grammar-constrained.
                    h.graph.assistant.testBackend()
                    waitFor("probe reached the engine", 15_000) { engine.optionsSeen.isNotEmpty() }
                    val probe = engine.optionsSeen.first()
                    println("[schema] probe jsonMode=${probe.jsonMode} schema=${probe.jsonSchema?.length}")
                    assertEquals(false, probe.jsonMode, "the connection probe must stay plain text")
                    assertEquals(null, probe.jsonSchema)

                    engine.optionsSeen.clear()
                    h.graph.assistant.send("add pick up kids today at 3pm")
                    waitFor("plan request reached the engine", 30_000) { engine.optionsSeen.isNotEmpty() }
                    val plan = engine.optionsSeen.first()
                    println("[schema] plan jsonMode=${plan.jsonMode} schemaChars=${plan.jsonSchema?.length}")
                    assertEquals(true, plan.jsonMode, "a plan request must be JSON-constrained")
                    val schema = plan.jsonSchema
                    assertTrue(!schema.isNullOrBlank(), "a plan request must carry the schema")
                    assertTrue(schema.contains("add_task"), "schema should enumerate the ops: $schema")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
