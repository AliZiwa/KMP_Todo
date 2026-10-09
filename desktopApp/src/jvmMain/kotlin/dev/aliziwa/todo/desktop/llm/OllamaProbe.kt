package dev.aliziwa.todo.desktop.llm

import dev.aliziwa.todo.desktop.DesktopLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reachability + model listing for a local/LAN Ollama (or any OpenAI-compatible server exposing
 * `/api/tags`). The shared `llm/` layer owns the actual Koog `OllamaClient`; this is only the
 * "Test connection" helper the desktop settings screen needs, and it is the one network call the
 * offline-first rule permits (user-configured local endpoint).
 */
object OllamaProbe {
    private const val TAG = "ollama"

    data class Result(val reachable: Boolean, val models: List<String>, val detail: String)

    suspend fun probe(baseUrl: String = "http://localhost:11434", timeoutMillis: Long = 4_000): Result =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/api/tags"
            val outcome = withTimeoutOrNull(timeoutMillis) {
                runCatching {
                    HttpClient(CIO).use { client -> client.get(url).bodyAsText() }
                }
            }
            when {
                outcome == null -> Result(false, emptyList(), "timed out after ${timeoutMillis}ms")
                outcome.isFailure -> {
                    val message = outcome.exceptionOrNull()?.message ?: "unreachable"
                    DesktopLog.i(TAG, "$url not reachable: $message")
                    Result(false, emptyList(), message)
                }
                else -> {
                    val body = outcome.getOrNull().orEmpty()
                    val models = MODEL_NAME.findAll(body).map { it.groupValues[1] }.distinct().toList()
                    DesktopLog.i(TAG, "$url reachable; models=$models")
                    Result(true, models, "ok")
                }
            }
        }

    // Deliberately regex rather than a serializable DTO: this is a liveness check, not a data path.
    private val MODEL_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
}
