package dev.aliziwa.todo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.aliziwa.todo.App
import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.assistant.PlanState
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.platform.SystemClock
import dev.aliziwa.todo.ui.ContentColumn
import dev.aliziwa.todo.ui.TaskEditorForm
import dev.aliziwa.todo.ui.TodoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real `App(graph)` — the real repositories, DueEngine and assistant from
 * [DesktopTestHarness] — into PNGs with [ImageComposeScene], in light and dark, at desktop
 * (1120x780) and phone (400x800) sizes. No UI automation involved: the scene is driven frame by
 * frame so the spring animations settle before the shot is taken.
 *
 * Output goes to `todo.screenshotDir` (default: build/screenshots). The test asserts only that
 * every expected PNG was written and is non-trivial in size; it exists to produce artefacts a
 * human can look at.
 */
class ScreenshotTest {

    private val outDir: File = File(
        System.getProperty("todo.screenshotDir")
            ?: System.getenv("TODO_SCREENSHOT_DIR")
            ?: "build/screenshots",
    ).apply { mkdirs() }

    private val written = mutableListOf<File>()

    @Test
    fun renders_every_screen_in_light_and_dark() {
        DesktopTestHarness.withGraph { harness ->
            val graph = harness.graph
            runBlocking { seed(graph) }

            // The morning prompt fires on a fresh database; take it out of the way so the shots
            // show the board, and capture the sheet deliberately at the end instead.
            runBlocking {
                runCatching {
                    waitFor("the daily prompt", timeoutMillis = 4_000) {
                        graph.dueEngine.dailyPromptRequested.value
                    }
                }
                graph.dueEngine.consumeDailyPrompt()
            }

            // 1. The due alert: the engine raises it by itself for the overdue task.
            runBlocking { waitFor("an alert to be raised") { graph.dueEngine.currentAlert.value != null } }
            shoot("alert", dark = false) { App(graph, initialRoute = "today", darkTheme = false) }
            shoot("alert", dark = true) { App(graph, initialRoute = "today", darkTheme = true) }

            // 2. Silence it (the task stays due and pending) and shoot the boards.
            runBlocking {
                graph.dueEngine.silenceCurrentAlert()
                waitFor("the alert to clear") { graph.dueEngine.currentAlert.value == null }
            }
            shoot("day", dark = false) { App(graph, initialRoute = "today", darkTheme = false) }
            shoot("day", dark = true) { App(graph, initialRoute = "today", darkTheme = true) }
            shoot("day-narrow", dark = false, width = 400, height = 800) {
                App(graph, initialRoute = "today", darkTheme = false)
            }
            shoot("day-narrow", dark = true, width = 400, height = 800) {
                App(graph, initialRoute = "today", darkTheme = true)
            }

            // 3. Editor, closed and with the time picker open.
            shoot("editor", dark = false) { App(graph, initialRoute = "add", darkTheme = false) }
            shoot("editor", dark = true) { App(graph, initialRoute = "add", darkTheme = true) }
            shoot("editor-timepicker", dark = false) { EditorWithTimePicker(dark = false) }
            shoot("editor-timepicker", dark = true) { EditorWithTimePicker(dark = true) }

            // 4. Chat with a pending plan card, produced by the real rule-based parser.
            graph.assistant.send("gym tomorrow at 7am")
            runBlocking {
                waitFor("the assistant to propose a plan", timeoutMillis = 20_000) {
                    graph.assistant.messages.value.any { it.plan != null }
                }
            }
            val pending = graph.assistant.messages.value.any { it.planState == PlanState.PENDING }
            shoot("chat", dark = false) { App(graph, initialRoute = "chat", darkTheme = false) }
            shoot("chat", dark = true) { App(graph, initialRoute = "chat", darkTheme = true) }

            // 5. Settings and the morning plan sheet.
            shoot("settings", dark = false) { App(graph, initialRoute = "settings", darkTheme = false) }
            shoot("settings", dark = true) { App(graph, initialRoute = "settings", darkTheme = true) }
            shoot("plan", dark = false) { App(graph, initialRoute = "plan", darkTheme = false) }
            shoot("plan", dark = true) { App(graph, initialRoute = "plan", darkTheme = true) }

            // 6. Two interactive states, driven through the scene's own pointer API: hovering a
            // task row (desktop reveals Done / Snooze / Edit / Delete) and the empty Week board
            // reached by clicking the navigation rail.
            shoot(
                "day-hover",
                dark = false,
                interact = { it.sendPointerEvent(PointerEventType.Move, dp(606f, 278f)) },
            ) { App(graph, initialRoute = "today", darkTheme = false) }
            shoot(
                "week-empty",
                dark = false,
                interact = { scene ->
                    scene.sendPointerEvent(PointerEventType.Press, dp(46f, 185f))
                    scene.sendPointerEvent(PointerEventType.Release, dp(46f, 185f))
                },
            ) { App(graph, initialRoute = "today", darkTheme = false) }
            shoot(
                "week-empty",
                dark = true,
                interact = { scene ->
                    scene.sendPointerEvent(PointerEventType.Press, dp(46f, 185f))
                    scene.sendPointerEvent(PointerEventType.Release, dp(46f, 185f))
                },
            ) { App(graph, initialRoute = "today", darkTheme = true) }

            println("screenshots written to ${outDir.absolutePath}")
            written.forEach { println("  ${it.absolutePath} (${it.length() / 1024} kB)") }
            assertTrue(pending, "expected the plan card to still be awaiting confirmation")
            assertTrue(written.size >= 19, "expected at least 19 screenshots, got ${written.size}")
            written.forEach {
                assertTrue(it.length() > 5_000, "${it.name} looks empty (${it.length()} bytes)")
            }
        }
    }

