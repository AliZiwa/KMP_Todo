package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.jvmDatabaseBuilder
import dev.aliziwa.todo.platform.AppPaths
import dev.aliziwa.todo.platform.LocalLlmEngine
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.platform.SoundPlayer
import dev.aliziwa.todo.platform.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock

/** Records the port calls instead of touching the audio device, so tests can assert on them. */
internal class RecordingSoundPlayer : SoundPlayer {
    val events = CopyOnWriteArrayList<String>()
    override fun playDoorbell(loop: Boolean) {
        events += "play(loop=$loop)"
    }
    override fun stop() {
        events += "stop"
    }
    fun played() = events.any { it.startsWith("play(") }
    fun stopped() = events.contains("stop")
    fun clear() = events.clear()
}

/**
 * A real [TodoApp] process graph wired to the real desktop ports, pointed at a throwaway database.
 *
 * Everything the production app injects is used verbatim — [DesktopAlarmScheduler],
 * [DesktopAlertPresenter], [DesktopUiController], [DesktopCapabilities], [DesktopHttp] — except the
 * sound player, which is swapped for [RecordingSoundPlayer] so "did the doorbell stop?" is
 * assertable. That makes these integration tests exercise the same code path as a click on the
 * alert window, minus Compose itself.
 */
internal class TestHarness(
    private val dir: File,
    private val keepDir: Boolean,
    val graph: AppGraph,
    val ui: DesktopUiController,
    val sound: RecordingSoundPlayer,
    val alarms: DesktopAlarmScheduler,
    val scope: CoroutineScope,
) : AutoCloseable {

    override fun close() {
        runCatching { alarms.shutdown() }
        runCatching { graph.appScope.cancel() }
        runCatching { scope.cancel() }
        runCatching { graph.llmRuntimeFactory.close() }
        runCatching { graph.database.close() }
        DesktopTestHarness.resetTodoApp()
        if (!keepDir) runCatching { dir.deleteRecursively() }
    }
}

internal object DesktopTestHarness {
    /** `TodoApp` is a process-wide singleton; serialise harness use and reset it between tests. */
    private val lock = ReentrantLock()

    fun resetTodoApp() {
        runCatching {
            val field = TodoApp::class.java.getDeclaredField("graph")
            field.isAccessible = true
            field.set(TodoApp, null)
        }
    }

    /**
     * @param dir reuse an existing data directory (and therefore its `todo.db`) so a test can
     *   persist settings with one graph and then observe what a *fresh* `TodoApp.start` does with
     *   them — which is how "does a saved model path load itself?" is verified.
     * @param keepDir leave the directory behind on close, for the same reason.
     */
    fun <T> withGraph(
        localLlmEngine: LocalLlmEngine? = null,
        dir: File? = null,
        keepDir: Boolean = false,
        block: (TestHarness) -> T,
    ): T {
        lock.lock()
        try {
            resetTodoApp()
            val dir = dir?.apply { mkdirs() } ?: Files.createTempDirectory("todo-desktop-it").toFile()
            val paths = object : AppPaths {
                override val dataDir: String = dir.absolutePath
                override val modelsDir: String = File(dir, "models").apply { mkdirs() }.absolutePath
                override val databasePath: String = File(dir, "todo.db").absolutePath
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ui = DesktopUiController()
            val sound = RecordingSoundPlayer()
            val alerts = DesktopAlertPresenter(ui, sound)
            val capabilities = DesktopCapabilities(DesktopPaths(), ui)
            val alarms = DesktopAlarmScheduler(
                scope = scope,
                clock = SystemClock,
                onAlarm = { taskId -> TodoApp.onAlarmFired(taskId) },
                onDailyPrompt = { TodoApp.onDailyPromptFired() },
                onWake = { },
                sliceMillis = 200,
            )
            val graph = TodoApp.start(
                PlatformServices(
                    alarms = alarms,
                    alerts = alerts,
                    sound = sound,
                    paths = paths,
                    clock = SystemClock,
                    http = DesktopHttp,
                    capabilities = capabilities,
                    localLlmEngine = localLlmEngine,
                ),
                jvmDatabaseBuilder(paths.databasePath),
            )
            alarms.start()
            return TestHarness(dir, keepDir, graph, ui, sound, alarms, scope).use(block)
        } finally {
            lock.unlock()
        }
    }
}

/** Polls [predicate] until true, or fails with [what]. */
internal suspend fun waitFor(what: String, timeoutMillis: Long = 10_000, predicate: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        delay(50)
    }
    throw AssertionError("timed out after ${timeoutMillis}ms waiting for: $what")
}

/** Polls [supplier] until it returns non-null, or fails with [what]. */
internal suspend fun <T : Any> waitForValue(
    what: String,
    timeoutMillis: Long = 10_000,
    supplier: suspend () -> T?,
): T {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        supplier()?.let { return it }
        delay(100)
    }
    throw AssertionError("timed out after ${timeoutMillis}ms waiting for: $what")
}

/**
 * A [LocalLlmEngine] whose load takes [loadDelayMillis], so the "Loading the model…" banner is
 * observable without racing a real 300ms gguf load. [reply] is what every generation streams.
 */
internal class SlowFakeEngine(
    private val loadDelayMillis: Long = 1_200,
    private val reply: String = """{"reply":"ok","ops":[]}""",
) : LocalLlmEngine {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.aliziwa.todo.platform.EngineState>(
        dev.aliziwa.todo.platform.EngineState.NotLoaded,
    )
    override val state: kotlinx.coroutines.flow.StateFlow<dev.aliziwa.todo.platform.EngineState> = _state
    override val supportedExtensions: List<String> = listOf("gguf")

    /** Every GenerationOptions we were handed, so tests can assert on jsonMode / jsonSchema. */
    val optionsSeen = CopyOnWriteArrayList<dev.aliziwa.todo.platform.GenerationOptions>()
    @Volatile var loadCount: Int = 0

    override suspend fun load(modelPath: String): Result<Unit> {
        loadCount++
        _state.value = dev.aliziwa.todo.platform.EngineState.Loading(null)
        delay(loadDelayMillis)
        _state.value = dev.aliziwa.todo.platform.EngineState.Ready(modelPath, File(modelPath).name)
        return Result.success(Unit)
    }

    override suspend fun unload() {
        _state.value = dev.aliziwa.todo.platform.EngineState.NotLoaded
    }

    override fun generate(
        messages: List<dev.aliziwa.todo.platform.LlmMessage>,
        options: dev.aliziwa.todo.platform.GenerationOptions,
    ): kotlinx.coroutines.flow.Flow<String> {
        optionsSeen += options
        return kotlinx.coroutines.flow.flowOf(reply)
    }

    override fun cancelGeneration() = Unit
}
