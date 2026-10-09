package dev.aliziwa.todo.domain

/** How a task relates to "now" — drives chip colour and whether an alert is owed. */
enum class DueState {
    /** No due time set at all. */
    NO_TIME,

    /** Due in the future. */
    UPCOMING,

    /** Due within the current minute. */
    DUE,

    /** Past due and still pending. */
    OVERDUE,

    /** Past due but the user silenced it. */
    DISMISSED,

    DONE,
}

object Due {
    /** Alert window: once now >= dueAt the task is due. */
    fun stateOf(item: TaskItem, nowMillis: Long): DueState = when {
        item.status == TaskStatus.DONE -> DueState.DONE
        item.status == TaskStatus.DISMISSED -> DueState.DISMISSED
        item.dueAt == null -> DueState.NO_TIME
        nowMillis < item.dueAt -> DueState.UPCOMING
        nowMillis - item.dueAt < 60_000 -> DueState.DUE
        else -> DueState.OVERDUE
    }

    /** True when the task has reached its due instant and the user has not acted. */
    fun isRinging(item: TaskItem, nowMillis: Long): Boolean =
        item.status == TaskStatus.PENDING && item.dueAt != null && nowMillis >= item.dueAt

    fun remainingMillis(item: TaskItem, nowMillis: Long): Long? =
        item.dueAt?.let { it - nowMillis }
}

/** Extension presets and the "move forward from max(now, dueAt)" rule. */
object ExtensionRules {
    val presetMinutes: List<Int> = listOf(5, 15, 30, 60)
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60 * 24 * 7

    fun sanitize(byMinutes: Int): Int = byMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /**
     * New due instant after extending. Works both before and after the original due time:
     * extending an overdue task pushes it [byMinutes] from *now*, not from the stale due time.
     */
    fun extendedDueAt(currentDueAt: Long?, nowMillis: Long, byMinutes: Int): Long {
        val base = maxOf(nowMillis, currentDueAt ?: nowMillis)
        return base + sanitize(byMinutes) * 60_000L
    }
}