    /** The task editor with the time picker already open — the whole point of the new control. */
    @Composable
    private fun EditorWithTimePicker(dark: Boolean) {
        TodoTheme(dark = dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ContentColumn {
                    Box(Modifier.fillMaxSize()) {
                        TaskEditorForm(
                            initial = TaskDraft(
                                title = "Swim 1 km",
                                notes = "",
                                priority = Priority.MEDIUM,
                                scope = TaskScope.DAY,
                                date = today(),
                                timeMinutes = 18 * 60 + 30,
                            ),
                            today = today(),
                            confirmLabel = "Add task",
                            nowMinutes = nowMinutes(),
                            openTimePicker = true,
                            onSubmit = {},
                            onCancel = {},
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shoot(
        name: String,
        dark: Boolean,
        width: Int = 1_120,
        height: Int = 780,
        frames: Int = 70,
        /** Optional pointer interaction (in dp), applied half way through the frames. */
        interact: (ImageComposeScene) -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        val scale = 2f
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
            content = content,
        )
        try {
            // Drive the clock so springs, the sparkle burst and the countdown flip all settle.
            var nanos = 0L
            repeat(frames) { frame ->
                nanos += 16_666_667L
                if (frame == frames / 3) interact(scene)
                scene.render(nanos)
            }
            val image = scene.render(nanos + 16_666_667L)
            val data = requireNonNull(image.encodeToData(), "PNG encoding failed for $name")
            val file = File(outDir, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(data.bytes)
            written += file
        } finally {
            scene.close()
        }
    }

    private fun <T : Any> requireNonNull(value: T?, message: String): T = value ?: error(message)

    /** Scenes take pixels; the layout is in dp at density 2. */
    private fun dp(x: Float, y: Float) = Offset(x * 2f, y * 2f)

    // ---------------------------------------------------------------- seed data

    private suspend fun seed(graph: AppGraph) {
        val now = nowMinutes()
        // Clamped so the seed stays inside today whatever time the test runs at.
        fun at(offset: Int) = (now + offset).coerceIn(0, 1_439)

        graph.taskRepository.create(
            TaskDraft(
                title = "Call the dentist back",
                notes = "Ask about the Thursday slot",
                priority = Priority.HIGH,
                scope = TaskScope.DAY,
                date = today(),
                timeMinutes = at(-25),
            ),
        )
        graph.taskRepository.create(
            TaskDraft(
                title = "Stand-up with the team",
                priority = Priority.MEDIUM,
                scope = TaskScope.DAY,
                date = today(),
                timeMinutes = at(3),
            ),
        )
        graph.taskRepository.create(
            TaskDraft(
                title = "Pick up the kids",
                priority = Priority.HIGH,
                scope = TaskScope.DAY,
                date = today(),
                timeMinutes = at(120),
            ),
        )
        graph.taskRepository.create(
            TaskDraft(
                title = "Buy oat milk",
                priority = Priority.LOW,
                scope = TaskScope.DAY,
                date = today(),
                timeMinutes = null,
            ),
        )
        graph.taskRepository.create(
            TaskDraft(
                title = "Water the plants",
                priority = Priority.LOW,
                scope = TaskScope.RECURRING_DAILY,
                date = today(),
                timeMinutes = 18 * 60,
            ),
        )
        val tidy = graph.taskRepository.create(
            TaskDraft(
                title = "Tidy the desk",
                priority = Priority.MEDIUM,
                scope = TaskScope.DAY,
                date = today(),
                timeMinutes = at(-180),
            ),
        )
        // One row in the "done" state, to show the sunk + struck-through styling.
        tidy?.let { graph.taskRepository.complete(it) }

        waitFor("the seeded tasks to reach the snapshot") {
            graph.taskRepository.snapshotNow().tasks.size >= 6
        }
        waitFor("the completed task to settle") {
            graph.taskRepository.snapshotNow().tasks.any { it.status == TaskStatus.DONE }
        }
    }

    private fun today(): LocalDate {
        val zone = runCatching { ZoneId.of(SystemClock.timeZoneId()) }.getOrElse { ZoneId.systemDefault() }
        val local = Instant.ofEpochMilli(SystemClock.nowEpochMillis()).atZone(zone)
        return LocalDate(local.year, local.monthValue, local.dayOfMonth)
    }

    private fun nowMinutes(): Int {
        val zone = runCatching { ZoneId.of(SystemClock.timeZoneId()) }.getOrElse { ZoneId.systemDefault() }
        val local = Instant.ofEpochMilli(SystemClock.nowEpochMillis()).atZone(zone)
        return local.hour * 60 + local.minute
    }
}
