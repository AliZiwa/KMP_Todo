package dev.aliziwa.todo.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanJsonTest {
    @Test
    fun parsesPlainJson() {
        val plan = PlanJson.parse("""{"reply":"ok","ops":[{"op":"add_task","title":"Gym"}]}""").getOrThrow()
        assertEquals("ok", plan.reply)
        assertEquals("Gym", plan.ops.single().title)
    }

    @Test
    fun stripsCodeFencesAndSurroundingProse() {
        val raw = """
            Sure! Here is the plan:
            ```json
            {"reply":"Added","ops":[{"op":"add_task","title":"Call mum","time":"18:00"}]}
            ```
            Let me know if that works.
        """.trimIndent()
        val plan = PlanJson.parse(raw).getOrThrow()
        assertEquals("18:00", plan.ops.single().time)
    }

    @Test
    fun ignoresUnknownKeys() {
        val plan = PlanJson.parse("""{"reply":"hi","hallucinated":42,"ops":[{"op":"chat","mood":"nice"}]}""")
            .getOrThrow()
        assertEquals(AssistantOps.CHAT, plan.ops.single().op)
    }

    @Test
    fun handlesNestedBracesInStrings() {
        val raw = """prefix {"reply":"use {braces}","ops":[]} suffix"""
        assertEquals("use {braces}", PlanJson.parse(raw).getOrThrow().reply)
    }

    @Test
    fun failsWhenThereIsNoJson() {
        assertTrue(PlanJson.parse("I have no idea what you mean.").isFailure)
        assertNull(PlanJson.extractJsonObject("nothing here"))
    }

    @Test
    fun failsOnUnbalancedJson() {
        assertTrue(PlanJson.parse("""{"reply":"oops","ops":[""").isFailure)
    }

    @Test
    fun emptyOpsIsValid() {
        val plan = PlanJson.parse("""{"reply":"Nothing to do"}""").getOrThrow()
        assertTrue(plan.ops.isEmpty())
    }
}
