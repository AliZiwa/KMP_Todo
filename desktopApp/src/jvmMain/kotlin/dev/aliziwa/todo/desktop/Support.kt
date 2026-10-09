package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.AppPaths
import dev.aliziwa.todo.platform.HttpEngineProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock

/**
 * Desktop-local logger. `dev.aliziwa.todo.util.Log` is owned by the shared agent; desktop code
 * writes through this thin wrapper so we never `println` from shared source sets and so the
 * resident (tray-only) process keeps a durable log the user can inspect.
 */
object DesktopLog {
    private val lock = ReentrantLock()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private var writer: PrintWriter? = null

    /** Called once [AppPaths] exist; before that we only log to stderr. */
    fun attachFile(dataDir: String) {
        runCatching {
            val f = File(dataDir, "desktop.log")
            if (f.exists() && f.length() > 4L * 1024 * 1024) {
                File(dataDir, "desktop.log.1").also { if (it.exists()) it.delete() }
                f.renameTo(File(dataDir, "desktop.log.1"))
            }
            lock.lock()
            try {
                writer = PrintWriter(java.io.FileWriter(File(dataDir, "desktop.log"), true), true)
            } finally {
                lock.unlock()
            }
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = "${stamp.format(Date())} $level/$tag: $msg"
        lock.lock()
        try {
            System.err.println(line)
            t?.printStackTrace()
            writer?.let { w ->
                w.println(line)
                t?.printStackTrace(w)
            }
        } finally {
            lock.unlock()
        }
    }
}

/** `~/Library/Application Support/PersonalTodo` layout. */
class DesktopPaths : AppPaths {
    override val dataDir: String =
        File(System.getProperty("user.home"), "Library/Application Support/PersonalTodo")
            .apply { mkdirs() }.absolutePath
    override val modelsDir: String = File(dataDir, "models").apply { mkdirs() }.absolutePath
    override val databasePath: String = File(dataDir, "todo.db").absolutePath

    val lockFile: File get() = File(dataDir, "instance.lock")
    val portFile: File get() = File(dataDir, "instance.port")
    val prefsFile: File get() = File(dataDir, "desktop.properties")
    val devRunScript: File get() = File(dataDir, "run-dev.sh")
}

/**
 * Tiny properties-backed store for things that belong to the *shell* rather than the domain
 * (window geometry, last daily-prompt date fallback). Domain settings live in the shared
 * settings table owned by the shared agent.
 */
class DesktopPrefs(private val file: File) {
    private val props = Properties()
    private val lock = ReentrantLock()

    init {
        runCatching { if (file.exists()) file.inputStream().use(props::load) }
            .onFailure { DesktopLog.w("prefs", "could not read ${file.name}", it) }
    }

    fun getInt(key: String, def: Int): Int = lock.withLockValue { props.getProperty(key)?.toIntOrNull() ?: def }
    fun getFloat(key: String, def: Float): Float = lock.withLockValue { props.getProperty(key)?.toFloatOrNull() ?: def }
    fun getBool(key: String, def: Boolean): Boolean = lock.withLockValue { props.getProperty(key)?.toBooleanStrictOrNull() ?: def }
    fun getString(key: String, def: String?): String? = lock.withLockValue { props.getProperty(key) ?: def }

    fun put(vararg pairs: Pair<String, Any?>) {
        lock.lock()
        try {
            pairs.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v.toString()) }
            runCatching { file.outputStream().use { props.store(it, "PersonalTodo desktop shell state") } }
                .onFailure { DesktopLog.w("prefs", "could not write ${file.name}", it) }
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
        lock()
        return try { block() } finally { unlock() }
    }
}

object DesktopHttp : HttpEngineProvider {
    override fun createHttpClient(): HttpClient = HttpClient(CIO)
}

/** Formats a duration the way the sticky surfaces want it ("12m", "2d 3h", "due 3m ago"). */
object Countdown {
    fun relative(deltaMillis: Long): String {
        val abs = kotlin.math.abs(deltaMillis)
        val totalMinutes = abs / 60_000
        val text = when {
            abs < 60_000 -> "${abs / 1000}s"
            totalMinutes < 60 -> "${totalMinutes}m"
            totalMinutes < 24 * 60 -> {
                val h = totalMinutes / 60
                val m = totalMinutes % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
            else -> {
                val d = totalMinutes / (24 * 60)
                val h = (totalMinutes % (24 * 60)) / 60
                if (h == 0L) "${d}d" else "${d}d ${h}h"
            }
        }
        return text
    }

    /** "in 12m" / "3m ago". */
    fun phrase(dueAtEpochMillis: Long, nowEpochMillis: Long): String {
        val delta = dueAtEpochMillis - nowEpochMillis
        return if (delta >= 0) "in ${relative(delta)}" else "${relative(delta)} ago"
    }
}
