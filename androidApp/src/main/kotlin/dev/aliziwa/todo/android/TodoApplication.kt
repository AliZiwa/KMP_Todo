package dev.aliziwa.todo.android

import android.app.Application
import android.util.Log
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.androidDatabaseBuilder
import dev.aliziwa.todo.platform.PlatformServices

/**
 * Process entry point. Runs before every Activity, Service and BroadcastReceiver in the app, so
 * by the time an alarm receiver fires the shared graph already exists.
 *
 * Deliberately cheap: it wires ports, creates notification channels and starts the foreground
 * service. No disk or network work happens here.
 */
class TodoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Channels.createAll(this)

        val paths = AndroidAppPaths(this)
        val doorbell = DoorbellPlayer(this)
        val alerts = AndroidAlertPresenter(this, doorbell)
        val alarms = AndroidAlarmScheduler(this)
        val llm = LiteRtLlmEngine(this)

        AndroidServices.alarms = alarms
        AndroidServices.alerts = alerts
        AndroidServices.doorbell = doorbell
        AndroidServices.llm = llm

        TodoApp.start(
            PlatformServices(
                alarms = alarms,
                alerts = alerts,
                sound = doorbell,
                paths = paths,
                clock = AndroidClock,
                http = AndroidHttpEngineProvider,
                capabilities = AndroidCapabilities(this),
                localLlmEngine = llm,
            ),
            androidDatabaseBuilder(this, paths.databasePath),
        )

        Log.i(TAG, "started; data=${paths.dataDir} models=${paths.modelsDir}")

        // Deliberately NOT starting StickyForegroundService here. When the process is cold-started
        // by an alarm, Application.onCreate runs before the receiver's onReceive, i.e. before the
        // alarm's foreground-service exemption applies, and the start throws
        // ForegroundServiceStartNotAllowedException (observed on API 37). Every path that is
        // allowed to start it does so itself: MainActivity.onResume, AlarmReceiver, BootReceiver,
        // and AndroidAlertPresenter.
    }

    private companion object {
        const val TAG = "TodoApplication"
    }
}
