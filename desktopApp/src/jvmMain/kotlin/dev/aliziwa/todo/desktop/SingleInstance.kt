package dev.aliziwa.todo.desktop

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * Single-instance guard.
 *
 * The first process takes an exclusive [FileLock] on `instance.lock` and publishes a loopback
 * control port in `instance.port`. A later launch fails the lock, connects to that port and sends
 * a command (`FRONT`, or `OPEN <route>`), then exits — so launching the app again while it sits
 * resident in the menu bar simply focuses the existing window.
 *
 * The lock dies with the process, so a crash never leaves the app unlaunchable. A stale
 * `instance.port` is tolerated (the connect times out and we log it).
 */
class SingleInstance private constructor(
    /** false => another instance is already running and has been signalled; this process must exit. */
    val isPrimary: Boolean,
    private val lockFile: File?,
    private val portFile: File?,
    private val raf: RandomAccessFile?,
    private val lock: FileLock?,
    private val server: ServerSocket?,
) : AutoCloseable {

    companion object {
        private const val TAG = "single-instance"
        const val CMD_FRONT = "FRONT"
        const val CMD_OPEN = "OPEN"

        fun acquire(paths: DesktopPaths, command: String = CMD_FRONT): SingleInstance {
            val raf = runCatching { RandomAccessFile(paths.lockFile, "rw") }.getOrElse {
                DesktopLog.w(TAG, "lock file unusable; running without a single-instance guard", it)
                return SingleInstance(true, null, null, null, null, null)
            }
            val lock = runCatching { raf.channel.tryLock() }.getOrNull()
            if (lock == null) {
                DesktopLog.i(TAG, "another instance holds the lock; signalling it with '$command'")
                signalExisting(paths.portFile, command)
                runCatching { raf.close() }
                return SingleInstance(false, null, null, null, null, null)
            }
            val server = runCatching {
                ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
            }.getOrElse {
                DesktopLog.w(TAG, "no control socket; later launches will not be able to focus us", it)
                null
            }
            if (server != null) {
                runCatching { paths.portFile.writeText("${server.localPort}\n${ProcessHandle.current().pid()}\n") }
                    .onFailure { DesktopLog.w(TAG, "could not publish control port", it) }
                DesktopLog.i(TAG, "primary instance; control port ${server.localPort}")
            }
            return SingleInstance(true, paths.lockFile, paths.portFile, raf, lock, server)
        }

        private fun signalExisting(portFile: File, command: String) {
            val port = runCatching { portFile.readLines().firstOrNull()?.trim()?.toIntOrNull() }.getOrNull()
            if (port == null) {
                DesktopLog.w(TAG, "no control port recorded; cannot focus the running instance")
                return
            }
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_500)
                    s.soTimeout = 1_500
                    s.getOutputStream().apply {
                        write((command + "\n").toByteArray())
                        flush()
                    }
                }
                DesktopLog.i(TAG, "sent '$command' to the running instance on port $port")
            }.onFailure { DesktopLog.w(TAG, "control port $port unreachable (stale port file?)", it) }
        }
    }

    /**
     * Serves control commands on a daemon thread, so it is alive before (and after) Compose's
     * `application { }` composition exists.
     */
    fun listen(onCommand: (command: String, argument: String?) -> Unit) {
        val srv = server ?: return
        Thread({
            while (!srv.isClosed) {
                val socket = runCatching { srv.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { s ->
                        s.soTimeout = 1_500
                        val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim().orEmpty()
                        if (line.isNotEmpty()) {
                            val cmd = line.substringBefore(' ')
                            val arg = line.substringAfter(' ', "").trim().ifEmpty { null }
                            DesktopLog.i(TAG, "control command: $cmd ${arg ?: ""}")
                            onCommand(cmd, arg)
                        }
                    }
                }.onFailure { DesktopLog.w(TAG, "control connection failed", it) }
            }
        }, "todo-single-instance").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { raf?.close() }
        runCatching { portFile?.takeIf { it.exists() }?.delete() }
        runCatching { lockFile?.takeIf { it.exists() }?.delete() }
    }
}
