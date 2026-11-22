package com.lou.lumi.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Streaming PCM playback for the TTS stage. Created for the sample rate reported by
 * the TTS model; [abort] stops output immediately when the user interrupts.
 */
class AudioOut(
    private val sampleRate: Int,
    /** Loudness (0..1, peak) of what is audible right now; called from the main or the writing thread. */
    private val onLevel: ((Float) -> Unit)? = null,
    /**
     * true while the mic is open: voice-communication path, so the hardware echo canceller knows
     * what we play (barge-in). false: plain assistant output on the speaker at media volume.
     */
    val voiceComm: Boolean = true,
) {

    private var track: AudioTrack? = null

    /**
     * Peak of the ~20 ms block at the playback head. The track buffers up to ~1 s and a whole
     * sentence is written at once, so the peak of the chunk being *written* would run ahead of the
     * speaker. [write] files a peak per block under its frame position; a playback-position
     * notification reads the block at the head, so the level follows what is actually heard.
     */
    @Volatile var outputPeak = 0f
        private set
    private val envBlock = maxOf(1, sampleRate / 50)
    private val envelope = FloatArray(ENV_SLOTS)
    @Volatile private var queuedFrames = 0L
    private val levelListener = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack) = publishLevel(track) // end of queue → 0
        override fun onPeriodicNotification(track: AudioTrack) = publishLevel(track)
    }

    @Volatile private var aborted = false
    @Volatile private var writtenFrames = 0L

    @Volatile var inCommunicationMode = false
        private set

    fun start(): Boolean {
        release()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        val t = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(if (voiceComm) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(min, sampleRate), // ~1 s buffer
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (e: Exception) {
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        track = t
        aborted = false
        writtenFrames = 0L
        queuedFrames = 0L
        envelope.fill(0f)
        runCatching {
            t.setPlaybackPositionUpdateListener(levelListener, Handler(Looper.getMainLooper()))
            t.positionNotificationPeriod = envBlock
        }
        runCatching { t.play() }
        return true
    }

    /** Returns false when playback was aborted and generation should stop. */
    fun write(samples: FloatArray): Boolean {
        if (aborted) return false
        val t = track ?: return false
        val shorts = ShortArray(samples.size)
        val base = writtenFrames
        var slot = -1L
        var peak = 0f
        for (i in samples.indices) {
            val v = samples[i].coerceIn(-1f, 1f)
            shorts[i] = (v * 32767f).toInt().toShort()
            // peak per 20 ms block, filed under its frame position (no allocation)
            val s = (base + i) / envBlock
            if (s != slot) {
                if (slot >= 0) envelope[(slot % ENV_SLOTS).toInt()] = peak
                slot = s
                peak = 0f
            }
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        if (slot >= 0) envelope[(slot % ENV_SLOTS).toInt()] = peak
        queuedFrames = base + samples.size
        runCatching { t.notificationMarkerPosition = queuedFrames.toInt() }
        var off = 0
        while (off < shorts.size) {
            if (aborted) return false
            val w = runCatching { t.write(shorts, off, shorts.size - off) }.getOrDefault(-1)
            if (w <= 0) return false
            off += w
        }
        writtenFrames += samples.size
        return true
    }

    /** Level of the block at the playback head; 0 once everything queued has been played. */
    private fun publishLevel(t: AudioTrack) {
        val head = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(0L)
        val level = if (aborted || head >= queuedFrames) 0f
        else envelope[((head / envBlock) % ENV_SLOTS).toInt()]
        outputPeak = level
        onLevel?.invoke(level)
    }

    private fun publishSilence() {
        outputPeak = 0f
        onLevel?.invoke(0f)
    }

    /** Cut output now: pause, drop buffered audio, be ready for the next turn. */
    fun abort() {
        aborted = true
        publishSilence()
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
        // flush() also clears the native update period: without this the level freezes at 0 after a barge-in
        runCatching { t.positionNotificationPeriod = envBlock }
        runCatching { t.play() }
        writtenFrames = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(0L)
        queuedFrames = writtenFrames
    }

    fun resumeFlag() {
        aborted = false
    }

    /** Frames handed to the track so far (Zuhause-PC mode: where a sentence starts). */
    fun framesWritten(): Long = writtenFrames

    /** Frames the speaker has played (Zuhause-PC mode: which Ditto frame to show). */
    fun framesPlayed(): Long = runCatching { track?.playbackHeadPosition?.toLong() ?: 0L }.getOrDefault(0L)

    val rate: Int get() = sampleRate

    /** Block until everything written so far has actually been rendered. */
    fun drain(timeoutMs: Long = 8_000L): Boolean {
        val t = track ?: return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (aborted) return false
            val played = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(0L)
            if (played >= writtenFrames) {
                publishSilence()
                return true
            }
            Thread.sleep(20)
        }
        publishSilence()
        return true
    }

    fun release() {
        publishSilence()
        track?.let { t ->
            runCatching { t.setPlaybackPositionUpdateListener(null) }
            runCatching { t.pause() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    fun setCommunicationMode(audioManager: AudioManager, enabled: Boolean) {
        if (inCommunicationMode == enabled) return
        runCatching {
            audioManager.mode = if (enabled) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            if (enabled) audioManager.isSpeakerphoneOn = true
        }
        inCommunicationMode = enabled
    }

    private companion object {
        /** 256 × 20 ms ≈ 5 s of envelope, well beyond the ~1 s track buffer. */
        const val ENV_SLOTS = 256
    }
}
