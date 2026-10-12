package dev.aliziwa.todo.data

import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.assistant.ChatMessage
import dev.aliziwa.todo.assistant.ChatRole
import dev.aliziwa.todo.assistant.PlanSource
import dev.aliziwa.todo.assistant.PlanState
import dev.aliziwa.todo.assistant.PlannedAction
import dev.aliziwa.todo.assistant.ResolvedPlan
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskKeys
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** Which part of the app a seeded demo task is there to make look alive. */
enum class DemoBucket {
    /** Today, still to come — the countdown chips. */
    TODAY_UPCOMING,

    /** Today, already past its time and untouched — the overdue styling. */
    TODAY_OVERDUE,

    /** Today, ticked off — the progress ring. */
    TODAY_DONE,

    /** Tomorrow, so the assistant transcript below is actually true. */
    TOMORROW,

    /** The Week board. */
    THIS_WEEK,

    /** The Month board. */
    THIS_MONTH,

    /** The Recurring board (templates). */
    RECURRING,
}

/** One planned demo row: a draft plus how it should be seeded. */
data class DemoTask(
    val draft: TaskDraft,
    val bucket: DemoBucket,
    /** Seeded as already complete. */
    val done: Boolean = false,
)

/**
 * App Store screenshot data. Builds a believable day *relative to the device clock and timezone*,
 * so a screenshot taken at 10:40 and one taken at 16:05 both look right.
 *
 * [plan] is pure (and unit-tested); [seed] is the only part that touches the database.
 */
object DemoData {
    private const val TAG = "DemoData"

    /** Last minute of a local day. */
    private const val LAST_MINUTE = 23 * 60 + 59

    /**
     * The demo day, as drafts. Invariants the tests pin down:
     *  - exactly one row is in the past and still pending ([DemoBucket.TODAY_OVERDUE]);
     *  - every other dated row today is at or after [nowMillis];
     *  - three recurring templates, each with a time of day.
     */
    fun plan(nowMillis: Long, zone: TimeZone): List<DemoTask> {
        val today = DateMath.today(nowMillis, zone)
        val nowMinutes = DateMath.minutesOfDay(nowMillis, zone)

        // First upcoming task ~25 minutes out so a live countdown chip is on screen; the rest are
        // spread over whatever is left of the day.
        val first = (nowMinutes + 25).coerceAtMost(LAST_MINUTE)
        val room = LAST_MINUTE - first
        val upcomingTitles = listOf(
            "Call mum" to Priority.MEDIUM,
            "Clear the inbox" to Priority.LOW,
            "Groceries run" to Priority.MEDIUM,
            "Read 20 pages" to Priority.LOW,
            "Draft the trip budget" to Priority.HIGH,
            "Tidy the desk" to Priority.LOW,
        )
        val upcoming = upcomingTitles.mapIndexed { index, (title, priority) ->
            val minutes = (first + room * index / upcomingTitles.size).coerceAtMost(LAST_MINUTE)
            DemoTask(
                draft = TaskDraft(
                    title = title,
                    priority = priority,
                    scope = TaskScope.DAY,
                    date = today,
                    timeMinutes = minutes,
                ),
                bucket = DemoBucket.TODAY_UPCOMING,
            )
        }

        // Strictly before "now", but never before midnight.
        fun earlier(byMinutes: Int): Int =
            minOf(nowMinutes - byMinutes, nowMinutes - 1).coerceAtLeast(0)

        val overdue = DemoTask(
            draft = TaskDraft(
                title = "Post the parcel",
                priority = Priority.HIGH,
                scope = TaskScope.DAY,
                date = today,
                timeMinutes = earlier(95),
            ),
            bucket = DemoBucket.TODAY_OVERDUE,
        )

        val completed = listOf(
            "Morning walk" to earlier(240),
            "Pay the electricity bill" to earlier(70),
        ).map { (title, minutes) ->
            DemoTask(
                draft = TaskDraft(
                    title = title,
                    priority = Priority.MEDIUM,
                    scope = TaskScope.DAY,
                    date = today,
                    timeMinutes = minutes,
                ),
                bucket = DemoBucket.TODAY_DONE,
                done = true,
            )
        }

        // Matches the seeded assistant transcript ("gym tomorrow at 7").
        val tomorrow = DemoTask(
            draft = TaskDraft(
                title = "Gym",
                priority = Priority.MEDIUM,
                scope = TaskScope.DAY,
                date = today.plus(1, DateTimeUnit.DAY),
                timeMinutes = 7 * 60,
            ),
            bucket = DemoBucket.TOMORROW,
        )

        val weekEnd = DateMath.weekRange(today).second
        val week = listOf(
            "Plan the weekend trip" to Priority.HIGH,
            "Deep clean the kitchen" to Priority.MEDIUM,
            "Finish the book club chapter" to Priority.LOW,
            "Back up the photos" to Priority.LOW,
        ).mapIndexed { index, (title, priority) ->
            DemoTask(
                draft = TaskDraft(
                    title = title,
                    priority = priority,
                    scope = TaskScope.WEEK,
                    date = minOf(today.plus(index + 1, DateTimeUnit.DAY), weekEnd),
                    timeMinutes = null,
                ),
                bucket = DemoBucket.THIS_WEEK,
            )
        }

        val monthEnd = DateMath.monthRange(today).second
        val month = listOf(
            "Dentist appointment" to Priority.HIGH,
            "Renew the library card" to Priority.LOW,
            "Sort the winter clothes" to Priority.MEDIUM,
        ).mapIndexed { index, (title, priority) ->
            DemoTask(
                draft = TaskDraft(
                    title = title,
                    priority = priority,
                    scope = TaskScope.MONTH,
                    date = minOf(today.plus(6 + index * 5, DateTimeUnit.DAY), monthEnd),
                    timeMinutes = null,
                ),
                bucket = DemoBucket.THIS_MONTH,
            )
        }

        val recurring = listOf(
            Triple("Walk Biscuit", 7 * 60 + 30, Priority.MEDIUM),
            Triple("Standup", 9 * 60 + 30, Priority.MEDIUM),
            Triple("Water the plants", 18 * 60, Priority.LOW),
        ).map { (title, minutes, priority) ->
            DemoTask(
                draft = TaskDraft(
                    title = title,
                    priority = priority,
                    scope = TaskScope.RECURRING_DAILY,
                    date = today,
                    timeMinutes = minutes,
                ),
                bucket = DemoBucket.RECURRING,
            )
        }

        return upcoming + overdue + completed + tomorrow + week + month + recurring
    }

