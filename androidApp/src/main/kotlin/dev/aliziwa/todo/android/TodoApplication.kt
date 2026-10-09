package dev.aliziwa.todo.android

import android.app.Application
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.androidDatabaseBuilder
import dev.aliziwa.todo.platform.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

// SKELETON ONLY — android agent replaces stubs with real implementations.
class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val paths = object : AppPaths {
            override val dataDir: String = filesDir.absolutePath
            override val modelsDir: String = File(filesDir, "models").apply { mkdirs() }.absolutePath
            override val databasePath: String = getDatabasePath("todo.db").absolutePath
        }
        TodoApp.start(
            PlatformServices(
                alarms = object : AlarmScheduler {
                    override fun schedule(taskId: String, atEpochMillis: Long) {}
                    override fun cancel(taskId: String) {}
                    override fun cancelAll() {}
                    override fun scheduleDailyPrompt(hourOfDay: Int, minute: Int) {}
                    override fun cancelDailyPrompt() {}
                },
                alerts = object : AlertPresenter {
                    override fun showDueAlert(alert: DueAlert) {}
                    override fun dismissDueAlert(taskId: String) {}
                    override fun updateSticky(summary: StickySummary) {}
                    override fun showDailyPrompt() {}
                    override fun dismissDailyPrompt() {}
                },
                sound = object : SoundPlayer { override fun playDoorbell(loop: Boolean) {}; override fun stop() {} },
                paths = paths,
                clock = SystemClock,
                http = object : HttpEngineProvider { override fun createHttpClient() = HttpClient(OkHttp) },
                capabilities = object : PlatformCapabilities { override val platformName = "Android" },
                localLlmEngine = null,
            ),
            androidDatabaseBuilder(this, paths.databasePath),
        )
    }
}
