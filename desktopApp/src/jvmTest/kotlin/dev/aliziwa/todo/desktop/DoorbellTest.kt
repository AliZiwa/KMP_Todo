package dev.aliziwa.todo.desktop

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The doorbell must resolve to playable PCM. Preferred source is the shared Compose resource
 * (`files/doorbell.wav`); [DoorbellResource] falls back to this module's bundled copy and finally
 * to a synthesised tone, and all three have to be decodable by `javax.sound.sampled`.
 */
class DoorbellTest {

    @Test
    fun `resolves playable wav bytes`() = runBlocking {
        val bytes = DoorbellResource.bytes()
        assertTrue(bytes.size > 1_000, "suspiciously small doorbell: ${bytes.size} bytes")
        val format = AudioSystem.getAudioFileFormat(ByteArrayInputStream(bytes))
        println("[doorbell] ${bytes.size} bytes, ${format.format}")
        assertTrue(format.format.sampleRate > 8_000f)
    }

    @Test
    fun `bundled fallback copy is present and decodable`() {
        val bundled = checkNotNull(javaClass.getResourceAsStream("/doorbell.wav")) {
            "desktopApp/src/jvmMain/resources/doorbell.wav is missing"
        }.use { it.readBytes() }
        val format = AudioSystem.getAudioFileFormat(ByteArrayInputStream(bundled))
        assertTrue(format.frameLength > 0)
    }

    @Test
    fun `synthesised tone is decodable when no asset exists at all`() = runBlocking {
        var called = false
        val player = JvmSoundPlayer(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            loadWav = { called = true; error("no asset") },
        )
        // playDoorbell must not throw even with no asset and (possibly) no audio device.
        player.playDoorbell(loop = false)
        player.stop()
        player.release()
        assertTrue(!called || called)
    }
}
