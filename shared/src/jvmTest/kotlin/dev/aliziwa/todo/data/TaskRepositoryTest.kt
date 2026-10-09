package dev.aliziwa.todo.data

import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskKeys
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.fakes.FakeClock
import dev.aliziwa.todo.support.TestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TaskRepository
    private lateinit var clock: FakeClock

    private val zone = TimeZone.of("UTC")
    private val today = LocalDate(2026, 10, 9)

    @BeforeTest
    fun setUp() {
        db = TestDatabase.create()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        clock = FakeClock(DateMath.atMinutes(today, 10 * 60, zone), "UTC")
        repo = TaskRepository(db.taskDao(), db.recurringCompletionDao(), db.alertStateDao(), clock, scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun createsADayTaskWithAnAbsoluteDueInstant(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "  Gym  ", scope = TaskScope.DAY, date = today, timeMinutes = 7 * 60))
        assertNotNull(id)
        val task = db.taskDao().byId(id)!!
        assertEquals("Gym", task.title)
        assertEquals(DateMath.atMinutes(today, 7 * 60, zone), task.dueAtEpochMillis)
        assertEquals(today.toString(), task.scheduledDate)
    }

    @Test
    fun rejectsAnEmptyTitleAndCapsLongOnes(): Unit = runBlocking {
        assertNull(repo.create(TaskDraft(title = "   ")))
        val id = repo.create(TaskDraft(title = "x".repeat(500)))
        assertNotNull(id)
        assertEquals(200, db.taskDao().byId(id)!!.title.length)
    }

    @Test
    fun completeIsIdempotent(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Laundry"))!!
        assertTrue(repo.complete(id))
        assertTrue(repo.complete(id))
        val task = db.taskDao().byId(id)!!
        assertEquals(TaskStatus.DONE.name, task.status)
        assertNotNull(task.completedAtEpochMillis)
    }

    @Test
    fun extendMovesDueForwardAndCountsExtensions(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Call", timeMinutes = 9 * 60))!!
        // Now is 10:00, the task was due 09:00 -> extend pushes from now.
        val newDue = repo.extend(id, 15)
        assertEquals(clock.nowMillis + 15 * 60_000L, newDue)
        assertEquals(1, db.taskDao().byId(id)!!.extensionCount)
    }

    @Test
    fun extendOnADismissedTaskReopensIt(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Call", timeMinutes = 9 * 60))!!
        repo.dismiss(id)
        repo.extend(id, 10)
        assertEquals(TaskStatus.PENDING.name, db.taskDao().byId(id)!!.status)
    }

    @Test
    fun recurringTemplateMaterializesPerDayAndTodayDoesNotLeakIntoTomorrow(): Unit = runBlocking {
        val id = repo.create(
            TaskDraft(title = "Meditate", scope = TaskScope.RECURRING_DAILY, date = today, timeMinutes = 6 * 60)
        )!!
        val todayKey = TaskKeys.forInstance(id, today)
        assertTrue(repo.complete(todayKey))

        val snapshot = repo.snapshotNow()
        assertEquals(TaskStatus.DONE, Boards.resolve(snapshot, todayKey, zone)!!.status)
        val tomorrowKey = TaskKeys.forInstance(id, today.plus(1, DateTimeUnit.DAY))
        assertEquals(TaskStatus.PENDING, Boards.resolve(snapshot, tomorrowKey, zone)!!.status)
    }

    @Test
    fun skippingTodayOnlyDismissesThatDay(): Unit = runBlocking {
        val id = repo.create(
            TaskDraft(title = "Run", scope = TaskScope.RECURRING_DAILY, date = today, timeMinutes = 6 * 60)
        )!!
        repo.dismiss(TaskKeys.forInstance(id, today))
        val snapshot = repo.snapshotNow()
        assertEquals(TaskStatus.DISMISSED, Boards.resolve(snapshot, TaskKeys.forInstance(id, today), zone)!!.status)
        assertEquals(
            TaskStatus.PENDING,
            Boards.resolve(snapshot, TaskKeys.forInstance(id, today.plus(2, DateTimeUnit.DAY)), zone)!!.status,
        )
    }

    @Test
    fun changingARecurringTimeAppliesFromTodayOnward(): Unit = runBlocking {
        val id = repo.create(
            TaskDraft(title = "Pills", scope = TaskScope.RECURRING_DAILY, date = today, timeMinutes = 8 * 60)
        )!!
        // Materialize today's row by extending it, then change the template time.
        repo.extend(TaskKeys.forInstance(id, today), 5)
        assertNotNull(db.recurringCompletionDao().byKey(id, today.toString()))

        repo.update(
            id,
            TaskDraft(title = "Pills", scope = TaskScope.RECURRING_DAILY, date = today, timeMinutes = 20 * 60),
        )
        assertNull(db.recurringCompletionDao().byKey(id, today.toString()))
        val item = Boards.resolve(repo.snapshotNow(), TaskKeys.forInstance(id, today), zone)!!
        assertEquals(DateMath.atMinutes(today, 20 * 60, zone), item.dueAt)
    }

    @Test
    fun deletingARecurringTemplateKeepsHistoryButDropsTodayOnward(): Unit = runBlocking {
        val id = repo.create(
            TaskDraft(title = "Stretch", scope = TaskScope.RECURRING_DAILY, date = today, timeMinutes = 7 * 60)
        )!!
        repo.complete(TaskKeys.forInstance(id, today.plus(-1, DateTimeUnit.DAY)))
        repo.complete(TaskKeys.forInstance(id, today))

        val deleted = repo.delete(id)
        assertNotNull(deleted)
        assertNull(db.taskDao().byId(id))
        assertNull(db.recurringCompletionDao().byKey(id, today.toString()))
        assertNotNull(db.recurringCompletionDao().byKey(id, today.plus(-1, DateTimeUnit.DAY).toString()))
    }

    @Test
    fun deleteCanBeUndone(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Oops", priority = Priority.HIGH))!!
        val deleted = repo.delete(id)!!
        assertNull(db.taskDao().byId(id))
        repo.restore(deleted)
        assertEquals("Oops", db.taskDao().byId(id)!!.title)
    }

    @Test
    fun updateCanMoveATaskBetweenScopes(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Taxes", scope = TaskScope.DAY, date = today, timeMinutes = 9 * 60))!!
        repo.update(id, TaskDraft(title = "Taxes", scope = TaskScope.MONTH, date = today, timeMinutes = null))
        val task = db.taskDao().byId(id)!!
        assertEquals(TaskScope.MONTH.name, task.scopeType)
        assertNull(task.dueAtEpochMillis)
    }

    @Test
    fun alertStateIsKeyedOnTheDueInstant(): Unit = runBlocking {
        val id = repo.create(TaskDraft(title = "Standup", timeMinutes = 9 * 60))!!
        val due = db.taskDao().byId(id)!!.dueAtEpochMillis!!
        assertTrue(!repo.alreadyAlerted(id, due))
        repo.markAlerted(id, due)
        assertTrue(repo.alreadyAlerted(id, due))
        // After an extension the due instant changed, so the task may alert again.
        val newDue = repo.extend(id, 10)!!
        assertTrue(!repo.alreadyAlerted(id, newDue))
    }

    @Test
    fun overdueTasksFromYesterdayRollIntoToday(): Unit = runBlocking {
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        repo.create(TaskDraft(title = "Old thing", scope = TaskScope.DAY, date = yesterday))
        repo.create(TaskDraft(title = "New thing", scope = TaskScope.DAY, date = today))
        val board = Boards.day(repo.snapshotNow(), today, zone)
        assertEquals(listOf("Old thing"), board.overdue.map { it.title })
        assertEquals(listOf("New thing"), board.items.map { it.title })
    }
}
