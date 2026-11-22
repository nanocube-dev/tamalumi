package com.lou.lumi.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Stage 1 + 2 of the cascade: Silero VAD segments the live mic stream, Whisper
 * (base, int8, multilingual) turns each segment into text. Fully on-device.
 */
class SttEngine(private val store: ModelStore) {

    data class Transcript(val text: String, val lang: String, val ms: Long)

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    /** Switching the recognition language rebuilds Whisper, so nothing may decode meanwhile. */
    private val recognizerLock = Any()

    val isReady: Boolean get() = vad != null && recognizer != null

    fun init() {
        if (isReady) return
        Log.i(TAG, "loading Silero VAD: ${store.vadModel}")
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = store.vadModel.absolutePath,
                    threshold = Config.vadThreshold,
                    minSilenceDuration = Config.vadMinSilenceSec,
                    minSpeechDuration = Config.vadMinSpeechSec,
                    windowSize = Config.VAD_WINDOW,
                    maxSpeechDuration = Config.vadMaxSpeechSec,
                ),
                sampleRate = Config.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
        )

        buildRecognizer()
    }

    /** Whisper's own language guess, or the one the user pinned. */
    fun languageLabel(): String = when (Config.sttLanguage) {
        "de" -> "Deutsch"
        "en" -> "Englisch"
        else -> "Auto"
    }

    /**
     * (Re)build the Whisper recognizer. `language = ""` lets Whisper guess per utterance, which
     * misfires on short German commands that start with an English-sounding wake word – pinning
     * the language also stops it from silently answering in the wrong one.
     */
    private fun buildRecognizer() {
        synchronized(recognizerLock) {
            recognizer?.release()
            recognizer = null
            Log.i(
                TAG,
                "Lade ${store.whisperName}: ${store.whisperEncoder.name} + ${store.whisperDecoder.name}, " +
                    "Sprache=${Config.sttLanguage.ifEmpty { "auto" }}",
            )
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = Config.SAMPLE_RATE,
                        featureDim = 80,
                        dither = 0f,
                    ),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = store.whisperEncoder.absolutePath,
                            decoder = store.whisperDecoder.absolutePath,
                            language = Config.sttLanguage,
                            task = "transcribe",
                            tailPaddings = 1000,
                        ),
                        tokens = store.whisperTokens.absolutePath,
                        numThreads = Config.whisperThreads,
                        debug = false,
                        provider = "cpu",
                        modelType = "whisper",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        }
        Log.i(TAG, "STT bereit (${languageLabel()})")
    }

    /** Pin the recognition language ("de", "en", or "" for Whisper's own guess). */
    fun setLanguage(code: String): String {
        if (code == Config.sttLanguage) return languageLabel()
        Config.sttLanguage = code
        buildRecognizer()
        return languageLabel()
    }

    /** Feed one 32 ms frame into the VAD. */
    fun accept(frame: FloatArray) {
        if (frame.isEmpty()) return
        vad?.acceptWaveform(frame)
    }

    /** True while the VAD considers the incoming audio to be speech. */
    fun speechActive(): Boolean = vad?.isSpeechDetected() ?: false

    /** Drain completed utterances from the VAD ring buffer. */
    fun pollSegments(): List<SpeechSegment> {
        val v = vad ?: return emptyList()
        val out = ArrayList<SpeechSegment>(2)
        while (!v.empty()) {
            out += v.front()
            v.pop()
        }
        return out
    }

    fun transcribe(samples: FloatArray): Transcript {
        val t0 = System.nanoTime()
        synchronized(recognizerLock) {
            val rec = recognizer ?: return Transcript("", "", 0)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, Config.SAMPLE_RATE)
                rec.decode(stream)
                val r = rec.getResult(stream)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val detected = r.lang.orEmpty().replace("<|", "").replace("|>", "").take(2)
                // What we actually asked for matters more than what Whisper guessed.
                val lang = Config.sttLanguage.ifEmpty { detected }
                return Transcript(r.text.orEmpty().trim(), lang, ms)
            } finally {
                stream.release()
            }
        }
    }

    fun resetVad() {
        runCatching { vad?.clear() }
        runCatching { vad?.reset() }
    }

    fun release() {
        synchronized(recognizerLock) { runCatching { recognizer?.release() }; recognizer = null }
        recognizer = null
        runCatching { vad?.release() }
        vad = null
    }

    companion object {
        private const val TAG = "Lumi.STT"
    }
}
