package dev.aliziwa.todo.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GenerationOptions.jsonSchema` is fed to llama.cpp's `jsonSchemaToGrammar` and LiteRT-LM's
 * `ResponseFormat.json(...)`, so it has to be genuinely valid JSON Schema, not prompt prose.
 */
class AssistantPlanSchemaTest {
    private val parsed = Json.parseToJsonElement(AssistantPlanSchema.STRICT).jsonObject

    @Test
    fun theSchemaIsValidJson() {
        assertEquals("object", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed.containsKey("properties"))
    }

    @Test
    fun itDescribesReplyAndOps() {
        val properties = parsed["properties"]!!.jsonObject
        assertEquals("string", properties["reply"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", properties["ops"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = parsed["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("reply", "ops"), required)
    }

    @Test
    fun opIsAnEnumOfEveryKnownOperation() {
        val opProperty = parsed["properties"]!!.jsonObject["ops"]!!.jsonObject["items"]!!
            .jsonObject["properties"]!!.jsonObject["op"]!!.jsonObject
        assertEquals("string", opProperty["type"]!!.jsonPrimitive.content)
        val values = opProperty["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(AssistantOps.all, values)
    }

    @Test
    fun opIsTheOnlyRequiredFieldOfAnOperation() {
        val items = parsed["properties"]!!.jsonObject["ops"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(listOf("op"), items["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun priorityAndScopeAreConstrained() {
        val properties = parsed["properties"]!!.jsonObject["ops"]!!.jsonObject["items"]!!
            .jsonObject["properties"]!!.jsonObject
        assertEquals(
            listOf("low", "medium", "high"),
            properties["priority"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("day", "week", "month", "recurring_daily"),
            properties["scope"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("integer", properties["minutes"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRealPlanValidatesAgainstTheAdvertisedShape() {
        // Round-trip sanity: what we tell the engine to produce is what we can actually decode.
        val sample = """{"reply":"Adding gym.","ops":[{"op":"add_task","title":"Gym","scope":"day","time":"07:00"}]}"""
        val plan = PlanJson.parse(sample).getOrThrow()
        assertEquals(AssistantOps.ADD, plan.ops.single().op)
    }
}
