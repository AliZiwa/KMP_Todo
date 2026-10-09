package dev.aliziwa.todo.platform.ios

import dev.aliziwa.todo.platform.HttpEngineProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/**
 * Ktor client backed by NSURLSession. Used only for (a) user-initiated model downloads and
 * (b) user-configured local-network LLM endpoints — see the offline-first rule in CLAUDE.md.
 *
 * Model downloads are large and resumable, so the request timeout is disabled and only
 * connect/socket timeouts apply.
 */
class DarwinHttpEngineProvider : HttpEngineProvider {
    override fun createHttpClient(): HttpClient = HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE // effectively disabled: resumable model downloads
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }
}
