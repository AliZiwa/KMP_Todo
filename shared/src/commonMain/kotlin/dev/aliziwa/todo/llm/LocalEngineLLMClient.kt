package dev.aliziwa.todo.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LlmRole
import dev.aliziwa.todo.platform.LocalLlmEngine
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/** The "provider" that identifies our on-device engines inside Koog. */
val LocalEngineProvider: LLMProvider = LLMProvider("local-engine", "On-device engine")

/**
 * Bridges the [LocalLlmEngine] platform port into Koog, so the exact same `AIAgent` code path runs
 * against llama.cpp (desktop/iOS), LiteRT-LM (Android) or Ollama.
 *
 * Tool calling is not supported by the local engines, so [execute] ignores tool descriptors and the
 * assistant relies on structured JSON output instead.
 */
class LocalEngineLLMClient(
    private val engine: LocalLlmEngine,
    private val defaults: GenerationOptions = GenerationOptions(),
    /**
     * JSON Schema handed to engines with schema-constrained decoding, for plan prompts only.
     * Supplied by [LlmRuntimeFactory] (ultimately `AssistantPlanSchema.STRICT`); null disables it.
     */
    private val planJsonSchema: String? = null,
) : LLMClient() {

    private val tag = "LocalEngineLLMClient"

    override val clientName: String get() = "LocalEngineLLMClient"

    override fun llmProvider(): LLMProvider = LocalEngineProvider

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        if (tools.isNotEmpty()) Log.w(tag, "on-device engine ignores ${tools.size} tool descriptor(s)")
        val chunks = engine.generate(prompt.toEngineMessages(), prompt.toOptions(model)).toList()
        val text = chunks.joinToString(separator = "").trim()
        return Message.Assistant(
            content = text,
            metaInfo = ResponseMetaInfo.create(KoogClock.System, modelId = model.id),
            finishReason = "stop",
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        engine.generate(prompt.toEngineMessages(), prompt.toOptions(model)).collect { chunk ->
            if (chunk.isNotEmpty()) emit(StreamFrame.TextDelta(chunk))
        }
        emit(
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo.create(KoogClock.System, modelId = model.id),
            )
        )
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("On-device engine does not support moderation")

    override fun close() {
        engine.cancelGeneration()
    }

    private fun Prompt.toEngineMessages(): List<LlmMessage> = messages.mapNotNull { message ->
        val role = when (message) {
            is Message.System -> LlmRole.SYSTEM
            is Message.User -> LlmRole.USER
            is Message.Assistant -> LlmRole.ASSISTANT
        }
        val content = message.textContent()
        if (content.isBlank()) null else LlmMessage(role, content)
    }

    /**
     * JSON/grammar mode is driven by the *model* we are asked to run, not by a global default:
     * plan requests use a model that declares [LLMCapability.Schema.JSON.Basic], while plain-text
     * prompts (the Settings connection probe, small talk) use one that does not. Forcing JSON on a
     * plain-text prompt makes grammar-constrained engines answer `{}`.
     */
    private fun Prompt.toOptions(model: LLModel): GenerationOptions {
        val jsonMode = model.supports(LLMCapability.Schema.JSON.Basic)
        return defaults.copy(
            temperature = params.temperature?.toFloat() ?: defaults.temperature,
            maxTokens = params.maxTokens ?: defaults.maxTokens,
            jsonMode = jsonMode,
            // Only a plan prompt gets a schema; a plain-text prompt must stay unconstrained.
            jsonSchema = if (jsonMode) planJsonSchema ?: defaults.jsonSchema else null,
        )
    }

    companion object {
        /**
         * Capabilities we honestly support: temperature + plain completion, plus a basic JSON
         * schema marker when [jsonMode] is on. No tools.
         */
        fun modelFor(modelName: String, jsonMode: Boolean = true): LLModel = LLModel(
            provider = LocalEngineProvider,
            id = modelName,
            capabilities = buildList {
                add(LLMCapability.Temperature)
                add(LLMCapability.Completion)
                if (jsonMode) add(LLMCapability.Schema.JSON.Basic)
            },
            contextLength = 4096L,
            maxOutputTokens = 1024L,
        )
    }
}
