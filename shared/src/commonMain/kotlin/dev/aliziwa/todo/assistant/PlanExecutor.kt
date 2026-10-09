package dev.aliziwa.todo.assistant

import dev.aliziwa.todo.data.TaskRepository
import dev.aliziwa.todo.scheduling.DueEngine
import dev.aliziwa.todo.util.Log

/** Applies a confirmed [ResolvedPlan] through the repositories / due engine. */
class PlanExecutor(
    private val repo: TaskRepository,
    private val engine: DueEngine,
) {
    private val tag = "PlanExecutor"

    data class Outcome(val applied: Int, val failures: List<String>, val listRequested: Boolean)

    suspend fun execute(plan: ResolvedPlan): Outcome {
        var applied = 0
        var listRequested = false
        val failures = mutableListOf<String>()

        plan.actions.forEach { action ->
            runCatching {
                when (action) {
                    is PlannedAction.Add ->
                        if (repo.create(action.draft) != null) applied++ else failures += "Empty title"

                    is PlannedAction.Update ->
                        if (repo.update(action.taskId, action.draft)) applied++ else failures += action.summary

                    is PlannedAction.Complete -> {
                        engine.complete(action.key)
                        applied++
                    }

                    is PlannedAction.Delete -> {
                        engine.delete(action.key)
                        applied++
                    }

                    is PlannedAction.Extend -> {
                        engine.extend(action.key, action.minutes)
                        applied++
                    }

                    is PlannedAction.ListTasks -> listRequested = true
                }
            }.onFailure {
                Log.e(tag, "action failed: ${action.summary}", it)
                failures += action.summary
            }
        }
        return Outcome(applied, failures, listRequested)
    }
}
