package dev.aliziwa.todo.desktop.llm

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
