package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the bug a real qwen2.5-0.5b run produced: it echoed the schema's op placeholder verbatim,
 * the plan parsed, every op was rejected, and the user got a confident reply with nothing created.
 */
class PlanRejectionTest {
    private val zone = TimeZone.of("UTC")
    private val resolver = PlanResolver { zone }
    private val today = LocalDate(2026, 10, 9)
    private val now = DateMath.atMinutes(today, 10 * 60, zone)

    private val snapshot = TaskSnapshot(
        tasks = listOf(
            Task(id = "t1", title = "Buy milk", scope = TaskScope.DAY, scheduledDate = today, createdAt = 1, updatedAt = 1)
        )
    )

    private fun resolve(plan: AssistantPlan) = resolver.resolve(plan, snapshot, now, PlanSource.LLM)

    @Test
    fun anEchoedEnumPlaceholderIsAFullRejection() {
        val plan = AssistantPlan(
            reply = "I will add that for you.",
            ops = listOf(
                AssistantOp(
                    op = "add_task | update_task | complete_task | delete_task | extend_task | list_tasks | clarify | chat",
                    title = "Gym",
                )
            ),
        )
        val resolved = resolve(plan)
        assertTrue(plan.hasActionableOps())
        assertTrue(resolved.actions.isEmpty())
        assertTrue(resolved.isFullRejectionOf(plan))
    }

    @Test
    fun hallucinatedTaskReferencesAreAFullRejection() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.COMPLETE, title = "feed the dragon")))
        assertTrue(resolve(plan).isFullRejectionOf(plan))
    }

    @Test
    fun smallTalkIsNotARejection() {
        val plan = AssistantPlan(reply = "Any time.", ops = listOf(AssistantOp(op = AssistantOps.CHAT)))
        assertFalse(plan.hasActionableOps())
        assertFalse(resolve(plan).isFullRejectionOf(plan))
    }

    @Test
    fun anEmptyOpsListIsNotARejection() {
        val plan = AssistantPlan(reply = "Nothing to do.")
        assertFalse(resolve(plan).isFullRejectionOf(plan))
    }

    @Test
    fun clarifyIsNotARejection() {
        val plan = AssistantPlan(
            ops = listOf(AssistantOp(op = AssistantOps.CLARIFY, question = "Morning or evening?"))
        )
        assertFalse(plan.hasActionableOps())
        assertFalse(resolve(plan).isFullRejectionOf(plan))
    }

    @Test
    fun aPartiallyUsablePlanIsNotARejection() {
        val plan = AssistantPlan(
            ops = listOf(
                AssistantOp(op = AssistantOps.ADD, title = "Gym"),
                AssistantOp(op = AssistantOps.COMPLETE, title = "nonexistent"),
            )
        )
        val resolved = resolve(plan)
        assertTrue(resolved.actions.size == 1)
        assertTrue(resolved.warnings.size == 1)
        assertFalse(resolved.isFullRejectionOf(plan))
    }

    @Test
    fun aGoodPlanIsNotARejection() {
        val plan = AssistantPlan(ops = listOf(AssistantOp(op = AssistantOps.ADD, title = "Gym")))
        assertFalse(resolve(plan).isFullRejectionOf(plan))
    }
}
