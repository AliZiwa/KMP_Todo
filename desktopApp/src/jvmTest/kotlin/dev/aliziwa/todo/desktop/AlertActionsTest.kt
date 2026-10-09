package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.Task
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.platform.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the exact handlers the always-on-top alert window's buttons call
 * (`TodoApp.completeTask` / `extendTask` / `dismissAlert` / `deleteTask`) against a real
 * `TodoApp` graph with the real desktop ports, and asserts the three things a user can see:
 * the task state changed, the alert window state cleared (`DesktopUiController.alert` and
 * `DueEngine.currentAlert`), and the doorbell was stopped.
 */
class AlertActionsTest {

    private suspend fun TestHarness.armedAlertFor(title: String): Pair<String, Long> {
        val zone = ZoneId.of(SystemClock.timeZoneId())
        val now = Instant.ofEpochMilli(SystemClock.nowEpochMillis()).atZone(zone)
        // Due at the start of the current minute => already due, so evaluate() alerts immediately.
        val id = graph.taskRepository.create(
            TaskDraft(
                title = title,
                priority = Priority.HIGH,
                scope = TaskScope.DAY,
                date = LocalDate(now.year, now.monthValue, now.dayOfMonth),
                timeMinutes = now.hour * 60 + now.minute,
            ),
        )
        assertNotNull(id, "repository refused the draft")
        TodoApp.onAlarmFired(id)
        waitFor("alert window state for $title") { ui.alert.value?.taskId == id }
        waitFor("doorbell started") { sound.played() }
        val dueAt = assertNotNull(task(id)?.dueAt, "task has no dueAt")
        sound.clear()
        return id to dueAt
    }

    private suspend fun TestHarness.task(id: String): Task? =
        graph.taskRepository.snapshotNow().tasks.firstOrNull { it.id == id }

    private suspend fun TestHarness.assertAlertCleared(id: String) {
        waitFor("DesktopUiController.alert cleared") { ui.alert.value == null }
        waitFor("DueEngine.currentAlert cleared") { graph.dueEngine.currentAlert.value == null }
        waitFor("doorbell stopped") { sound.stopped() }
        assertNull(ui.alert.value, "alert window should be gone for $id")
    }

    @Test
    fun `Done completes the task, clears the alert and stops the doorbell`() =
        DesktopTestHarness.withGraph { h ->
            runBlocking {
                val (id, _) = h.armedAlertFor("Pick up kids")
                TodoApp.completeTask(id)
                waitFor("task DONE") { h.task(id)?.status == TaskStatus.DONE }
                h.assertAlertCleared(id)
                assertTrue(h.alarms.armed()[id] == null, "a completed task must not stay armed")
            }
        }

    @Test
    fun `plus 15 moves the due time, re-arms the alarm and clears the alert`() =
        DesktopTestHarness.withGraph { h ->
            runBlocking {
                val (id, originalDue) = h.armedAlertFor("Call the dentist")
                val before = SystemClock.nowEpochMillis()
                TodoApp.extendTask(id, 15)

                val extended = waitForValue("extended dueAt") {
                    h.task(id)?.takeIf { (it.dueAt ?: 0) > originalDue }
                }
                val newDue = assertNotNull(extended.dueAt)
                // Rule: extend moves from max(now, dueAt); the task was already overdue, so ~now+15m.
                val expected = before + 15 * 60_000L
                assertTrue(
                    abs(newDue - expected) < 90_000,
                    "expected ~${expected} (now+15m), got $newDue (delta ${newDue - expected}ms)",
                )
                assertEquals(TaskStatus.PENDING, extended.status, "extend must not complete the task")
                assertEquals(1, extended.extensionCount, "extensionCount should increment")

                h.assertAlertCleared(id)
                waitFor("alarm re-armed at the new due time") { h.alarms.armed()[id] == newDue }
            }
        }

    @Test
    fun `Dismiss marks the task dismissed and clears the alert`() =
        DesktopTestHarness.withGraph { h ->
            runBlocking {
                val (id, _) = h.armedAlertFor("Water the plants")
                TodoApp.dismissAlert(id)
                waitFor("task DISMISSED") { h.task(id)?.status == TaskStatus.DISMISSED }
                h.assertAlertCleared(id)
            }
        }

    @Test
    fun `Delete removes the task, cancels the alarm and clears the alert`() =
        DesktopTestHarness.withGraph { h ->
            runBlocking {
                val (id, _) = h.armedAlertFor("Cancel the subscription")
                TodoApp.deleteTask(id)
                waitFor("task gone") { h.task(id) == null }
                h.assertAlertCleared(id)
                waitFor("alarm cancelled") { h.alarms.armed()[id] == null }
            }
        }

    @Test
    fun `sticky summary reaches the tray after an action`() =
        DesktopTestHarness.withGraph { h ->
            runBlocking {
                val (id, _) = h.armedAlertFor("Take out the bins")
                waitFor("sticky summary published") { h.ui.sticky.value != null }
                TodoApp.completeTask(id)
                waitFor("completedToday counted") { (h.ui.sticky.value?.completedTodayCount ?: 0) >= 1 }
                val summary = assertNotNull(h.ui.sticky.value)
                println("[sticky] $summary")
                assertEquals(0, summary.dueNowCount, "nothing should be due once it is done")
            }
        }
}
