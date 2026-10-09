package dev.aliziwa.todo.platform.ios

import androidx.room.RoomDatabase
import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.TodoApp
import dev.aliziwa.todo.db.TodoDatabase
import dev.aliziwa.todo.db.iosDatabaseBuilder
import dev.aliziwa.todo.platform.AlarmScheduler
import dev.aliziwa.todo.platform.AlertPresenter
import dev.aliziwa.todo.platform.LocalLlmEngine
import dev.aliziwa.todo.platform.PlatformCapabilities
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.platform.SoundPlayer
import dev.aliziwa.todo.platform.SystemClock

/**
 * Builds the Room builder from [PlatformServices.paths] and starts the shared layer.
 * Idempotent — calling it twice returns the same [AppGraph].
 *
 * Swift: `IosPlatformKt.startTodoApp(services: services)`.
 */
fun startTodoApp(services: PlatformServices): AppGraph =
    TodoApp.start(services, iosDatabaseBuilder(services.paths.databasePath))

/**
 * Convenience factory so Swift only has to implement the ports that genuinely need UIKit.
 * [dev.aliziwa.todo.platform.AppPaths], [dev.aliziwa.todo.platform.Clock] and
 * [dev.aliziwa.todo.platform.HttpEngineProvider] are provided here.
 *
 * Swift: `IosPlatformKt.buildIosPlatformServices(alarms:alerts:sound:capabilities:localLlmEngine:appGroupId:)`.
 */
fun buildIosPlatformServices(
    alarms: AlarmScheduler,
    alerts: AlertPresenter,
    sound: SoundPlayer,
    capabilities: PlatformCapabilities,
    localLlmEngine: LocalLlmEngine?,
    appGroupId: String?,
): PlatformServices = PlatformServices(
    alarms = alarms,
    alerts = alerts,
    sound = sound,
    paths = IosAppPaths(appGroupId = appGroupId),
    clock = SystemClock,
    http = DarwinHttpEngineProvider(),
    capabilities = capabilities,
    localLlmEngine = localLlmEngine,
)

/** Exposed for tooling/tests that want the builder without starting the app. */
fun iosRoomBuilder(databasePath: String): RoomDatabase.Builder<TodoDatabase> =
    iosDatabaseBuilder(databasePath)
