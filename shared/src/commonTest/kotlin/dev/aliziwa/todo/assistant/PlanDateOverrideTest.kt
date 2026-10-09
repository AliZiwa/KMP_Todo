package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Small models put "today at 3pm" on tomorrow. When the user's own words pin a date or time down,
 * those words win over the model and the correction is surfaced as a warning on the plan card.
 */
class PlanDateOverrideTest {
    private val zone = TimeZone.of("UTC")
    private val resolver = PlanResolver { zone }
    private val today = LocalDate(2026, 10, 9)
    private val tomorrow = LocalDate(2026, 10, 10)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)

    private val snapshot = TaskSnapshot(
        tasks = listOf(
            Task(
                id = "t1", title = "Buy milk", scope = TaskScope.DAY, scheduledDate = today,
                createdAt = 1, updatedAt = 1,
            )
        )
    )

    private fun addOp(date: String? = null, time: String? = null, title: String = "Dentist") =
        AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.ADD, title = title, scope = "day", date = date, time = time)))

    @Test
    fun theWordTodayOverridesAModelThatSaidTomorrow() {
        val resolved = resolver.resolve(
            addOp(date = tomorrow.toString(), time = "15:00"),
            snapshot, now, PlanSource.LLM, userText = "dentist today at 3pm",
        )
        val add = resolved.actions.single() as PlannedAction.Add
        assertEquals(today, add.draft.date)
        assertEquals(15 * 60, add.draft.timeMinutes)
        assertTrue(resolved.warnings.any { it.contains("Adjusted the date") && it.contains("today") })
    }

    @Test
    fun theWordTomorrowOverridesAModelThatSaidToday() {
        val resolved = resolver.resolve(
            addOp(date = today.toString()),
            snapshot, now, PlanSource.LLM, userText = "dentist tomorrow",
        )
        assertEquals(tomorrow, (resolved.actions.single() as PlannedAction.Add).draft.date)
        assertEquals(1, resolved.warnings.size)
    }

    @Test
    fun anExplicitTimeOverridesTheModelsTime() {
        val resolved = resolver.resolve(
            addOp(date = today.toString(), time = "09:00"),
            snapshot, now, PlanSource.LLM, userText = "dentist today at 3pm",
        )
        val add = resolved.actions.single() as PlannedAction.Add
        assertEquals(15 * 60, add.draft.timeMinutes)
        assertTrue(resolved.warnings.any { it.contains("Adjusted the time to 15:00") })
    }

    @Test
    fun aMissingModelDateIsFilledFromTheKeyword() {
        val resolved = resolver.resolve(
            addOp(date = null, time = null),
            snapshot, now, PlanSource.LLM, userText = "dentist tonight",
        )
        val add = resolved.actions.single() as PlannedAction.Add
        assertEquals(today, add.draft.date)
        assertEquals(20 * 60, add.draft.timeMinutes)
    }

    @Test
    fun agreementProducesNoWarning() {
        val resolved = resolver.resolve(
            addOp(date = today.toString(), time = "15:00"),
            snapshot, now, PlanSource.LLM, userText = "dentist today at 3pm",
        )
        assertTrue(resolved.warnings.none { it.contains("Adjusted the time") })
    }

    @Test
    fun noKeywordMeansTheModelIsTrusted() {
        val resolved = resolver.resolve(
            addOp(date = tomorrow.toString(), time = "15:00"),
            snapshot, now, PlanSource.LLM, userText = "dentist appointment",
        )
        val add = resolved.actions.single() as PlannedAction.Add
        assertEquals(tomorrow, add.draft.date)
        assertEquals(15 * 60, add.draft.timeMinutes)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun weekdayKeywordsAlsoOverride() {
        val resolved = resolver.resolve(
            addOp(date = today.toString()),
            snapshot, now, PlanSource.LLM, userText = "haircut on monday",
        )
        assertEquals(LocalDate(2026, 10, 12), (resolved.actions.single() as PlannedAction.Add).draft.date)
    }

    @Test
    fun updatesAreCorrectedToo() {
        val plan = AssistantPlan(
            ops = listOf(
                AssistantOp(op = AssistantOps.UPDATE, taskId = "t1", title = "Buy milk", date = tomorrow.toString())
            )
        )
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM, userText = "move buy milk to today")
        assertEquals(today, (resolved.actions.single() as PlannedAction.Update).draft.date)
    }

    @Test
    fun aMultiTaskMessageIsNotOverridden() {
        // One "today" cannot be attributed to a particular task, so the model keeps its dates.
        val plan = AssistantPlan(
            ops = listOf(
                AssistantOp(op = AssistantOps.ADD, title = "Buy milk", scope = "day", date = today.toString()),
                AssistantOp(op = AssistantOps.ADD, title = "Walk dog", scope = "day", date = tomorrow.toString()),
            )
        )
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM, userText = "buy milk today and walk dog tomorrow")
        assertEquals(today, (resolved.actions[0] as PlannedAction.Add).draft.date)
        assertEquals(tomorrow, (resolved.actions[1] as PlannedAction.Add).draft.date)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun ruleBasedPlansAreNotSecondGuessed() {
        val resolved = resolver.resolve(
            addOp(date = tomorrow.toString()),
            snapshot, now, PlanSource.RULES, userText = "dentist today",
        )
        assertEquals(tomorrow, (resolved.actions.single() as PlannedAction.Add).draft.date)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun withoutUserTextNothingIsOverridden() {
        val resolved = resolver.resolve(addOp(date = tomorrow.toString()), snapshot, now, PlanSource.LLM)
        assertEquals(tomorrow, (resolved.actions.single() as PlannedAction.Add).draft.date)
    }
}
