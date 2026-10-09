package dev.aliziwa.todo.util

/** Severity levels understood by [Log]. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Minimal multiplatform logger for shared code. Platforms may install a [sink] to forward
 * into Logcat / os_log / a file; by default it prints.
 *
 * Shared code must never call `println` directly — use this.
 */
object Log {
    var minLevel: LogLevel = LogLevel.DEBUG
    var sink: ((LogLevel, String, String, Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) = emit(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = emit(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = emit(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = emit(LogLevel.ERROR, tag, message, error)

    private fun emit(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val installed = sink
        if (installed != null) {
            installed(level, tag, message, error)
            return
        }
        @Suppress("ForbiddenMethodCall")
        println("[${level.name}] $tag: $message" + (error?.let { " | ${it::class.simpleName}: ${it.message}" } ?: ""))
    }
}
