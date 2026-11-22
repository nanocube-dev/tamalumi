package com.lou.lumi.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.sqrt

/**
 * Microphone capture at 16 kHz mono, delivered in [Config.VAD_WINDOW]-sample frames.
 * Uses the VOICE_COMMUNICATION source and enables hardware AEC/NS so the assistant
 * can hear the user over its own TTS output (barge-in).
 */
class AudioIn(private val onFrame: (FloatArray) -> Unit) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @Volatile var rms = 0f
        private set
    @Volatile var aecActive = false
        private set
    @Volatile var nsActive = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            Config.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Config.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, Config.VAD_WINDOW * 8),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        aecActive = if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; aec = it }
            }.isSuccess
        } else false

        nsActive = if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; ns = it }
            }.isSuccess
        } else false

        record = rec
        runCatching { rec.startRecording() }.onFailure { rec.release(); return false }

        running = true
        val shorts = ShortArray(Config.VAD_WINDOW)
        thread = Thread {
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                val frame = FloatArray(n)
                var acc = 0.0
                for (i in 0 until n) {
                    val s = shorts[i] / 32768f
                    frame[i] = s
                    acc += (s * s).toDouble()
                }
                rms = sqrt(acc / n).toFloat()
                onFrame(frame)
            }
        }.apply {
            name = "mic-16k"
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(600) } }
        thread = null
        record?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        record = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        aecActive = false
        nsActive = false
        rms = 0f
    }
}
