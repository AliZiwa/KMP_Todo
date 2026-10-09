package dev.aliziwa.todo.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A ready-to-use Koog executor plus the model descriptors it expects.
 *
 * [model] declares a JSON schema capability and is used for plan requests; [plainModel] does not and
 * is used for plain-text prompts (connection probe, small talk). On-device engines turn that into
 * grammar-constrained vs free generation.
 */
class LlmRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    val plainModel: LLModel,
    val backend: LlmBackend,
    val label: String,
) : AutoCloseable {
    override fun close() {
        runCatching { executor.close() }
    }
}

/** Why the assistant cannot use an LLM right now. */
sealed interface LlmUnavailable {
    data object RulesOnlySelected : LlmUnavailable
    data object NoLocalEngine : LlmUnavailable
    data object NoModelConfigured : LlmUnavailable

    /** The model file is being loaded right now; retrying shortly will work. */
    data class EngineLoading(val progress: Float?) : LlmUnavailable
    data class EngineError(val message: String) : LlmUnavailable
}

/**
 * Builds (and caches) the Koog executor for the current settings. Everything is local:
 * on-device engine, or a user-configured Ollama / OpenAI-compatible endpoint on the LAN.
 *
 * For [LlmBackend.LOCAL_ENGINE] this also owns *loading* the configured model file, so a restart
 * with a model already chosen does not need the user to press anything in Settings.
 */
