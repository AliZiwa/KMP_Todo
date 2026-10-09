package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.resources.Res

/**
 * Resolves the doorbell audio.
 *
 * Preferred source is the shared Compose multiplatform resource (`files/doorbell.wav`, accessor
 * package `dev.aliziwa.todo.resources`) so every platform rings with the same asset. The shared
 * agent owns `shared/src/commonMain/composeResources`; until that file lands we fall back to the
 * copy bundled in this module's classpath, and finally to a synthesised tone.
 */
object DoorbellResource {
    private const val TAG = "doorbell"
    const val SHARED_PATH = "files/doorbell.wav"
    private const val CLASSPATH_FALLBACK = "/doorbell.wav"

    suspend fun bytes(): ByteArray {
        runCatching { Res.readBytes(SHARED_PATH) }
            .onSuccess { if (it.isNotEmpty()) return it }
            .onFailure { DesktopLog.i(TAG, "shared resource $SHARED_PATH unavailable (${it.message}); using bundled copy") }

        runCatching {
            DoorbellResource::class.java.getResourceAsStream(CLASSPATH_FALLBACK)?.use { it.readBytes() }
        }.onSuccess { if (it != null && it.isNotEmpty()) return it }
            .onFailure { DesktopLog.w(TAG, "bundled $CLASSPATH_FALLBACK unreadable", it) }

        error("no doorbell audio available")
    }
}
