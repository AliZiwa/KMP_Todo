package dev.aliziwa.todo.desktop.llm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the local Ollama endpoint the settings screen offers as the "remote local-network" backend.
 * Skipped (with a note) when no server is running, so CI without Ollama still passes.
 */
class OllamaProbeTest {

    @Test
    fun `probes the local ollama server`() = runBlocking {
        val result = OllamaProbe.probe()
        if (!result.reachable) {
            println("[skip] no Ollama at http://localhost:11434 (${result.detail})")
            return@runBlocking
        }
        println("[ollama] models=${result.models}")
        assertTrue(result.models.isNotEmpty(), "a reachable Ollama should list at least one model")
    }

    @Test
    fun `unreachable endpoint reports a failure instead of throwing`() = runBlocking {
        // Port 1 is never an Ollama server.
        val result = OllamaProbe.probe("http://127.0.0.1:1", timeoutMillis = 1_500)
        assertTrue(!result.reachable)
        assertTrue(result.detail.isNotBlank())
    }
}
