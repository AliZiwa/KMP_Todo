package dev.aliziwa.todo.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Looping doorbell on the ALARM stream.
 *
 * `USAGE_ALARM` matters for three reasons: it is audible when the ringer is silenced, it is
 * audible in Do Not Disturb, and it is allowed to start from the background (unlike media).
 * A single [MediaPlayer] instance is reused so two simultaneous due tasks cannot overlap sounds.
 */
class DoorbellPlayer(private val context: Context) : dev.aliziwa.todo.platform.SoundPlayer {

    private val lock = Any()
    private var player: MediaPlayer? = null

    val isPlaying: Boolean
        get() = synchronized(lock) { player?.isPlaying == true }

    override fun playDoorbell(loop: Boolean) {
        synchronized(lock) {
            // Already ringing: keep the existing loop rather than restarting it mid-chime.
            if (player != null) {
                if (loop) return
                releaseLocked()
            }
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.isLooping = loop
                mp.setOnCompletionListener { if (!loop) stop() }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    stop()
                    true
                }
                context.resources.openRawResourceFd(R.raw.doorbell).use { fd ->
                    mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                mp.prepare()
                // Make sure the alarm stream is not muted to zero by an earlier media session.
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                    Log.w(TAG, "alarm stream volume is 0 - the alert will be silent")
                }
                mp.start()
                player = mp
                Log.i(TAG, "doorbell started (loop=$loop)")
            } catch (t: Throwable) {
                // No audio device, missing codec, OOM: the visual alert must still happen.
                Log.e(TAG, "doorbell failed to start", t)
                releaseLocked()
            }
        }
    }

    override fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        val mp = player ?: return
        player = null
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
        Log.i(TAG, "doorbell stopped")
    }

    private companion object {
        const val TAG = "TodoDoorbell"
    }
}
