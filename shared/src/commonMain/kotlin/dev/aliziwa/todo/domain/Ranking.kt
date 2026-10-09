package dev.aliziwa.todo.domain

/**
 * Authoritative ordering: HIGH priority first, then earlier [TaskItem.dueAt], then earlier
 * [TaskItem.createdAt]. Items without a due time sort after items with one at the same priority.
 * DONE items always sink to the bottom.
 */
val TaskRanking: Comparator<TaskItem> = Comparator { a, b ->
    val doneA = if (a.status == TaskStatus.DONE) 1 else 0
    val doneB = if (b.status == TaskStatus.DONE) 1 else 0
    if (doneA != doneB) return@Comparator doneA - doneB

    val prio = b.priority.ordinal - a.priority.ordinal
    if (prio != 0) return@Comparator prio

    val dueA = a.dueAt ?: Long.MAX_VALUE
    val dueB = b.dueAt ?: Long.MAX_VALUE
    if (dueA != dueB) return@Comparator dueA.compareTo(dueB)

    if (a.createdAt != b.createdAt) return@Comparator a.createdAt.compareTo(b.createdAt)
    a.key.compareTo(b.key)
}

fun List<TaskItem>.ranked(): List<TaskItem> = sortedWith(TaskRanking)