    /**
     * True when this row must be seeded as already complete.
     *
     * A recurring template whose time of day has already gone by would otherwise render as a second
     * (and third) red overdue row — the screenshots must show exactly ONE overdue task, so those
     * instances are ticked off instead. That also makes them the morning's "already done" items.
     */
    fun isSeededDone(task: DemoTask, nowMillis: Long, zone: TimeZone): Boolean {
        if (task.done) return true
        if (task.draft.scope != TaskScope.RECURRING_DAILY) return false
        val minutes = task.draft.timeMinutes ?: return false
        val today = DateMath.today(nowMillis, zone)
        return DateMath.atMinutes(today, minutes, zone) <= nowMillis
    }

    /**
     * The rows that will be pending with a due instant already in the past — i.e. exactly what the
     * Day tab shows as overdue and counts in its "N due now" badge. Must always be one task.
     */
    fun overdueAfterSeeding(nowMillis: Long, zone: TimeZone): List<DemoTask> {
        val today = DateMath.today(nowMillis, zone)
        return plan(nowMillis, zone).filter { task ->
            if (isSeededDone(task, nowMillis, zone)) return@filter false
            val minutes = task.draft.timeMinutes ?: return@filter false
            val date = if (task.draft.scope == TaskScope.RECURRING_DAILY) today else task.draft.date
            DateMath.atMinutes(date ?: today, minutes, zone) <= nowMillis
        }
    }

    /**
     * Wipes the database and writes the demo day, then seeds the assistant transcript.
     *
     * The one intentional overdue task is marked as "already alerted" so seeding never sets the
     * doorbell off mid-screenshot; every other past-due row is seeded complete.
     */
    suspend fun seed(graph: AppGraph) {
        val repo = graph.taskRepository
        val zone = graph.zone()
        val now = graph.platform.clock.nowEpochMillis()
        val today = DateMath.today(now, zone)

        // Delete through the repository so recurring rows and alert state go with each task.
        repo.snapshotNow().tasks.forEach { repo.delete(it.id) }

        plan(now, zone).forEach { demo ->
            val id = repo.create(demo.draft)
            if (id == null) {
                Log.w(TAG, "demo task \"${demo.draft.title}\" was rejected")
                return@forEach
            }
            when {
                // Recurring instances are completed per-day, so they need the instance key.
                isSeededDone(demo, now, zone) -> {
                    val key = if (demo.draft.scope == TaskScope.RECURRING_DAILY) {
                        TaskKeys.forInstance(id, today)
                    } else {
                        id
                    }
                    repo.complete(key)
                }
                // The single intentional overdue row: visibly late, but silent.
                demo.bucket == DemoBucket.TODAY_OVERDUE -> demo.draft.timeMinutes?.let { minutes ->
                    repo.markAlerted(id, DateMath.atMinutes(demo.draft.date ?: today, minutes, zone))
                }
            }
        }

        graph.assistant.seedTranscript(transcript(now, today))
        Log.i(TAG, "demo data seeded")
    }

    /**
     * A short, already-applied exchange so the Chat screen is not an empty state in screenshots.
     * The two tasks it claims to have created really are in the seeded data.
     */
    fun transcript(nowMillis: Long, today: LocalDate): List<ChatMessage> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val plan = ResolvedPlan(
            reply = "Done — gym tomorrow morning and the plants every evening.",
            actions = listOf(
                PlannedAction.Add(
                    draft = TaskDraft(
                        title = "Gym",
                        scope = TaskScope.DAY,
                        date = tomorrow,
                        timeMinutes = 7 * 60,
                    ),
                    summary = "Add \"Gym\" tomorrow at 7:00 AM",
                ),
                PlannedAction.Add(
                    draft = TaskDraft(
                        title = "Water the plants",
                        priority = Priority.LOW,
                        scope = TaskScope.RECURRING_DAILY,
                        date = today,
                        timeMinutes = 18 * 60,
                    ),
                    summary = "Add \"Water the plants\" every day at 6:00 PM",
                ),
            ),
            source = PlanSource.LLM,
        )
        return listOf(
            ChatMessage(
                id = "demo-user-1",
                role = ChatRole.USER,
                text = "gym tomorrow at 7, and remind me to water the plants every evening at 6",
                at = nowMillis - 90_000,
            ),
            ChatMessage(
                id = "demo-assistant-1",
                role = ChatRole.ASSISTANT,
                text = plan.reply,
                at = nowMillis - 85_000,
                plan = plan,
                planState = PlanState.CONFIRMED,
            ),
        )
    }
}

/**
 * Swift-callable entry point (`DemoDataKt.seedDemoDataAsync`): runs [DemoData.seed] on the graph's
 * own scope and calls [onDone] on completion, success or failure.
 */
fun seedDemoDataAsync(graph: AppGraph, onDone: () -> Unit) {
    graph.appScope.launch {
        runCatching { DemoData.seed(graph) }
            .onFailure { Log.e("DemoData", "seeding failed", it) }
        onDone()
    }
}
