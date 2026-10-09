package dev.aliziwa.todo.platform.ios

import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LocalLlmEngine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One chat turn, flattened for ObjC export (`role` is "system" | "user" | "assistant"). */
data class NativeLlmMessage(val role: String, val content: String)

/** Everything a native engine needs for one generation, flattened for ObjC export. */
data class NativeLlmRequest(
    val messages: List<NativeLlmMessage>,
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val jsonMode: Boolean,
    val stopSequences: List<String>,
    val timeoutMillis: Long,
)

/**
 * Implemented in Swift (`LlamaCppEngine`) and wrapped by [SwiftBackedLlmEngine].
 *
 * Contract:
 *  - Nothing here blocks the caller; all work happens on the implementation's own queue.
 *  - Callbacks may be invoked from any thread.
 *  - `completion`/`onFinished` are called EXACTLY once per call; a non-null string is an
 *    error message, `null` means success.
 */
interface NativeLlmBridge {
    /** e.g. `["gguf"]`. */
    fun supportedFileExtensions(): List<String>

    /** Human-readable model name once loaded (file name is a fine default). */
    fun modelDisplayName(): String?

    fun loadModel(path: String, completion: (String?) -> Unit)

    fun unloadModel()

    /**
     * Applies the model's chat template (llama.cpp `llama_chat_apply_template`), then streams
     * decoded text through [onToken] and calls [onFinished] once.
     */
    fun startGeneration(
        request: NativeLlmRequest,
        onToken: (String) -> Unit,
        onFinished: (String?) -> Unit,
    )

    fun cancelGeneration()
}

/**
 * Adapts a Swift [NativeLlmBridge] to the shared [LocalLlmEngine] port: owns the
 * [EngineState] machine and turns the native streaming callbacks into a Kotlin [Flow].
 *
 * Swift: `SwiftBackedLlmEngine(bridge: LlamaCppEngine())`.
 */
class SwiftBackedLlmEngine(private val bridge: NativeLlmBridge) : LocalLlmEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val supportedExtensions: List<String>
        get() = bridge.supportedFileExtensions()

    override suspend fun load(modelPath: String): Result<Unit> {
        _state.value = EngineState.Loading(null)
        return suspendCancellableCoroutine { cont ->
            bridge.loadModel(modelPath) { error ->
                if (error == null) {
                    val name = bridge.modelDisplayName() ?: modelPath.substringAfterLast('/')
                    _state.value = EngineState.Ready(modelPath, name)
                    if (cont.isActive) cont.resume(Result.success(Unit))
                } else {
                    _state.value = EngineState.Error(error)
                    if (cont.isActive) cont.resume(Result.failure(LocalLlmEngineException(error)))
                }
            }
            cont.invokeOnCancellation { bridge.cancelGeneration() }
        }
    }

    override suspend fun unload() {
        bridge.unloadModel()
        _state.value = EngineState.NotLoaded
    }

    override fun generate(messages: List<LlmMessage>, options: GenerationOptions): Flow<String> =
        callbackFlow {
            val request = NativeLlmRequest(
                messages = messages.map { NativeLlmMessage(it.role.name.lowercase(), it.content) },
                maxTokens = options.maxTokens,
                temperature = options.temperature,
                topP = options.topP,
                jsonMode = options.jsonMode,
                stopSequences = options.stopSequences,
                timeoutMillis = options.timeoutMillis,
            )
            bridge.startGeneration(
                request = request,
                onToken = { chunk -> trySend(chunk) },
                onFinished = { error ->
                    if (error == null) close() else close(LocalLlmEngineException(error))
                },
            )
            awaitClose { bridge.cancelGeneration() }
        }.buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

    override fun cancelGeneration() = bridge.cancelGeneration()
}

class LocalLlmEngineException(message: String) : RuntimeException(message)
