package dev.aliziwa.todo.desktop.llm

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaIterator
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import de.kherud.llama.Pair as LlamaPair
import dev.aliziwa.todo.desktop.DesktopLog
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LlmRole
import dev.aliziwa.todo.platform.LocalLlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeoutException

/**
 * On-device inference for macOS via `de.kherud:llama` (java-llama.cpp).
 *
 * Notes on the binding (API inspected from `llama-4.2.0-sources.jar`):
 *  - The jar ships `de/kherud/llama/Mac/aarch64/libjllama.dylib` and `LlamaLoader` extracts it to
 *    `java.io.tmpdir` on first class use, so nothing needs `-Djava.library.path` — including inside
 *    a packaged `.app`. `de.kherud.llama.tmpdir` can override the extraction directory.
 *  - Chat templates live in the gguf metadata. We ask the native side to render them:
 *    `InferenceParameters.setUseChatTemplate(true).setMessages(system, turns)` followed by
 *    `LlamaModel.applyTemplate(...)` returns the fully formatted prompt string. If a model has no
 *    template we fall back to a plain role-tagged prompt.
 *  - Grammar is exposed as `InferenceParameters.setGrammar(gbnf)`, which is how [GenerationOptions.jsonMode]
 *    is honoured (see [JSON_GBNF]). `LlamaModel.jsonSchemaToGrammar(schema)` is also available if the
 *    shared assistant ever wants a schema-exact grammar instead of "any JSON".
 *  - `LlamaIterator.cancel()` stops an in-flight completion; that backs [cancelGeneration].
 *
 * Every native entry point is guarded: a missing/corrupt model, an unsupported extension, a failed
 * native load (`UnsatisfiedLinkError`) or an OOM all land in [EngineState.Error] rather than killing
 * the app, which is what the assistant's failure policy expects.
 */
