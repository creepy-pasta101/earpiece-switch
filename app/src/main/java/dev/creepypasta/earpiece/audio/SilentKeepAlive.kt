package dev.creepypasta.earpiece.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Holds the communication audio path open by streaming silence through it.
 *
 * WHY a silent stream, and not just the audio mode: setting
 * [android.media.AudioManager.MODE_IN_COMMUNICATION] only asks the platform for the
 * communication path. Another app can reclaim `MODE_NORMAL` at any moment (a media app taking
 * focus, an OEM audio policy quirk), and every reclaim drops our route back to the loudspeaker —
 * after which re-setting the mode reconfigures the path and costs about a second of dropout. A
 * loop of mode re-assertions cannot win that race. An active [AudioTrack] whose usage is
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION] does: while it is playing, the platform keeps the
 * communication path (and with it the mode) routed to our preferred device for as long as we hold
 * the track, so other apps have far less room to pull the route out from under us.
 *
 * The stream is silent by construction: the buffer handed to [AudioTrack.write] is a zero-filled
 * [ShortArray] and is never modified, so nothing is audible even at full volume.
 *
 * The whole player state is the single nullable [track] field plus the [running] flag, so [start]
 * is idempotent and [stop]/[release] are safe to call at any time, any number of times.
 */
class SilentKeepAlive(private val context: Context) {

    /** Non-null exactly while the keep-alive track is playing or about to play. */
    private var track: AudioTrack? = null

    /** The writer loop's only stop condition; cleared before the track is torn down. */
    @Volatile
    private var running = false

    /** Owned by [start]; exits on its own once [running] clears. */
    private var writer: Thread? = null

    /**
     * Opens the silent stream on the communication path and pins it to [earpiece] when given.
     *
     * @param earpiece the device to route the stream to, or null to let the platform pick the
     *        communication device.
     * @return true when a stream is running (or was already running), false when the platform
     *         refused the track or construction/playback threw.
     */
    fun start(earpiece: AudioDeviceInfo?): Boolean {
        if (track != null) return true

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

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
            // Low-latency is what keeps a communication stream on the fast path; guarded by the
            // API level the same way the rest of the codebase guards new platform calls.
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val audioTrack = builder.build()
            created = audioTrack

            // Pinning the device is best-effort: the mode plus the voice-communication usage
            // already give us the path, so a refusal here is not a reason to fail the start.
            if (earpiece != null) {
                try {
                    audioTrack.setPreferredDevice(earpiece)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "keep-alive setPreferredDevice failed", e)
                }
            }

            audioTrack.play()

            // minBufferBytes counts bytes; a 16-bit sample is two of them, so the writer hands the
            // platform exactly one buffer's worth of samples per pass.
            val silence = ShortArray(minBufferBytes / BYTES_PER_SAMPLE)
            track = audioTrack
            running = true
            writer = Thread({ writeLoop(audioTrack, silence) }, "EarpieceKeepAlive").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Silent keep-alive started (earpiece=$earpiece)")
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "keep-alive start denied", e)
            return false
        } catch (e: RuntimeException) {
            Log.w(TAG, "keep-alive start failed", e)
            return false
        } finally {
            // Any early return above must not leak the track.
            if (track == null && created != null) {
                releaseQuietly(created)
            }
        }
    }

    /**
     * Streams [silence] until [running] clears. [AudioTrack.write] blocks once the buffer is full,
     * which is what paces the loop — no sleeps are needed or wanted.
     */
    private fun writeLoop(audioTrack: AudioTrack, silence: ShortArray) {
        while (running) {
            val written = try {
                audioTrack.write(silence, 0, silence.size)
            } catch (e: RuntimeException) {
                // The track was torn down underneath us, or the platform dropped the stream.
                if (running) Log.w(TAG, "keep-alive write failed", e)
                return
            }
            if (written < 0) {
                Log.w(TAG, "keep-alive write returned $written; stopping writer")
                return
            }
        }
    }

    /**
     * Tears the stream down. Never joins [writer]: that thread is parked inside a blocking write
     * and joining it would stall whatever thread the service tears down on. Clearing [running]
     * plus the pause/flush below is what makes the pending write return, and the interrupt is only
     * a best-effort nudge for a thread still waiting to be scheduled.
     */
    fun stop() {
        running = false
        writer?.interrupt()
        writer = null

        val audioTrack = track ?: return
        track = null
        releaseQuietly(audioTrack)
        Log.i(TAG, "Silent keep-alive stopped")
    }

    fun release() {
        stop()
    }

    private fun releaseQuietly(audioTrack: AudioTrack) {
        try {
            audioTrack.pause()
            audioTrack.flush()
        } catch (e: RuntimeException) {
            Log.w(TAG, "keep-alive pause/flush failed", e)
        }
        try {
            audioTrack.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "keep-alive release failed", e)
        }
    }

    private companion object {
        const val TAG = "Earpiece"

        const val SAMPLE_RATE = 44100
        const val BYTES_PER_SAMPLE = 2
    }
}
