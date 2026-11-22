package com.lou.lumi.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Stage 4 of the cascade: Piper/VITS voices, streamed straight into playback
 * through a generation callback, so speech starts before the whole sentence is done.
 */
class TtsEngine(private val store: ModelStore) {

    private val voices = HashMap<String, OfflineTts>()

    /** The native OfflineTts instances are not reentrant — one synthesis at a time. */
    private val nativeLock = Any()

    val isReady: Boolean get() = voices.isNotEmpty()

    /** Thread-safe one-shot synthesis (used by the self test / simulated speech). */
    fun synthesize(text: String, lang: String, speed: Float = Config.ttsSpeed): GeneratedAudio? {
        val clean = preprocess(text)
        if (clean.isBlank()) return null
        return runCatching {
            synchronized(nativeLock) { voiceFor(lang).generate(text = clean, sid = 0, speed = speed) }
        }.onFailure { Log.w(TAG, "synthesize failed for '$clean': ${it.message}") }.getOrNull()
    }

    /** Sample rate of the loaded voices; both Piper "medium" voices are 22.05 kHz. */
    fun sampleRate(): Int = voices.values.firstOrNull()?.sampleRate() ?: 22_050

    /** Which voice is currently speaking per language. */
    private val voiceId = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun voicesFor(lang: String): List<ModelStore.Voice> = store.piperVoices(lang)

    fun activeVoiceId(lang: String): String? = voiceId[lang] ?: Config.ttsVoiceId[lang]

    /**
     * Swap the voice of one language while running. The native voice is not reentrant, so this
     * happens under [nativeLock]; the old engine is released afterwards.
     */
    fun useVoice(lang: String, id: String): String? {
        val voice = store.piperVoices(lang).firstOrNull { it.id == id } ?: return null
        val old = synchronized(nativeLock) {
            voices[lang]?.let { voices.remove(lang) }
        }
        return runCatching {
            val tts = load(voice)
            voices[lang] = tts
            voiceId[lang] = voice.id
            Config.ttsVoiceId[lang] = voice.id
            runCatching { old?.release() }
            voice.label
        }.getOrElse {
            Log.e(TAG, "voice switch to ${voice.id} failed, restoring the previous one", it)
            runCatching { old?.let { t -> voices[lang] = t } }
            null
        }
    }

    fun init() {
        if (voices.isNotEmpty()) return
        runCatching { store.selectedVoice("de")?.let { voices["de"] = load(it); voiceId["de"] = it.id } }
            .onFailure { Log.e(TAG, "de voice konnte nicht geladen werden", it) }
        runCatching { store.selectedVoice("en")?.let { voices["en"] = load(it); voiceId["en"] = it.id } }
            .onFailure { Log.e(TAG, "en voice konnte nicht geladen werden", it) }
        require(voices.isNotEmpty()) { "Keine TTS-Stimme geladen — Ordner espeak-ng-data fehlt?" }
        Log.i(TAG, "TTS ready: ${voices.keys}, rate=${sampleRate()}")
    }

    private fun load(voice: ModelStore.Voice): OfflineTts {
        val espeak = voice.espeak
        require(File(espeak, "phontab").isFile) { "espeak-ng phonemizer data missing in $espeak" }
        return OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.model.absolutePath,
                        lexicon = "",
                        tokens = voice.tokens.absolutePath,
                        // Piper voices need the bundled eSpeak-ng phonemizer
                        dataDir = espeak.absolutePath,
                    ),
                    numThreads = Config.ttsThreads,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
                silenceScale = 0.6f,
            )
        )
    }

    /** Pick the voice for a language tag ("de", "en", ...); falls back to English. */
    fun voiceFor(lang: String): OfflineTts {
        val key = lang.lowercase().take(2)
        return voices[key] ?: voices["de"].takeIf { key == "de" } ?: voices["en"] ?: voices.values.first()
    }

    fun has(lang: String): Boolean = voices.containsKey(lang.lowercase().take(2))

    /**
     * Generate and stream one chunk. [sink] receives float PCM and returns false to
     * stop generation (used for interruption).
     */
    fun speakChunk(text: String, lang: String, speed: Float = Config.ttsSpeed, sink: (FloatArray) -> Boolean) {
        val clean = preprocess(text)
        if (clean.isBlank()) return
        val tts = voiceFor(lang)
        // sherpa-onnx 1.13.x calls its JNI callback with a signature ART rejects
        // (NoSuchMethodError → SIGABRT), so synthesise the sentence and stream it out ourselves.
        val audio = runCatching {
            synchronized(nativeLock) { tts.generate(text = clean, sid = 0, speed = speed) }
        }.onFailure { Log.w(TAG, "tts failed for '$clean': ${it.message}"
        ) }.getOrNull() ?: return

        val samples = audio.samples
        if (samples.isEmpty()) {
            Log.w(TAG, "tts produced no audio for '$clean'")
            return
        }
        var off = 0
        while (off < samples.size) {
            val end = minOf(off + PLAY_SLICE, samples.size)
            if (!sink(samples.copyOfRange(off, end))) return
            off = end
        }
    }

    /** Strip things that read badly out loud. */
    private fun preprocess(text: String): String = text
        .replace(Regex("""\[[^\]]{0,24}\]"""), " ")     // [froh] and friends are stage directions, not speech
        .replace(Regex("<[^>]{0,200}>"), " ")
        .replace(Regex("[*_#`>~]"), "")
        .replace(Regex("[\\u2190-\\u21FF\\u2600-\\u27BF\\uD83C-\\uD83E\\uFE0F]"), "")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun release() {
        voices.values.forEach { runCatching { it.release() } }
        voices.clear()
    }

    companion object {
        private const val TAG = "Lumi.TTS"
        private const val PLAY_SLICE = 4096
    }
}
