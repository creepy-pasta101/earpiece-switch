package dev.creepypasta.earpiece.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Plays a short synthesised two-tone confirmation sweep through whatever route is
 * currently active. No bundled asset: the PCM buffer is generated at runtime.
 *
 * The whole player state is the single nullable [track] field, so [start] is
 * idempotent-safe and [stop]/[release] are safe to call at any time, any number
 * of times.
 */
class TestTonePlayer {

    private val handler = Handler(Looper.getMainLooper())

    /** Non-null exactly while a tone is playing or about to play. */
    private var track: AudioTrack? = null

    /** Posted when the buffer has finished playing; cancels itself via [stop]. */
    private val stopRunnable = Runnable { stop() }

    /**
     * Synthesises and plays the tone.
     *
     * @return true when playback started, false when a tone is already playing,
     *         the platform refused the track, or construction threw.
     */
    fun start(): Boolean {
        if (track != null) return false

        val samples = try {
            buildTone()
        } catch (e: RuntimeException) {
            Log.w(TAG, "tone synthesis failed", e)
            return false
        }

        var created: AudioTrack? = null
        try {
            val minBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                Log.w(TAG, "getMinBufferSize returned $minBufferBytes")
                return false
            }

            val bytesNeeded = samples.size * BYTES_PER_SAMPLE
            val bufferSizeBytes = max(minBufferBytes, bytesNeeded)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            created = audioTrack

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack not initialized")
                return false
            }

            // The buffer is sized to hold the whole sweep, so this write copies
            // straight through without blocking on playback progress.
            val written = audioTrack.write(samples, 0, samples.size)
            if (written <= 0) {
                Log.w(TAG, "AudioTrack.write returned $written")
                return false
            }

            audioTrack.play()
            track = audioTrack
            handler.postDelayed(stopRunnable, STOP_DELAY_MS)
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "tone playback denied", e)
            return false
        } catch (e: RuntimeException) {
            Log.w(TAG, "tone playback failed", e)
            return false
        } finally {
            // Any early return above must not leak the track.
            if (track == null && created != null) {
                releaseQuietly(created)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        val audioTrack = track ?: return
        track = null
        releaseQuietly(audioTrack)
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    // --------------------------------------------------------------- synthesis

    /**
     * ~1.1 s mono buffer: 350 ms of 660 Hz, 50 ms gap, 350 ms of 880 Hz, then
     * silence. Each tone is faded in/out over 10 ms to avoid clicks.
     */
    private fun buildTone(): ShortArray {
        val samples = ShortArray(TOTAL_SAMPLES)

        fillTone(samples, offset = 0, length = TONE_SAMPLES, frequencyHz = TONE_A_HZ)
        // The 50 ms gap between the two tones is left as silence.
        fillTone(
            samples,
            offset = TONE_SAMPLES + GAP_SAMPLES,
            length = TONE_SAMPLES,
            frequencyHz = TONE_B_HZ,
        )
        // The tail after the second tone remains silent.
        return samples
    }

    private fun fillTone(buffer: ShortArray, offset: Int, length: Int, frequencyHz: Double) {
        val amplitude = Short.MAX_VALUE * AMPLITUDE
        val angularStep = 2.0 * PI * frequencyHz / SAMPLE_RATE
        for (i in 0 until length) {
            val envelope = fadeEnvelope(i, length)
            val value = amplitude * envelope * sin(angularStep * i)
            val index = offset + i
            if (index in buffer.indices) {
                buffer[index] = value.toInt().coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt(),
                ).toShort()
            }
        }
    }

    /** Linear 10 ms fade-in at the head and fade-out at the tail of a tone. */
    private fun fadeEnvelope(index: Int, length: Int): Double {
        if (index < FADE_SAMPLES) {
            return index.toDouble() / FADE_SAMPLES
        }
        val fromEnd = length - 1 - index
        if (fromEnd < FADE_SAMPLES) {
            return fromEnd.toDouble() / FADE_SAMPLES
        }
        return 1.0
    }

    private fun releaseQuietly(audioTrack: AudioTrack) {
        try {
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause()
            }
            audioTrack.flush()
        } catch (e: RuntimeException) {
            Log.w(TAG, "tone pause/flush failed", e)
        }
        try {
            audioTrack.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "tone release failed", e)
        }
    }

    private companion object {
        const val TAG = "Earpiece"

        const val SAMPLE_RATE = 44100
        const val BYTES_PER_SAMPLE = 2

        const val TONE_A_HZ = 660.0
        const val TONE_B_HZ = 880.0

        const val TONE_MS = 350
        const val GAP_MS = 50
        const val TOTAL_MS = 1100
        const val FADE_MS = 10

        /** Audible but not harsh: ~0.35 of full scale. */
        const val AMPLITUDE = 0.35

        const val STOP_DELAY_MS = 1300L

        val TONE_SAMPLES: Int = SAMPLE_RATE * TONE_MS / 1000
        val GAP_SAMPLES: Int = SAMPLE_RATE * GAP_MS / 1000
        val TOTAL_SAMPLES: Int = SAMPLE_RATE * TOTAL_MS / 1000
        val FADE_SAMPLES: Int = SAMPLE_RATE * FADE_MS / 1000
    }
}
