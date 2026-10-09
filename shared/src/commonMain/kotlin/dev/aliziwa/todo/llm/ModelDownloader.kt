package dev.aliziwa.todo.llm

import dev.aliziwa.todo.platform.AppPaths
import dev.aliziwa.todo.platform.HttpEngineProvider
import dev.aliziwa.todo.platform.PlatformCapabilities
import dev.aliziwa.todo.util.Log
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Preparing(val fileName: String) : DownloadState
    data class Running(
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val resumed: Boolean,
    ) : DownloadState {
        val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    }

    data class Completed(val fileName: String, val path: String) : DownloadState
    data class Failed(val fileName: String, val message: String) : DownloadState
    data class Cancelled(val fileName: String, val keptBytes: Long) : DownloadState
}

/**
 * Resumable model downloader. Streams to `<modelsDir>/<file>.part` with an HTTP `Range` header,
 * reports progress, supports cancel (the partial file is kept so the next run resumes), checks free
 * space when the platform can report it, and refuses files the engine cannot load.
 */
class ModelDownloader(
    private val http: HttpEngineProvider,
    private val paths: AppPaths,
    private val capabilities: PlatformCapabilities,
    private val scope: CoroutineScope,
) {
    private val tag = "ModelDownloader"

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(url: String, allowedExtensions: List<String>, fileNameOverride: String? = null) {
        if (isRunning) {
            Log.w(tag, "download already running; ignoring start()")
            return
        }
        job = scope.launch { runCatching { download(url, allowedExtensions, fileNameOverride) } }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Deletes a partially downloaded file so the next attempt starts clean. */
    suspend fun discardPartial(fileName: String) = withContext(Dispatchers.Default) {
        val part = Path(paths.modelsDir, "$fileName.part")
        runCatching { if (SystemFileSystem.exists(part)) SystemFileSystem.delete(part) }
        _state.value = DownloadState.Idle
    }

    fun reset() {
        if (!isRunning) _state.value = DownloadState.Idle
    }

    suspend fun download(
        url: String,
        allowedExtensions: List<String>,
        fileNameOverride: String? = null,
    ): Result<String> {
        val fileName = fileNameOverride?.takeIf { it.isNotBlank() }
            ?: ModelCatalog.fileNameFromUrl(url)
            ?: return fail("model", "Could not work out a file name from that URL")

        val extension = ModelCatalog.extensionOf(fileName)
        val normalized = allowedExtensions.map { it.lowercase().removePrefix(".") }
        if (normalized.isNotEmpty() && extension !in normalized) {
            return fail(fileName, "This engine loads ${normalized.joinToString("/")} files, not \".$extension\"")
        }

        _state.value = DownloadState.Preparing(fileName)
        val client = runCatching { http.createHttpClient() }
            .getOrElse { return fail(fileName, "No HTTP client: ${it.message}") }

        return try {
            SystemFileSystem.createDirectories(Path(paths.modelsDir))
            val target = Path(paths.modelsDir, fileName)
            val part = Path(paths.modelsDir, "$fileName.part")

            if (SystemFileSystem.exists(target)) {
                _state.value = DownloadState.Completed(fileName, target.toString())
                return Result.success(target.toString())
            }

            val totalBytes = runCatching { client.head(url).contentLength() }.getOrNull()
            var existing = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
            if (totalBytes != null && existing >= totalBytes) {
                existing = 0L
                runCatching { SystemFileSystem.delete(part) }
            }

            if (totalBytes != null) {
                val free = runCatching { capabilities.freeSpaceBytes(paths.modelsDir) }.getOrNull()
                val needed = totalBytes - existing + SAFETY_MARGIN_BYTES
                if (free != null && free < needed) {
                    return fail(
                        fileName,
                        "Not enough free space: need ~${needed / 1_000_000} MB, have ${free / 1_000_000} MB",
                    )
                }
            }

            var downloaded = existing
            _state.value = DownloadState.Running(fileName, downloaded, totalBytes, resumed = existing > 0)

            client.prepareGet(url) {
                if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
            }.execute { response ->
                val partial = response.status == HttpStatusCode.PartialContent
                if (existing > 0 && !partial) {
                    // Server ignored the Range header — start over.
                    runCatching { SystemFileSystem.delete(part) }
                    downloaded = 0
                }
                val append = downloaded > 0
                val total = totalBytes ?: response.contentLength()?.let { it + downloaded }
                val channel = response.bodyAsChannel()
                val sink = SystemFileSystem.sink(part, append = append).buffered()
                try {
                    while (!channel.isClosedForRead) {
                        val buffer = channel.readBuffer(CHUNK_BYTES)
                        val bytes = buffer.readByteArray()
                        if (bytes.isEmpty()) continue
                        sink.write(bytes)
                        downloaded += bytes.size
                        _state.value = DownloadState.Running(fileName, downloaded, total, resumed = existing > 0)
                    }
                    sink.flush()
                } finally {
                    runCatching { sink.close() }
                }
            }

            SystemFileSystem.atomicMove(part, target)
            _state.value = DownloadState.Completed(fileName, target.toString())
            Log.i(tag, "downloaded $fileName ($downloaded bytes)")
            Result.success(target.toString())
        } catch (cancellation: CancellationException) {
            val kept = SystemFileSystem.metadataOrNull(Path(paths.modelsDir, "$fileName.part"))?.size ?: 0L
            _state.value = DownloadState.Cancelled(fileName, kept)
            Log.i(tag, "download cancelled, kept $kept bytes")
            throw cancellation
        } catch (error: Throwable) {
            fail(fileName, error.message ?: error::class.simpleName ?: "Download failed")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun fail(fileName: String, message: String): Result<String> {
        Log.w(tag, "download failed: $message")
        _state.value = DownloadState.Failed(fileName, message)
        return Result.failure(IllegalStateException(message))
    }

    private companion object {
        const val CHUNK_BYTES = 256L * 1024L
        const val SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
    }
}
