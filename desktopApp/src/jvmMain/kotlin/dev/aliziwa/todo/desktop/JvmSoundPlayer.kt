package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.SoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Doorbell playback through `javax.sound.sampled`.
 *
 * A single [Clip] instance is reused, so two simultaneous alerts can never overlap sounds
 * (ARCHITECTURE: "single looping player instance"). Looping is done by us rather than
 * `Clip.loop(LOOP_CONTINUOUSLY)` because the spec wants a 3 second gap between rings.
 *
 * Every audio call is wrapped: a machine with no output device, a muted/exclusive device, or a
 * headless JVM must never take the app down — we log once and go silent.
 */
class JvmSoundPlayer(
    private val scope: CoroutineScope,
    /** Loaded lazily; see [DoorbellResource]. */
    private val loadWav: suspend () -> ByteArray = { DoorbellResource.bytes() },
) : SoundPlayer {

    private val mutex = Mutex()
    private var job: Job? = null

    @Volatile private var clip: Clip? = null
    @Volatile private var wav: ByteArray? = null
    @Volatile private var audioBroken = false

    override fun playDoorbell(loop: Boolean) {
        if (audioBroken) return
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoinQuietly()
            mutex.withLock {
                val clip = ensureClip() ?: return@withLock
                val ringMillis = (clip.microsecondLength / 1000).coerceAtLeast(300)
                do {
                    if (!isActive) break
                    runCatching {
                        clip.stop()
                        clip.framePosition = 0
                        clip.start()
                    }.onFailure {
                        DesktopLog.w(TAG, "doorbell playback failed", it)
                        audioBroken = true
                        return@withLock
                    }
                    delay(ringMillis)
                    if (!loop) break
                    delay(GAP_MILLIS)
                } while (isActive)
            }
        }
    }

    override fun stop() {
        val current = job
        job = null
        scope.launch(Dispatchers.IO) {
            current?.cancelAndJoinQuietly()
            runCatching { clip?.stop() }
            runCatching { clip?.framePosition = 0 }
        }
    }

    /** Frees the audio line; call on app exit. */
    fun release() {
        runCatching { clip?.stop() }
        runCatching { clip?.close() }
        clip = null
    }

    private suspend fun ensureClip(): Clip? {
        clip?.let { return it }
        val bytes = wav ?: runCatching { loadWav() }
            .onFailure { DesktopLog.w(TAG, "could not read doorbell.wav; synthesising a tone", it) }
            .getOrNull()
            ?: synthesizedDoorbell()
        wav = bytes
        return runCatching {
            val stream: AudioInputStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val info = DataLine.Info(Clip::class.java, stream.format)
            if (!AudioSystem.isLineSupported(info)) {
                DesktopLog.w(TAG, "no audio line supports ${stream.format}; doorbell disabled")
                audioBroken = true
                return null
            }
            (AudioSystem.getLine(info) as Clip).also {
                it.open(stream)
                clip = it
            }
        }.onFailure {
            DesktopLog.w(TAG, "no usable audio device; doorbell disabled for this session", it)
            audioBroken = true
        }.getOrNull()
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        runCatching {
            cancel()
            join()
        }
    }

    private companion object {
        const val TAG = "sound"
        const val GAP_MILLIS = 3_000L

        /**
         * Last-resort doorbell: a two-tone decaying bell rendered to an in-memory 16-bit mono WAV.
         * Used only when neither the shared Compose resource nor the bundled classpath copy exists.
         */
        fun synthesizedDoorbell(): ByteArray {
            val rate = 44_100
            fun tone(freq: Double, seconds: Double, decay: Double): DoubleArray {
                val n = (rate * seconds).toInt()
                return DoubleArray(n) { i ->
                    val t = i.toDouble() / rate
                    val env = 0.9 * exp(-decay * t)
                    env * (sin(2 * PI * freq * t) + 0.3 * sin(4 * PI * freq * t) * exp(-2 * decay * t)) / 1.3
                }
            }
            val samples = tone(987.77, 0.6, 5.0) + tone(783.99, 1.0, 4.0)
            val pcm = ByteArrayOutputStream(samples.size * 2)
            samples.forEach { s ->
                val v = (s.coerceIn(-1.0, 1.0) * 32_767).toInt()
                pcm.write(v and 0xFF)
                pcm.write((v shr 8) and 0xFF)
            }
            val data = pcm.toByteArray()
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val out = ByteArrayOutputStream()
            AudioSystem.write(
                AudioInputStream(ByteArrayInputStream(data), format, (data.size / 2).toLong()),
                javax.sound.sampled.AudioFileFormat.Type.WAVE,
                out,
            )
            return out.toByteArray()
        }
    }
}