class LlmRuntimeFactory(
    private val platform: PlatformServices,
    /**
     * JSON Schema for the assistant plan, passed to on-device engines that can constrain decoding.
     * Injected (rather than imported) so this package stays independent of the assistant layer;
     * `AppGraph` supplies `AssistantPlanSchema.STRICT`.
     */
    private val planJsonSchema: String? = null,
) {
    private val tag = "LlmRuntimeFactory"
    private var cached: LlmRuntime? = null
    private var cacheKey: String? = null
    private val loadLock = Mutex()
    private var lastLoadFailure: String? = null
    private var lastLoadedPath: String? = null

    fun engineAvailable(): Boolean = platform.localLlmEngine != null

    fun localEngineState(): EngineState? = platform.localLlmEngine?.state?.value

    /**
     * Returns the runtime, or an explanation of why there is none. Suspends while an on-device
     * model is loading, because the caller (a chat send, or the startup status refresh) genuinely
     * needs it ready.
     */
    suspend fun resolve(settings: AppSettings): Result<LlmRuntime> {
        if (!settings.assistantEnabled) {
            return Result.failure(LlmUnavailableException(LlmUnavailable.RulesOnlySelected))
        }

        return when (settings.llmBackend) {
            LlmBackend.RULES_ONLY -> Result.failure(LlmUnavailableException(LlmUnavailable.RulesOnlySelected))
            LlmBackend.LOCAL_ENGINE -> resolveLocalEngine(settings)
            LlmBackend.OLLAMA -> resolveOllama(settings)
        }
    }

    private suspend fun resolveLocalEngine(settings: AppSettings): Result<LlmRuntime> {
        val engine = platform.localLlmEngine
            ?: return Result.failure(LlmUnavailableException(LlmUnavailable.NoLocalEngine))
        val path = settings.localModelPath
            ?: return Result.failure(LlmUnavailableException(LlmUnavailable.NoModelConfigured))

        // The engine state is authoritative — never hand back a cached runtime for an engine that
        // has since errored, unloaded, or been pointed at another file.
        ensureLoaded(path)?.let { return Result.failure(LlmUnavailableException(it)) }

        val key = cacheKeyFor(settings)
        cached?.let { if (cacheKey == key) return Result.success(it) }
        close()

        val modelName = path.substringAfterLast('/').substringAfterLast('\\')
        val runtime = LlmRuntime(
            executor = MultiLLMPromptExecutor(
                LocalEngineLLMClient(engine, planJsonSchema = planJsonSchema)
            ),
            model = LocalEngineLLMClient.modelFor(modelName, jsonMode = true),
            plainModel = LocalEngineLLMClient.modelFor(modelName, jsonMode = false),
            backend = LlmBackend.LOCAL_ENGINE,
            label = modelName,
        )
        cached = runtime
        cacheKey = key
        Log.i(tag, "local engine runtime for $modelName")
        return Result.success(runtime)
    }

    /** Loads [path] if the engine is not already holding it. Returns null when ready. */
    private suspend fun ensureLoaded(path: String): LlmUnavailable? {
        val engine = platform.localLlmEngine ?: return LlmUnavailable.NoLocalEngine

        (engine.state.value as? EngineState.Ready)?.let { ready ->
            if (ready.modelPath == path) return null
        }

        return loadLock.withLock {
            // Re-check under the lock: another send may have loaded it while we waited.
            (engine.state.value as? EngineState.Ready)?.let { ready ->
                if (ready.modelPath == path) return@withLock null
            }
            if (lastLoadFailure != null && lastLoadedPath == path) {
                return@withLock LlmUnavailable.EngineError(lastLoadFailure!!)
            }

            Log.i(tag, "loading on-device model $path")
            lastLoadedPath = path
            val result = runCatching { engine.load(path) }
                .getOrElse { Result.failure(it) }

            result.fold(
                onSuccess = {
                    lastLoadFailure = null
                    null
                },
                onFailure = { error ->
                    val message = error.message ?: error::class.simpleName ?: "could not load the model"
                    lastLoadFailure = message
                    Log.w(tag, "model load failed: $message", error)
                    LlmUnavailable.EngineError(message)
                },
            )
        } ?: run {
            // Some engines report readiness asynchronously; reflect whatever state they are in now.
            when (val state = engine.state.value) {
                is EngineState.Ready -> null
                is EngineState.Loading -> LlmUnavailable.EngineLoading(state.progress)
                is EngineState.Error -> LlmUnavailable.EngineError(state.message)
                EngineState.NotLoaded -> null
            }
        }
    }

    private fun resolveOllama(settings: AppSettings): Result<LlmRuntime> {
        val key = cacheKeyFor(settings)
        cached?.let { if (cacheKey == key) return Result.success(it) }
        close()

        val factory = KtorKoogHttpClient.Factory(
            baseClient = platform.http.createHttpClient(),
            withSse = false,
        )
        val client = OllamaClient(
            httpClientFactory = factory,
            baseUrl = settings.ollamaBaseUrl.trim().trimEnd('/'),
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = settings.generationTimeoutMillis,
                connectTimeoutMillis = 10_000,
                socketTimeoutMillis = settings.generationTimeoutMillis,
            ),
        )
        // Ollama does its own JSON handling; the same descriptor serves both call kinds.
        val model = LLModel(
            provider = LLMProvider.Ollama,
            id = settings.ollamaModel.trim(),
            capabilities = listOf(LLMCapability.Temperature, LLMCapability.Completion),
            contextLength = 8192L,
            maxOutputTokens = 1024L,
        )
        val runtime = LlmRuntime(
            executor = MultiLLMPromptExecutor(client),
            model = model,
            plainModel = model,
            backend = LlmBackend.OLLAMA,
            label = "${settings.ollamaModel} @ ${settings.ollamaBaseUrl}",
        )
        cached = runtime
        cacheKey = key
        Log.i(tag, "ollama runtime for ${runtime.label}")
        return Result.success(runtime)
    }

    /** Lets the user retry after fixing a bad model file / picking another one. */
    fun clearLoadFailure() {
        lastLoadFailure = null
        lastLoadedPath = null
    }

    fun close() {
        cached?.close()
        cached = null
        cacheKey = null
    }

    private fun cacheKeyFor(s: AppSettings): String =
        "${s.llmBackend}|${s.localModelPath}|${s.ollamaBaseUrl}|${s.ollamaModel}|${s.generationTimeoutMillis}"
}

class LlmUnavailableException(val reason: LlmUnavailable) : Exception(reason.describe())

fun LlmUnavailable.describe(): String = when (this) {
    LlmUnavailable.RulesOnlySelected -> "Assistant set to rules-only"
    LlmUnavailable.NoLocalEngine -> "This platform has no on-device engine yet"
    LlmUnavailable.NoModelConfigured -> "No model selected — pick one in Settings"
    is LlmUnavailable.EngineLoading ->
        "Loading the model" + (progress?.let { " — ${(it * 100).toInt()}%" } ?: "…")
    is LlmUnavailable.EngineError -> "Model could not be loaded: $message"
}
