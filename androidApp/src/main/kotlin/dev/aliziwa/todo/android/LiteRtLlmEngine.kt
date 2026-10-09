package dev.aliziwa.todo.android

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LlmRole
import dev.aliziwa.todo.platform.LocalLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * On-device inference via LiteRT-LM (`com.google.ai.edge.litertlm`).
 *
 * Why this library and not Koog's `prompt-executor-litert-client`: that artifact only exists as
 * `1.3.0-beta` (there is no `1.3.0`), and it is itself a thin wrapper over this same AAR. Going
 * direct keeps the version matrix honest and gives access to `Conversation`, which applies the
 * model's own chat template - something the raw `Session` API does not do.
 *
 * Failure handling (all surface as [EngineState.Error], never as a crash):
 *  - model file missing / wrong extension  -> rejected before touching native code
 *  - `UnsatisfiedLinkError`                -> no native lib for this ABI
 *  - `OutOfMemoryError`                    -> model too big for the device
 *  - `LiteRtLmJniException`                -> corrupt or unsupported `.litertlm`
 */
class LiteRtLlmEngine(context: Context) : LocalLlmEngine {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "litertlm").apply { mkdirs() }

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** `.task` is the older MediaPipe/LLM-Inference bundle; LiteRT-LM reads both. */
    override val supportedExtensions: List<String> = listOf("litertlm", "task")

    private val loadLock = Mutex()
    private var engine: Engine? = null

    @Volatile
    private var activeConversation: Conversation? = null

    override suspend fun load(modelPath: String): Result<Unit> = loadLock.withLock {
        val file = File(modelPath)
        if (!file.isFile) {
            return@withLock fail("Model file not found: $modelPath")
        }
        if (file.extension.lowercase() !in supportedExtensions) {
            return@withLock fail(
                "Android needs a LiteRT-LM model (${supportedExtensions.joinToString("/") { ".$it" }}); got .${file.extension}",
            )
        }
        if (file.length() < 1024) {
            return@withLock fail("Model file looks truncated (${file.length()} bytes) - re-download it")
        }
        headerProblem(file)?.let { return@withLock fail(it) }

        unloadInternal()
        _state.value = EngineState.Loading(null)

        return@withLock withContext(Dispatchers.IO) {
            try {
                val created = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU(),
                        maxNumTokens = MAX_CONTEXT_TOKENS,
                        cacheDir = cacheDir.absolutePath,
                    ),
                )
                created.initialize()
                engine = created
                _state.value = EngineState.Ready(modelPath, file.name)
                Log.i(TAG, "loaded ${file.name}")
                Result.success(Unit)
            } catch (c: CancellationException) {
                _state.value = EngineState.NotLoaded
                throw c
            } catch (t: Throwable) {
                // Deliberately Throwable: UnsatisfiedLinkError and OutOfMemoryError are Errors.
                engine = null
                val message = describeLoadFailure(t, file)
                Log.e(TAG, "load failed: $message", t)
                _state.value = EngineState.Error(message, t)
                Result.failure(t)
            }
        }
    }

    override suspend fun unload() = loadLock.withLock {
        unloadInternal()
        _state.value = EngineState.NotLoaded
    }

    override fun generate(messages: List<LlmMessage>, options: GenerationOptions): Flow<String> =
        flow {
            val eng = engine
                ?: throw IllegalStateException("No on-device model loaded")
            if (messages.none { it.role != LlmRole.SYSTEM }) {
                throw IllegalArgumentException("generate() needs at least one user message")
            }

            val system = messages.filter { it.role == LlmRole.SYSTEM }
                .joinToString("\n\n") { it.content }
                .trim()
            val turns = messages.filter { it.role != LlmRole.SYSTEM }
            val history = turns.dropLast(1).map { it.toLiteRt() }
            val prompt = turns.last().content

            val sampler = SamplerConfig(
                /* topK = */ DEFAULT_TOP_K,
                /* topP = */ options.topP.toDouble(),
                /* temperature = */ options.temperature.toDouble(),
                /* seed = */ 0,
            )

            // Schema-constrained decoding. Building the ResponseFormat can throw if LiteRT-LM
            // dislikes the schema, so it is attempted defensively; a null format means ordinary
            // unconstrained generation and the shared layer's tolerant JSON parser takes over.
            val requested = options.jsonSchema?.takeIf { options.jsonMode && it.isNotBlank() }
            var format = requested?.let { schema ->
                runCatching { ResponseFormat.json(schema) }
                    .onFailure { Log.w(TAG, "LiteRT rejected the JSON schema; generating unconstrained", it) }
                    .getOrNull()
            }

            while (true) {
                val conversation = eng.createConversation(
                    conversationConfig(
                        system = system,
                        history = history,
                        sampler = sampler,
                        maxTokens = options.maxTokens,
                        responseFormat = format != null,
                    ),
                )
                activeConversation = conversation
                var emittedAny = false
                try {
                    var emitted = ""
                    withTimeout(options.timeoutMillis) {
                        conversation.sendMessageAsync(Contents.of(prompt), responseFormat = format)
                            .collect { message ->
                                val full = message.text()
                                // LiteRT-LM's streaming Messages are cumulative in practice, but
                                // tolerate delta-style emissions rather than duplicating the answer.
                                val chunk = when {
                                    full.length >= emitted.length && full.startsWith(emitted) -> {
                                        val c = full.substring(emitted.length)
                                        emitted = full
                                        c
                                    }
                                    else -> {
                                        emitted += full
                                        full
                                    }
                                }
                                if (chunk.isNotEmpty()) {
                                    val stop = options.stopSequences.firstOrNull { chunk.contains(it) }
                                    if (stop != null) {
                                        emittedAny = true
                                        emit(chunk.substringBefore(stop))
                                        conversation.cancelProcess()
                                        return@collect
                                    }
                                    emittedAny = true
                                    emit(chunk)
                                }
                            }
                    }
                    return@flow
                } catch (t: TimeoutCancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw IllegalStateException(
                        "On-device generation timed out after ${options.timeoutMillis} ms",
                        t,
                    )
                } catch (c: CancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw c
                } catch (t: Throwable) {
                    runCatching { conversation.cancelProcess() }
                    // A constrained run that failed before producing anything is retried once
                    // unconstrained: a schema the runtime cannot compile must not cost the user
                    // their answer. Once text has been emitted, retrying would duplicate it.
                    if (format != null && !emittedAny) {
                        Log.w(TAG, "constrained generation failed; retrying unconstrained", t)
                        format = null
                        continue
                    }
                    val message = "On-device generation failed: ${t.message ?: t::class.java.simpleName}"
                    Log.e(TAG, message, t)
                    _state.value = EngineState.Error(message, t)
                    throw IllegalStateException(message, t)
                } finally {
                    activeConversation = null
                    runCatching { conversation.close() }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * [ConversationConfig] has no nullable `systemInstruction`, so the two shapes are built
     * explicitly rather than passing an empty [Contents].
     */
    private fun conversationConfig(
        system: String,
        history: List<Message>,
        sampler: SamplerConfig,
        maxTokens: Int,
        responseFormat: Boolean,
    ): ConversationConfig = if (system.isEmpty()) {
        ConversationConfig(
            initialMessages = history,
            samplerConfig = sampler,
            maxOutputToken = maxTokens,
            enableResponseFormat = responseFormat,
        )
    } else {
        ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = history,
            samplerConfig = sampler,
            maxOutputToken = maxTokens,
            enableResponseFormat = responseFormat,
        )
    }

    override fun cancelGeneration() {
        runCatching { activeConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelGeneration failed", it) }
    }

    // ------------------------------------------------------------ internals

    private fun unloadInternal() {
        runCatching { activeConversation?.cancelProcess() }
        activeConversation = null
        engine?.let { e ->
            runCatching { e.close() }.onFailure { Log.w(TAG, "engine close failed", it) }
        }
        engine = null
    }

    /**
     * Rejects a file whose container header is wrong BEFORE handing it to native code.
     *
     * This is not belt-and-braces, it is load-bearing: LiteRT-LM 0.18.0 reports a bad container by
     * building the message "Invalid magic number or failed to read: <raw file bytes>" and passing
     * it to JNI ThrowNew. Those bytes are not Modified UTF-8, so ART kills the whole process with
     * "JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8" - an abort that no
     * try/catch can intercept. Reproduced on API 37 with a 4 KB random file. Checking the magic
     * ourselves turns the realistic case (user pasted a wrong or half-written download URL) into
     * an ordinary [EngineState.Error].
     *
     * Magics: ".litertlm" starts with the ASCII tag LITERTLM; ".task" is a zip bundle (PK 03 04).
     */
    private fun headerProblem(file: File): String? = try {
        val head = ByteArray(8)
        val read = file.inputStream().use { it.read(head) }
        val ok = when (file.extension.lowercase()) {
            "litertlm" -> read == 8 && head.decodeToString() == LITERTLM_MAGIC
            "task" -> read >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
            else -> false
        }
        if (ok) {
            null
        } else {
            "${file.name} is not a valid LiteRT-LM model (bad container header) - re-download it"
        }
    } catch (t: Throwable) {
        "Could not read ${file.name}: ${t.message ?: t::class.java.simpleName}"
    }

    private fun fail(message: String): Result<Unit> {
        Log.w(TAG, message)
        _state.value = EngineState.Error(message)
        return Result.failure(IllegalArgumentException(message))
    }

    private fun describeLoadFailure(t: Throwable, file: File): String = when (t) {
        is UnsatisfiedLinkError ->
            "LiteRT-LM has no native library for this device (${android.os.Build.SUPPORTED_ABIS.joinToString()}). " +
                "The AAR ships arm64-v8a and x86_64 only."
        is OutOfMemoryError ->
            "Out of memory loading ${file.name} (${file.length() / (1024 * 1024)} MB). Try a smaller model."
        else -> "Could not load ${file.name}: ${t.message ?: t::class.java.simpleName}"
    }

    private companion object {
        const val TAG = "TodoLiteRt"
        const val MAX_CONTEXT_TOKENS = 2048
        const val DEFAULT_TOP_K = 40
        const val LITERTLM_MAGIC = "LITERTLM"
    }
}

/** The public `Message` constructor is internal to the AAR; use its role factories. */
private fun LlmMessage.toLiteRt(): Message = when (role) {
    LlmRole.ASSISTANT -> Message.model(Contents.of(content))
    LlmRole.SYSTEM -> Message.system(Contents.of(content))
    LlmRole.USER -> Message.user(Contents.of(content))
}

private fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
