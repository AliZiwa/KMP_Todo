package dev.aliziwa.todo.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DueStateTest {
    private fun item(dueAt: Long?, status: TaskStatus = TaskStatus.PENDING) = TaskItem(
        key = "k", taskId = "k", title = "k", notes = "", priority = Priority.MEDIUM,
        scope = TaskScope.DAY, dueAt = dueAt, status = status, extensionCount = 0,
        createdAt = 0, completedAt = null,
    )

    @Test
    fun statesFollowTheClock() {
        assertEquals(DueState.NO_TIME, Due.stateOf(item(null), 1_000))
        assertEquals(DueState.UPCOMING, Due.stateOf(item(10_000), 1_000))
        assertEquals(DueState.DUE, Due.stateOf(item(1_000), 1_000))
        assertEquals(DueState.OVERDUE, Due.stateOf(item(1_000), 1_000 + 61_000))
        assertEquals(DueState.DONE, Due.stateOf(item(1_000, TaskStatus.DONE), 5_000))
        assertEquals(DueState.DISMISSED, Due.stateOf(item(1_000, TaskStatus.DISMISSED), 5_000))
    }

    @Test
    fun onlyPendingTasksRing() {
        assertTrue(Due.isRinging(item(1_000), 1_000))
        assertFalse(Due.isRinging(item(1_000), 999))
        assertFalse(Due.isRinging(item(1_000, TaskStatus.DISMISSED), 5_000))
        assertFalse(Due.isRinging(item(null), 5_000))
    }

    @Test
    fun extensionMovesForwardFromMaxOfNowAndDue() {
        // before due: from the due time
        assertEquals(100_000 + 15 * 60_000L, ExtensionRules.extendedDueAt(100_000, 50_000, 15))
        // after due: from now, not from the stale due time
        assertEquals(200_000 + 15 * 60_000L, ExtensionRules.extendedDueAt(100_000, 200_000, 15))
        // no due time at all: from now
        assertEquals(200_000 + 5 * 60_000L, ExtensionRules.extendedDueAt(null, 200_000, 5))
    }

    @Test
    fun extensionAmountIsClamped() {
        assertEquals(ExtensionRules.MIN_MINUTES, ExtensionRules.sanitize(0))
        assertEquals(ExtensionRules.MAX_MINUTES, ExtensionRules.sanitize(999_999))
    }
}
