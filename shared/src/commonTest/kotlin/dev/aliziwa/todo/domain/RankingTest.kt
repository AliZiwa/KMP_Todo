package dev.aliziwa.todo.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class RankingTest {
    private fun item(
        key: String,
        priority: Priority = Priority.MEDIUM,
        dueAt: Long? = null,
        createdAt: Long = 0,
        status: TaskStatus = TaskStatus.PENDING,
    ) = TaskItem(
        key = key,
        taskId = key,
        title = key,
        notes = "",
        priority = priority,
        scope = TaskScope.DAY,
        dueAt = dueAt,
        status = status,
        extensionCount = 0,
        createdAt = createdAt,
        completedAt = null,
    )

    @Test
    fun highPriorityFirst() {
        val ranked = listOf(
            item("low", Priority.LOW),
            item("high", Priority.HIGH),
            item("med", Priority.MEDIUM),
        ).ranked()
        assertEquals(listOf("high", "med", "low"), ranked.map { it.key })
    }

    @Test
    fun earlierDueWinsWithinPriority() {
        val ranked = listOf(
            item("later", dueAt = 2_000),
            item("sooner", dueAt = 1_000),
            item("none"),
        ).ranked()
        assertEquals(listOf("sooner", "later", "none"), ranked.map { it.key })
    }

    @Test
    fun createdAtBreaksTies() {
        val ranked = listOf(
            item("second", createdAt = 20),
            item("first", createdAt = 10),
        ).ranked()
        assertEquals(listOf("first", "second"), ranked.map { it.key })
    }

    @Test
    fun doneSinksBelowEverything() {
        val ranked = listOf(
            item("doneHigh", Priority.HIGH, status = TaskStatus.DONE),
            item("pendingLow", Priority.LOW),
        ).ranked()
        assertEquals(listOf("pendingLow", "doneHigh"), ranked.map { it.key })
    }

    @Test
    fun dismissedKeepsItsPlace() {
        val ranked = listOf(
            item("done", Priority.HIGH, status = TaskStatus.DONE),
            item("dismissedHigh", Priority.HIGH, status = TaskStatus.DISMISSED),
            item("pendingMed", Priority.MEDIUM),
        ).ranked()
        assertEquals(listOf("dismissedHigh", "pendingMed", "done"), ranked.map { it.key })
    }
}
