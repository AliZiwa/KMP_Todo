package dev.aliziwa.todo.fakes

import dev.aliziwa.todo.platform.AlarmScheduler
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.GenerationOptions
import dev.aliziwa.todo.platform.LlmMessage
import dev.aliziwa.todo.platform.LocalLlmEngine
import dev.aliziwa.todo.platform.AlertPresenter
import dev.aliziwa.todo.platform.AppPaths
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.HttpEngineProvider
import dev.aliziwa.todo.platform.PlatformCapabilities
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.platform.SoundPlayer
import dev.aliziwa.todo.platform.StickySummary
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class FakeClock(var nowMillis: Long, var zoneId: String = "UTC") : Clock {
    override fun nowEpochMillis(): Long = nowMillis
    override fun timeZoneId(): String = zoneId
    fun advanceMinutes(minutes: Int) {
        nowMillis += minutes * 60_000L
    }
}

class RecordingAlarmScheduler : AlarmScheduler {
    val scheduled = mutableMapOf<String, Long>()
    /** Titles handed over by the 3-arg overload (what iOS needs at schedule time). */
    val titles = mutableMapOf<String, String>()
    val cancelled = mutableListOf<String>()
    var cancelAllCount = 0
    var dailyPrompt: Pair<Int, Int>? = null

    override fun schedule(taskId: String, atEpochMillis: Long) {
        scheduled[taskId] = atEpochMillis
    }

    override fun schedule(taskId: String, atEpochMillis: Long, title: String) {
        titles[taskId] = title
        schedule(taskId, atEpochMillis)
    }

    override fun cancel(taskId: String) {
        scheduled.remove(taskId)
        cancelled += taskId
    }

    override fun cancelAll() {
        cancelAllCount++
        scheduled.clear()
    }

    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) {
        dailyPrompt = hourOfDay to minute
    }

    override fun cancelDailyPrompt() {
        dailyPrompt = null
    }
}

class RecordingAlertPresenter : AlertPresenter {
    val shown = mutableListOf<DueAlert>()
    val dismissed = mutableListOf<String>()
    var lastSticky: StickySummary? = null
    var dailyPromptShown = 0

    override fun showDueAlert(alert: DueAlert) {
        shown += alert
    }

    override fun dismissDueAlert(taskId: String) {
        dismissed += taskId
    }

    override fun updateSticky(summary: StickySummary) {
        lastSticky = summary
    }

    override fun showDailyPrompt() {
        dailyPromptShown++
    }

    override fun dismissDailyPrompt() = Unit
}

class RecordingSoundPlayer : SoundPlayer {
    var playing = false
    var playCount = 0
    var stopCount = 0

    override fun playDoorbell(loop: Boolean) {
        playing = true
        playCount++
    }

    override fun stop() {
        playing = false
        stopCount++
    }
}

class FakePaths(private val base: String = "/tmp/personal-todo-test") : AppPaths {
    override val dataDir: String = base
    override val modelsDir: String = "$base/models"
    override val databasePath: String = "$base/todo.db"
}

class FakeHttp : HttpEngineProvider {
    override fun createHttpClient(): HttpClient = HttpClient()
}

class FakeCapabilities(override val platformName: String = "test") : PlatformCapabilities {
    val openedUrls = mutableListOf<String>()
    var launchAtLogin = false

    override suspend fun setLaunchAtLogin(enabled: Boolean): Boolean {
        launchAtLogin = enabled
        return true
    }

    override suspend fun isLaunchAtLoginEnabled(): Boolean = launchAtLogin
    override fun openUrl(url: String) {
        openedUrls += url
    }

    override suspend fun freeSpaceBytes(directory: String): Long? = 100L * 1024 * 1024 * 1024
}

class TestPlatform(
    val clockFake: FakeClock,
    val alarmsFake: RecordingAlarmScheduler = RecordingAlarmScheduler(),
    val alertsFake: RecordingAlertPresenter = RecordingAlertPresenter(),
    val soundFake: RecordingSoundPlayer = RecordingSoundPlayer(),
    val capabilitiesFake: FakeCapabilities = FakeCapabilities(),
) {
    val services: PlatformServices = PlatformServices(
        alarms = alarmsFake,
        alerts = alertsFake,
        sound = soundFake,
        paths = FakePaths(),
        clock = clockFake,
        http = FakeHttp(),
        capabilities = capabilitiesFake,
        localLlmEngine = null,
    )
}

/** Implements ONLY the original 2-arg [AlarmScheduler.schedule]; proves the 3-arg default bridges. */
class LegacyAlarmScheduler : AlarmScheduler {
    val scheduled = mutableMapOf<String, Long>()

    override fun schedule(taskId: String, atEpochMillis: Long) {
        scheduled[taskId] = atEpochMillis
    }

    override fun cancel(taskId: String) {
        scheduled.remove(taskId)
    }

    override fun cancelAll() {
        scheduled.clear()
    }

    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) = Unit
    override fun cancelDailyPrompt() = Unit
}

/** Records what the shared layer asked the engine for, and what it was told to load. */
class FakeLocalLlmEngine(
    private val reply: String = "hello",
    private val failLoadWith: String? = null,
    override val supportedExtensions: List<String> = listOf("gguf"),
) : LocalLlmEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state

    val loadedPaths = mutableListOf<String>()
    val calls = mutableListOf<Pair<List<LlmMessage>, GenerationOptions>>()
    var cancelCount = 0

    override suspend fun load(modelPath: String): Result<Unit> {
        loadedPaths += modelPath
        return if (failLoadWith != null) {
            _state.value = EngineState.Error(failLoadWith)
            Result.failure(IllegalStateException(failLoadWith))
        } else {
            _state.value = EngineState.Ready(modelPath, modelPath.substringAfterLast('/'))
            Result.success(Unit)
        }
    }

    override suspend fun unload() {
        _state.value = EngineState.NotLoaded
    }

    override fun generate(messages: List<LlmMessage>, options: GenerationOptions): Flow<String> {
        calls += messages to options
        return flowOf(reply)
    }

    override fun cancelGeneration() {
        cancelCount++
    }
}
