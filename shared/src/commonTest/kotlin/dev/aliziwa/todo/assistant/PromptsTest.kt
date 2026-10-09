package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptsTest {
    private val zone = TimeZone.of("UTC")
    private val today = LocalDate(2026, 10, 9)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)
    private val prompt = Prompts.system(today, now, zone, emptyList())

    @Test
    fun opsAreOfferedAsAJsonEnumNotAPipePlaceholder() {
        assertTrue(Prompts.JSON_SCHEMA.contains("\"enum\""))
        AssistantOps.all.forEach { op ->
            assertTrue(Prompts.JSON_SCHEMA.contains("\"$op\""), "schema is missing $op")
        }
        // The old "a | b | c" placeholder is exactly what small models copied verbatim, so it must
        // not appear in the schema at all — only in the rule that calls it invalid.
        assertFalse(Prompts.JSON_SCHEMA.contains("add_task | update_task"))
        assertFalse(Prompts.EXAMPLES.contains("add_task | update_task"))
        assertTrue(prompt.contains("\"add_task | update_task\" is invalid"))
    }

    @Test
    fun theSystemPromptCarriesConcreteExamples() {
        assertTrue(prompt.contains("\"op\":\"add_task\""))
        assertTrue(prompt.contains("\"op\":\"complete_task\""))
        assertTrue(prompt.contains("\"ops\":[]"))
    }

    @Test
    fun theSystemPromptForbidsCopyingTheSchema() {
        assertTrue(prompt.contains("Never copy schema text"))
    }

    @Test
    fun theSystemPromptStatesTodayAndTomorrow() {
        assertTrue(prompt.contains("2026-10-09"))
        assertTrue(prompt.contains("2026-10-10"))
        assertTrue(prompt.contains(zone.id))
    }

    @Test
    fun priorityAndScopeAreEnumsToo() {
        assertTrue(Prompts.JSON_SCHEMA.contains("\"recurring_daily\""))
        assertTrue(Prompts.JSON_SCHEMA.contains("\"high\""))
    }
}