class LlamaCppEngine(
    @Suppress("unused") private val scope: CoroutineScope,
    private val contextSize: Int = DEFAULT_CTX,
    private val gpuLayers: Int = ALL_LAYERS_ON_METAL,
) : LocalLlmEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val supportedExtensions: List<String> = listOf("gguf")

    private val loadLock = Mutex()
    private val generateLock = Mutex()

    @Volatile private var model: LlamaModel? = null
    @Volatile private var activeIterator: LlamaIterator? = null
    private val grammarCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        loadLock.withLock {
            val file = File(modelPath)
            val extension = file.extension.lowercase()
            when {
                !file.isFile -> return@withLock fail("Model file not found: $modelPath")
                file.length() < MIN_MODEL_BYTES -> return@withLock fail(
                    "Model file looks truncated (${file.length()} bytes): ${file.name}",
                )
                extension !in supportedExtensions -> return@withLock fail(
                    "Unsupported model type '.$extension'; llama.cpp needs ${supportedExtensions.joinToString()}",
                )
            }

            _state.value = EngineState.Loading(null)
            unloadLocked()
            try {
                quietNativeLogs()
                val parameters = ModelParameters()
                    .setModel(file.absolutePath)
                    .setCtxSize(contextSize)
                    .setGpuLayers(gpuLayers)
                    .setThreads(inferenceThreads())
                    .setBatchSize(512)
                    .enableJinja()
                    .setLogVerbosity(0)
                val loaded = LlamaModel(parameters)
                model = loaded
                _state.value = EngineState.Ready(file.absolutePath, file.name)
                DesktopLog.i(TAG, "loaded ${file.name} (ctx=$contextSize, gpuLayers=$gpuLayers)")
                Result.success(Unit)
            } catch (t: Throwable) {
                // LlamaException, UnsatisfiedLinkError, OutOfMemoryError, ...
                val message = "Could not load ${file.name}: ${t.message ?: t::class.java.simpleName}"
                _state.value = EngineState.Error(message, t)
                DesktopLog.e(TAG, message, t)
                Result.failure(t)
            }
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            loadLock.withLock { unloadLocked() }
            _state.value = EngineState.NotLoaded
        }
    }

    override fun generate(messages: List<LlmMessage>, options: GenerationOptions): Flow<String> =
        channelFlow {
            generateLock.withLock {
                val loaded = model
                    ?: throw IllegalStateException("llama.cpp engine is not loaded")
                val prompt = buildPrompt(loaded, messages)
                val parameters = InferenceParameters(prompt)
                    .setNPredict(options.maxTokens)
                    .setTemperature(options.temperature)
                    .setTopP(options.topP)
                    .setCachePrompt(true)
                if (options.stopSequences.isNotEmpty()) {
                    parameters.setStopStrings(*options.stopSequences.toTypedArray())
                }
                // jsonMode off => plain text, no grammar at all (the Settings connection probe and
                // small talk must not be forced into JSON).
                if (options.jsonMode) {
                    parameters.setGrammar(grammarFor(options.jsonSchema))
                }

                val deadline = System.currentTimeMillis() + options.timeoutMillis
                val iterator = loaded.generate(parameters).iterator()
                activeIterator = iterator
                try {
                    while (iterator.hasNext()) {
                        ensureActive()
                        if (System.currentTimeMillis() > deadline) {
                            runCatching { iterator.cancel() }
                            throw TimeoutException("Generation exceeded ${options.timeoutMillis}ms")
                        }
                        val chunk = iterator.next().text
                        if (chunk.isNotEmpty()) send(chunk)
                    }
                } finally {
                    activeIterator = null
                    // If we are leaving early (cancellation/timeout) release the native task.
                    if (iterator.hasNext()) runCatching { iterator.cancel() }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Schema-constrained decoding when the shared layer supplies one.
     *
     * `LlamaModel.jsonSchemaToGrammar` is llama.cpp's own JSON-Schema -> GBNF converter (a static
     * native call, available as soon as the binding's class initialiser has extracted the dylib).
     * Converted grammars are cached per schema string: conversion is a JNI round trip and the
     * assistant sends the same `AssistantPlanSchema.STRICT` on every plan request.
     *
     * Anything that is not a JSON object, or that the converter cannot express, falls back to
     * [JSON_GBNF] ("any JSON") — still better than unconstrained decoding, and it keeps a schema
     * change from breaking generation.
     */
    private fun grammarFor(jsonSchema: String?): String {
        if (jsonSchema.isNullOrBlank()) return JSON_GBNF
        grammarCache[jsonSchema]?.let { return it }
        // The converter is C++: handing it something that is not even a JSON object aborts the
        // whole process (SIGABRT) instead of throwing, which no runCatching can save us from.
        // So pre-validate here and only cross the JNI boundary with a real JSON object.
        if (!isJsonObject(jsonSchema)) {
            DesktopLog.w(TAG, "jsonSchema is not a JSON object; using the generic JSON grammar")
            grammarCache[jsonSchema] = JSON_GBNF
            return JSON_GBNF
        }
        val grammar = runCatching { LlamaModel.jsonSchemaToGrammar(jsonSchema) }
            .mapCatching { it.also { g -> require(!g.isNullOrBlank()) { "empty grammar" } } }
            .getOrElse { error ->
                DesktopLog.w(TAG, "jsonSchemaToGrammar failed; using the generic JSON grammar", error)
                JSON_GBNF
            }
        grammarCache[jsonSchema] = grammar
        DesktopLog.i(
            TAG,
            if (grammar === JSON_GBNF) "schema rejected; generic JSON grammar in use"
            else "schema-constrained grammar built (${grammar.length} chars)",
        )
        return grammar
    }

    override fun cancelGeneration() {
        val iterator = activeIterator ?: return
        DesktopLog.i(TAG, "cancelling generation")
        runCatching { iterator.cancel() }
        activeIterator = null
    }

    // ------------------------------------------------------------------ internals

    private fun isJsonObject(candidate: String): Boolean = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(candidate)
        element is kotlinx.serialization.json.JsonObject
    }.getOrDefault(false)

    private fun fail(message: String): Result<Unit> {
        _state.value = EngineState.Error(message)
        DesktopLog.e(TAG, message)
        return Result.failure(IllegalArgumentException(message))
    }

    private fun unloadLocked() {
        activeIterator?.let { runCatching { it.cancel() } }
        activeIterator = null
        model?.let { m ->
            runCatching { m.close() }.onFailure { DesktopLog.w(TAG, "close() failed", it) }
        }
        model = null
    }

    /** Renders the loaded model's own chat template; falls back to a role-tagged prompt. */
    private fun buildPrompt(model: LlamaModel, messages: List<LlmMessage>): String {
        val system = messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content
        val turns = messages
            .filter { it.role != LlmRole.SYSTEM }
            .map { LlamaPair(it.role.wireName(), it.content) }
        if (turns.isEmpty()) return system.orEmpty()
        return runCatching {
            val templated = model.applyTemplate(
                InferenceParameters("")
                    .setUseChatTemplate(true)
                    .setMessages(system, turns),
            )
            require(!templated.isNullOrBlank()) { "empty template output" }
            templated
        }.getOrElse {
            DesktopLog.w(TAG, "model has no usable chat template; using a plain prompt", it)
            plainPrompt(system, messages)
        }
    }

    private fun plainPrompt(system: String?, messages: List<LlmMessage>): String = buildString {
        if (!system.isNullOrBlank()) append("System: ").append(system).append("\n\n")
        messages.filter { it.role != LlmRole.SYSTEM }.forEach { m ->
            append(if (m.role == LlmRole.USER) "User: " else "Assistant: ")
            append(m.content).append("\n\n")
        }
        append("Assistant: ")
    }

    private fun LlmRole.wireName(): String = when (this) {
        LlmRole.SYSTEM -> "system"
        LlmRole.USER -> "user"
        LlmRole.ASSISTANT -> "assistant"
    }

    private fun inferenceThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

    private fun quietNativeLogs() {
        runCatching {
            LlamaModel.setLogger(de.kherud.llama.args.LogFormat.TEXT) { level, message ->
                if (level == de.kherud.llama.LogLevel.ERROR) {
                    DesktopLog.w(TAG, "llama.cpp: ${message.trim()}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "llama"
        private const val DEFAULT_CTX = 4_096

        /** Offload everything to Metal; llama.cpp clamps this to the model's layer count. */
        private const val ALL_LAYERS_ON_METAL = 999
        private const val MIN_MODEL_BYTES = 1_000_000L

        /**
         * Standard llama.cpp `json.gbnf`: constrains output to any syntactically valid JSON value.
         * Used for [GenerationOptions.jsonMode] so the assistant's structured-output parse cannot
         * fail on prose or code fences.
         */
        val JSON_GBNF: String = """
root   ::= object
value  ::= object | array | string | number | ("true" | "false" | "null") ws

object ::=
  "{" ws (
            string ":" ws value
    ("," ws string ":" ws value)*
  )? "}" ws

array  ::=
  "[" ws (
            value
    ("," ws value)*
  )? "]" ws

string ::=
  "\"" (
    [^"\\\x7F\x00-\x1F] |
    "\\" (["\\bfnrt/] | "u" [0-9a-fA-F]{4})
  )* "\"" ws

number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9]{1,3})? ws

ws ::= | " " | "\n" [ \t]{0,20}
""".trimIndent()
    }
}
