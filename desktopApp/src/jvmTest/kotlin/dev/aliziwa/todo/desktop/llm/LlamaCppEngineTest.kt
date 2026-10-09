package dev.aliziwa.todo.desktop.llm

import dev.aliziwa.todo.assistant.AssistantPlanSchema
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LlmRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real inference against a small gguf, so the chat template, the JSON grammar and the streaming
 * Flow are all exercised against the actual native binding.
 *
 * The model is a 400MB download, so the test is **skipped with a printed note** when
 * `~/Library/Application Support/PersonalTodo/models/qwen2.5-0.5b-instruct-q4_k_m.gguf` is not
 * present. Fetch it with:
 *
 * ```
 * curl -L -o "$HOME/Library/Application Support/PersonalTodo/models/qwen2.5-0.5b-instruct-q4_k_m.gguf" \
 *   https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
 * ```
 */
class LlamaCppEngineTest {

    private val modelFile = File(
        System.getProperty("user.home"),
        "Library/Application Support/PersonalTodo/models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    )

    @Test
    fun `rejects the wrong file type without touching the native layer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(scope)
        try {
            val wrongType = File.createTempFile("model", ".litertlm").apply {
                writeBytes(ByteArray(2_000_000))
                deleteOnExit()
            }
            assertTrue(engine.load(wrongType.absolutePath).isFailure)
            val state = engine.state.value
            assertTrue(state is EngineState.Error, "expected Error, got $state")
            assertTrue((state as EngineState.Error).message.contains("litertlm"), state.message)
            assertEquals(listOf("gguf"), engine.supportedExtensions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing model file becomes an Error state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(scope)
        try {
            assertTrue(engine.load("/nope/not-here.gguf").isFailure)
            assertTrue(engine.state.value is EngineState.Error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the AssistantPlan schema becomes a llama_cpp grammar`() {
        // Static native converter; needs no loaded model, only the extracted dylib.
        val grammar = de.kherud.llama.LlamaModel.jsonSchemaToGrammar(AssistantPlanSchema.STRICT)
        println("[grammar] ${grammar.length} chars")
        assertTrue(grammar.isNotBlank(), "jsonSchemaToGrammar produced nothing")
        assertTrue(grammar.contains("root"), "a GBNF grammar needs a root rule:\n$grammar")
        assertTrue(grammar.contains("add_task"), "the op enum should survive into the grammar")
    }

    @Test
    fun `a non-JSON schema falls back to the generic grammar without crashing the process`() = runBlocking {
        if (!modelFile.isFile) {
            println("[skip] ${modelFile.name} not downloaded; see this test's KDoc")
            return@runBlocking
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(scope, contextSize = 1_024)
        try {
            assertTrue(engine.load(modelFile.absolutePath).isSuccess)
            val text = engine.generate(
                listOf(LlmMessage(LlmRole.USER, "Reply with a JSON object.")),
                GenerationOptions(
                    maxTokens = 48,
                    temperature = 0.0f,
                    jsonMode = true,
                    jsonSchema = "{ this is not json schema",
                    timeoutMillis = 120_000,
                ),
            ).toList().joinToString("").trim()
            // llama.cpp's JSON-Schema converter is C++ and aborts the process on non-JSON input,
            // so the engine must reject it in Kotlin before the JNI call. Reaching this line at all
            // is the assertion: a SIGABRT here would take the whole test JVM down.
            println("[grammar-fallback] $text")
            assertTrue(text.startsWith("{"), "should still be grammar-constrained JSON: $text")
        } finally {
            engine.unload()
            scope.cancel()
        }
    }

    @Test
    fun `jsonMode off leaves generation unconstrained`() = runBlocking {
        if (!modelFile.isFile) {
            println("[skip] ${modelFile.name} not downloaded; see this test's KDoc")
            return@runBlocking
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(scope, contextSize = 1_024)
        try {
            assertTrue(engine.load(modelFile.absolutePath).isSuccess)
            val text = engine.generate(
                listOf(LlmMessage(LlmRole.USER, "Reply with the single word OK.")),
                GenerationOptions(maxTokens = 16, temperature = 0.0f, jsonMode = false, timeoutMillis = 120_000),
            ).toList().joinToString("").trim()
            println("[plain] '$text'")
            assertTrue(text.isNotBlank(), "plain generation returned nothing")
            assertTrue(!text.startsWith("{"), "plain prompts must not be forced into JSON: '$text'")
        } finally {
            engine.unload()
            scope.cancel()
        }
    }

    @Test
    fun `loads a gguf and streams JSON for an add-task request`() = runBlocking {
        if (!modelFile.isFile) {
            println("[skip] ${modelFile.name} not downloaded; see this test's KDoc")
            return@runBlocking
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = LlamaCppEngine(scope, contextSize = 2_048)
        try {
            val loaded = engine.load(modelFile.absolutePath)
            assertTrue(loaded.isSuccess, "load failed: ${loaded.exceptionOrNull()}")
            val ready = engine.state.value
            assertTrue(ready is EngineState.Ready, "expected Ready, got $ready")

            val chunks = engine.generate(
                listOf(
                    LlmMessage(
                        LlmRole.SYSTEM,
                        "You convert a to-do request into JSON. Reply with ONLY a JSON object of the " +
                            "form {\"op\":\"AddTask\",\"title\":\"...\",\"time\":\"HH:MM\"}. No prose.",
                    ),
                    LlmMessage(LlmRole.USER, "add pick up kids at 3pm"),
                ),
                GenerationOptions(maxTokens = 128, temperature = 0.0f, jsonMode = true, timeoutMillis = 120_000),
            ).toList()

            assertTrue(chunks.isNotEmpty(), "engine streamed nothing")
            val text = chunks.joinToString("").trim()
            println("[llama] streamed ${chunks.size} chunks: $text")
            // The JSON GBNF grammar guarantees syntactic validity regardless of model quality.
            assertTrue(text.startsWith("{"), "grammar must force a JSON object, got: $text")
            assertTrue(text.contains("\""), "expected JSON keys, got: $text")
            // A 0.5B model is weak, but it should at least echo the subject.
            val lowered = text.lowercase()
            assertTrue(
                lowered.contains("kid") || lowered.contains("pick") || lowered.contains("addtask"),
                "unexpected content: $text",
            )
        } finally {
            engine.unload()
            assertTrue(engine.state.value is EngineState.NotLoaded)
            scope.cancel()
        }
    }
}
