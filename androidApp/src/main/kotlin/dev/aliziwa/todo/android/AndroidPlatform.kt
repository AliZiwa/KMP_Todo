package dev.aliziwa.todo.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.aliziwa.todo.platform.AppPaths
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.platform.HttpEngineProvider
import dev.aliziwa.todo.platform.PlatformCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Everything lives under the app's private `filesDir`, so nothing needs storage permissions. */
class AndroidAppPaths(context: Context) : AppPaths {
    override val dataDir: String =
        File(context.filesDir, "todo").apply { mkdirs() }.absolutePath
    override val modelsDir: String =
        File(context.filesDir, "models").apply { mkdirs() }.absolutePath
    override val databasePath: String =
        File(dataDir, "todo.db").absolutePath
}

/**
 * Wall-clock time. [timeZoneId] is read fresh every call (and the JVM default is cleared on
 * `TIMEZONE_CHANGED`, see [BootReceiver]) so a travelling device re-renders correctly.
 */
object AndroidClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun timeZoneId(): String = TimeZone.getDefault().id
}

/**
 * OkHttp engine. Long read timeout because the only traffic is large model downloads;
 * the downloader itself streams and resumes.
 */
object AndroidHttpEngineProvider : HttpEngineProvider {
    override fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(true)
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(120, TimeUnit.SECONDS)
            }
        }
    }
}

class AndroidCapabilities(private val context: Context) : PlatformCapabilities {
    override val platformName: String = "Android"

    override val appVersion: String by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName} ($code)"
        }.getOrDefault("")
    }

    /** Android has no launch-at-login concept; the foreground service + BootReceiver cover it. */
    override suspend fun setLaunchAtLogin(enabled: Boolean): Boolean = false
    override suspend fun isLaunchAtLoginEnabled(): Boolean = true

    override fun bringToFront() {
        runCatching {
            context.startActivity(
                MainActivity.intent(context, route = null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
        }
    }

    /** Free bytes on the volume holding [directory]; used to refuse an oversized model download. */
    override suspend fun freeSpaceBytes(directory: String): Long? = runCatching {
        val stat = android.os.StatFs(directory)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()

    override fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing sensible to do, and this must never crash the app.
        }
    }
}
