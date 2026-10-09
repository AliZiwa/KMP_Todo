package dev.aliziwa.todo.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aliziwa.todo.App
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.jvmDatabaseBuilder
import dev.aliziwa.todo.platform.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// SKELETON ONLY — desktop agent replaces with real implementations (tray, alerts, sound, llama.cpp, LaunchAgent).
private object StubAlarms : AlarmScheduler {
    override fun schedule(taskId: String, atEpochMillis: Long) {}
    override fun cancel(taskId: String) {}
    override fun cancelAll() {}
    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) {}
    override fun cancelDailyPrompt() {}
}
private object StubAlerts : AlertPresenter {
    override fun showDueAlert(alert: DueAlert) {}
    override fun dismissDueAlert(taskId: String) {}
    override fun updateSticky(summary: StickySummary) {}
    override fun showDailyPrompt() {}
    override fun dismissDailyPrompt() {}
}
private object StubSound : SoundPlayer { override fun playDoorbell(loop: Boolean) {}; override fun stop() {} }
private class DesktopPaths : AppPaths {
    override val dataDir: String = File(System.getProperty("user.home"), "Library/Application Support/PersonalTodo").apply { mkdirs() }.absolutePath
    override val modelsDir: String = File(dataDir, "models").apply { mkdirs() }.absolutePath
    override val databasePath: String = File(dataDir, "todo.db").absolutePath
}
private object DesktopHttp : HttpEngineProvider { override fun createHttpClient() = HttpClient(CIO) }
private object DesktopCaps : PlatformCapabilities { override val platformName = "macOS" }

fun main() {
    val paths = DesktopPaths()
    val graph = TodoApp.start(
        PlatformServices(StubAlarms, StubAlerts, StubSound, paths, SystemClock, DesktopHttp, DesktopCaps, localLlmEngine = null),
        jvmDatabaseBuilder(paths.databasePath),
    )
    application {
        Window(onCloseRequest = ::exitApplication, title = "Personal Todo") { App(graph) }
    }
}
