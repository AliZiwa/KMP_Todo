package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanResolverTest {
    private val zone = TimeZone.of("UTC")
    private val resolver = PlanResolver { zone }
    private val today = LocalDate(2026, 10, 9)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)

    private val snapshot = TaskSnapshot(
        tasks = listOf(
            Task(
                id = "t1", title = "Buy milk", scope = TaskScope.DAY, scheduledDate = today,
                createdAt = 1, updatedAt = 1,
            ),
            Task(
                id = "t2", title = "Submit expense report", scope = TaskScope.DAY, scheduledDate = today,
                dueAt = DateMath.atMinutes(today, 15 * 60, zone), createdAt = 2, updatedAt = 2,
            ),
        )
    )

    @Test
    fun addBecomesADraftWithScopeDateAndTime() {
        val plan = AssistantPlan(
            reply = "Sure",
            ops = listOf(
                AssistantOp(
                    op = AssistantOps.ADD, title = "Gym", scope = "day",
                    date = "2026-10-10", time = "07:00", priority = "high",
                )
            ),
        )
        val action = resolver.resolve(plan, snapshot, now, PlanSource.LLM).actions.single()
        assertTrue(action is PlannedAction.Add)
        val draft = action.draft
        assertEquals("Gym", draft.title)
        assertEquals(LocalDate(2026, 10, 10), draft.date)
        assertEquals(7 * 60, draft.timeMinutes)
        assertEquals(Priority.HIGH, draft.priority)
        assertTrue(action.summary.contains("07:00"))
    }

    @Test
    fun completeMatchesByExactTitle() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.COMPLETE, title = "buy milk")))
        val action = resolver.resolve(plan, snapshot, now, PlanSource.LLM).actions.single()
        assertEquals("t1", (action as PlannedAction.Complete).key)
    }

    @Test
    fun completeMatchesByTaskId() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.COMPLETE, taskId = "t2")))
        val action = resolver.resolve(plan, snapshot, now, PlanSource.LLM).actions.single()
        assertEquals("t2", (action as PlannedAction.Complete).key)
    }

    @Test
    fun fuzzyTitleMatchFindsTheRightTask() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.COMPLETE, title = "expense report")))
        val action = resolver.resolve(plan, snapshot, now, PlanSource.LLM).actions.single()
        assertEquals("t2", (action as PlannedAction.Complete).key)
    }

    @Test
    fun hallucinatedTaskBecomesAWarningNotAnAction() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.DELETE, title = "feed the dragon")))
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertTrue(resolved.actions.isEmpty())
        assertEquals(1, resolved.warnings.size)
        assertTrue(resolved.warnings.single().contains("feed the dragon"))
    }

    @Test
    fun extendClampsAndDefaultsMinutes() {
        val plan = AssistantPlan(
            ops = listOf(AssistantOp(op = AssistantOps.EXTEND, title = "Buy milk", minutes = null))
        )
        val action = resolver.resolve(plan, snapshot, now, PlanSource.LLM).actions.single()
        assertEquals(15, (action as PlannedAction.Extend).minutes)
    }

    @Test
    fun clarifyProducesNoActionsButAQuestion() {
        val plan = AssistantPlan(
            ops = listOf(AssistantOp(op = AssistantOps.CLARIFY, question = "Morning or evening?"))
        )
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertTrue(resolved.actions.isEmpty())
        assertEquals("Morning or evening?", resolved.clarification)
    }

    @Test
    fun unknownOperationIsIgnoredWithAWarning() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = "launch_rocket", title = "x")))
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertTrue(resolved.actions.isEmpty())
        assertTrue(resolved.warnings.single().contains("launch_rocket"))
    }

    @Test
    fun listOpNeedsNoConfirmation() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.LIST)))
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertEquals(1, resolved.actions.size)
        assertTrue(!resolved.needsConfirmation)
    }

    @Test
    fun severalAddsSurviveTogether() {
        val plan = AssistantPlan(
            ops = listOf(
                AssistantOp(op = AssistantOps.ADD, title = "One"),
                AssistantOp(op = AssistantOps.ADD, title = "Two", scope = "recurring_daily", time = "08:00"),
            )
        )
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertEquals(2, resolved.actions.size)
        val second = resolved.actions[1] as PlannedAction.Add
        assertEquals(TaskScope.RECURRING_DAILY, second.draft.scope)
        assertTrue(second.summary.contains("every day"))
    }

    @Test
    fun addWithNoTitleIsSkipped() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.ADD, title = "   ")))
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.LLM)
        assertTrue(resolved.actions.isEmpty())
        assertTrue(resolved.warnings.isNotEmpty())
    }

    @Test
    fun replyFallsBackToASummaryWhenTheModelGivesNone() {
        val plan = AssistantPlan(reply = "", ops = listOf(AssistantOp(op = AssistantOps.ADD, title = "Gym")))
        val resolved = resolver.resolve(plan, snapshot, now, PlanSource.RULES)
        assertNotNull(resolved.reply)
        assertTrue(resolved.reply.contains("Gym"))
    }
}
