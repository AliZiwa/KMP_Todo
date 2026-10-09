package dev.aliziwa.todo.llm

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import dev.aliziwa.todo.assistant.AssistantPlanSchema
import dev.aliziwa.todo.fakes.FakeLocalLlmEngine
import dev.aliziwa.todo.platform.LlmRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The on-device engine must only be put into JSON/grammar mode for plan prompts. A llama.cpp or
 * LiteRT-LM engine forced into JSON mode for the Settings probe answers `{}`, which previously
 * looked like a passing connection test.
 */
class LocalEngineLLMClientTest {

    private fun prompt(system: String, user: String) = Prompt(
        messages = listOf(
            Message.System(system, RequestMetaInfo.Empty),
            Message.User(user, RequestMetaInfo.Empty),
        ),
        id = "test",
        params = LLMParams(temperature = 0.2),
    )

    @Test
    fun planModelTurnsOnJsonModeAndCarriesTheSchema() = runTest {
        val engine = FakeLocalLlmEngine(reply = """{"reply":"ok","ops":[]}""")
        val client = LocalEngineLLMClient(engine, planJsonSchema = AssistantPlanSchema.STRICT)
        val model = LocalEngineLLMClient.modelFor("qwen.gguf", jsonMode = true)

        client.execute(prompt("plan schema here", "gym tomorrow"), model, emptyList())

        val (messages, options) = engine.calls.single()
        assertTrue(options.jsonMode)
        assertEquals(AssistantPlanSchema.STRICT, options.jsonSchema)
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER), messages.map { it.role })
        assertEquals(0.2f, options.temperature)
    }

    @Test
    fun plainModelLeavesJsonModeOffAndSendsNoSchema() = runTest {
        val engine = FakeLocalLlmEngine(reply = "OK")
        val client = LocalEngineLLMClient(engine, planJsonSchema = AssistantPlanSchema.STRICT)
        val model = LocalEngineLLMClient.modelFor("qwen.gguf", jsonMode = false)

        val reply = client.execute(prompt("connection test", "ping"), model, emptyList())

        val options = engine.calls.single().second
        assertFalse(options.jsonMode)
        assertNull(options.jsonSchema)
        assertEquals("OK", reply.textContent())
    }

    @Test
    fun blankMessagesAreDroppedBeforeReachingTheEngine() = runTest {
        val engine = FakeLocalLlmEngine()
        val client = LocalEngineLLMClient(engine)
        val model = LocalEngineLLMClient.modelFor("qwen.gguf")

        client.execute(prompt("   ", "hello"), model, emptyList())

        assertEquals(listOf(LlmRole.USER), engine.calls.single().first.map { it.role })
    }

    @Test
    fun closingTheClientCancelsAnyGeneration() {
        val engine = FakeLocalLlmEngine()
        LocalEngineLLMClient(engine).close()
        assertEquals(1, engine.cancelCount)
    }
}
