package com.lou.lumi.voice

import android.content.Context
import java.io.File

/**
 * Tunables of the on-device voice pipeline (taken over from Gemma Voice):
 *   mic -> VAD (Silero) -> STT (Whisper) -> LLM (Gemma 4 E2B / LiteRT-LM) -> TTS (Piper)
 *
 * The persona / system prompt is not here – it is built per pet in brain/Persona.kt.
 */
object Config {
    const val SAMPLE_RATE = 16_000
    const val VAD_WINDOW = 512            // 32 ms @ 16 kHz, required by Silero v5

    // --- VAD / segmentation ---
    var vadThreshold = 0.55f              // speech probability
    var vadMinSilenceSec = 0.45f          // end of utterance after this much silence
    var vadMinSpeechSec = 0.20f           // discard shorter blips
    var vadMaxSpeechSec = 15.0f           // hard cap per utterance

    // --- Barge-in (interruption while the pet speaks) ---
    const val BARGE_IN_MS = 260L          // how long user speech must be sustained
    const val BARGE_RMS_FLOOR = 0.045f    // own voice re-entering the mic stays below this

    /** A conversation (open mic) closes by itself after this much silence. */
    const val SESSION_IDLE_MS = 30_000L

    // --- Whisper ---
    var whisperThreads = 6

    /** "de" (default), "en", or "" to let Whisper guess per utterance. */
    var sttLanguage = "de"

    /** Selected Piper voice per language (empty = the first one we find). */
    val ttsVoiceId = mutableMapOf<String, String>()

    // --- LLM ---
    var maxContextTokens = 2048
    /** Answers we accept before the idle engine refresh (LiteRT-LM#2062). */
    var llmRecycleAfter = 8
    /** null = the bundle's own compiled shape decides. */
    var llmMaxTokensOverride: Int? = maxContextTokens
    /** LiteRT-LM enables thinking by default; a talking pet cannot afford 20 s pauses. */
    var llmThinking = false
    var llmThinkingBudget = 0
    /** Which bundle family to pick from gemma/ (E2B: fast; E4B works too, slower). */
    var llmFlavour = "E2B"
    /** The `-gpu` bundles decode gibberish on the S23 (see Gemma Voice README); plain bundle on the GPU delegate. */
    var llmGpuWeights = false
    /** Substring a bundle name must contain to be considered (test hook). */
    var llmBundleHint: String? = null
    var llmThreads = 4
    /** A bit more playful than the assistant (0.4) – it is a pet, not an oracle. */
    const val TEMPERATURE = 0.7
    const val TOP_P = 0.92
    const val TOP_K = 32

    // --- TTS ---
    var ttsThreads = 2
    var ttsSpeed = 1.0f
}

/** Where the model weights live on disk (app external files dir, pushed over adb). */
class ModelStore(context: Context) {
    /**
     * Models live in the app's private dir (/data/user/0/<pkg>/files/models) — pushed
     * there once over adb. A sibling location under the external files dir is accepted
     * as a fallback so users can also drop files with a file manager.
     */
    val root: File = pickRoot(context)

    private fun filesIn(dir: File): List<File> =
        runCatching { dir.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())

    private fun firstOf(vararg candidates: File): File =
        candidates.firstOrNull { it.isFile } ?: candidates.first()

    /** Silero, whatever the file is called. */
    val vadModel: File = run {
        val dir = File(root, "vad")
        firstOf(
            File(dir, "silero_vad_v5.onnx"),
            *(filesIn(dir).filter { it.name.endsWith(".onnx") }.toTypedArray()),
        )
    }

    /**
     * Whisper triplet. Model names keep whatever the download used (small-encoder.int8.onnx,
     * medium-encoder.onnx, …); encoder/decoder/tokens are paired by their stem and the biggest
     * set that is actually on disk wins.
     */
    val whisperEncoder: File = run {
        val dir = File(root, "asr")
        val encoders = filesIn(dir).filter { it.name.contains("encoder", true) && it.name.endsWith(".onnx", true) }
        encoders
            .sortedWith(compareByDescending<File> { rankOf(it.name) }.thenByDescending { !it.name.contains("int8") })
            .firstOrNull() ?: File(dir, "small-encoder.int8.onnx")
    }
    val whisperDecoder: File = run {
        val dir = File(root, "asr")
        val stem = stemOf(whisperEncoder.name)
        val decoders = filesIn(dir).filter { it.name.contains("decoder", true) && it.name.endsWith(".onnx", true) }
        decoders.firstOrNull { it.name.startsWith("$stem-decoder") && it.name.contains("int8") }
            ?: decoders.firstOrNull { it.name.startsWith("$stem-decoder") }
            ?: decoders.sortedWith(compareByDescending<File> { rankOf(it.name) }).firstOrNull()
            ?: File(dir, "$stem-decoder.int8.onnx")
    }
    val whisperTokens: File = run {
        val dir = File(root, "asr")
        val stem = stemOf(whisperEncoder.name)
        val txt = filesIn(dir).filter { it.name.endsWith(".txt") }
        txt.firstOrNull { it.name.startsWith(stem) } ?: txt.firstOrNull { it.name == "tokens.txt" }
            ?: txt.firstOrNull() ?: File(dir, "$stem-tokens.txt")
    }
    val whisperName: String get() = stemOf(whisperEncoder.name).ifBlank { "Whisper" }

    private fun stemOf(fileName: String): String =
        fileName.substringBeforeLast("-encoder").substringBeforeLast("-decoder").substringBeforeLast("-tokens")

    private fun rankOf(fileName: String): Int = when {
        fileName.startsWith("large") || fileName.contains("large-v3") -> 5
        fileName.startsWith("distil") -> 4
        fileName.startsWith("medium") -> 3
        fileName.startsWith("small") -> 2
        fileName.startsWith("base") -> 1
        else -> 0
    }

    /**
     * One Piper voice: model, its config, its tokens and the eSpeak-ng phonemiser that belongs to
     * it. A voice may sit directly in `tts/<lang>` (the layout the app already knew) or in
     * `tts/<lang>/<voice>`, which is what the importer produces as soon as one folder holds
     * several voices – their `tokens.txt` would otherwise overwrite each other.
     */
    data class Voice(
        val lang: String,
        val id: String,
        val label: String,
        val model: File,
        val config: File,
        val tokens: File,
        val espeak: File,
    ) {
        val bytes: Long get() = if (model.isFile) model.length() else 0L
    }

    /** Every usable voice for a language: the flat one first, then the subfolders. */
    fun piperVoices(lang: String): List<Voice> {
        val dir = File(root, "tts/$lang")
        val subDirs = runCatching { dir.listFiles()?.filter { it.isDirectory }.orEmpty() }.getOrDefault(emptyList())
        val out = ArrayList<Voice>()
        for (d in listOf(dir) + subDirs) {
            for (model in filesIn(d).filter { it.name.endsWith(".onnx") }) {
                val config = File(d, "${model.name}.json").takeIf { it.isFile }
                    ?: filesIn(d).firstOrNull { it.name.endsWith(".json") } ?: continue
                val tokens = filesIn(d).firstOrNull { it.name.equals("tokens.txt", true) }
                    ?: filesIn(d).firstOrNull { it.name.endsWith(".txt") } ?: continue
                val espeak = listOf(d, dir).map { File(it, "espeak-ng-data") }
                    .firstOrNull { File(it, "phontab").isFile } ?: continue
                out += Voice(lang, ModelLayout.voiceId(model.name), voiceLabel(model.name), model, config, tokens, espeak)
            }
        }
        // The voice that has always been the default (flat layout) stays on top, and a voice that
        // exists both flat and in a subfolder (re-imported bundle) is listed once.
        return out.sortedWith(compareBy({ it.model.parentFile != dir }, { it.id }))
            .distinctBy { it.id }
    }

    /** Label for the picker: `de_DE-thorsten-medium.onnx` → „Thorsten (medium)“. */
    private fun voiceLabel(fileName: String): String {
        val stem = fileName.removeSuffix(".onnx")
        val quality = Regex("-(medium|low|high|x_low|x_high)(-int8)?$").find(stem)?.groupValues?.get(1).orEmpty()
        val who = stem.replace(Regex("^[a-z]{2}_[A-Za-z]{2}-"), "")
            .replace(Regex("-(medium|low|high|x_low|x_high)(-int8)?$"), "")
            .split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirst(it[0], it[0].uppercaseChar()) }
        return if (quality.isEmpty()) (who.ifEmpty { "Stimme" }) else "$who ($quality)"
    }

    fun selectedVoice(lang: String): Voice? =
        piperVoices(lang).let { voices ->
            Config.ttsVoiceId[lang]?.let { id -> voices.firstOrNull { it.id == id } } ?: voices.firstOrNull()
        }

    val ttsDeModel: File get() = selectedVoice("de")?.model ?: piperFallback("de")
    val ttsDeTokens: File get() = selectedVoice("de")?.tokens ?: File(root, "tts/de/tokens.txt")
    val ttsEnModel: File get() = selectedVoice("en")?.model ?: piperFallback("en")
    val ttsEnTokens: File get() = selectedVoice("en")?.tokens ?: File(root, "tts/en/tokens.txt")

    private fun piperFallback(lang: String): File {
        val dir = File(root, "tts/$lang")
        val onnx = filesIn(dir).filter { it.name.endsWith(".onnx") }
        return onnx.firstOrNull { PIPERVOICE.matches(it.name) } ?: onnx.firstOrNull() ?: File(dir, "voice.onnx")
    }

    private val PIPERVOICE = Regex("^[a-z]{2}_[A-Za-z]{2}-.+\\.onnx$")

    /**
     * LiteRT-LM bundles for the LLM slot. The community repo ships several builds of the same
     * model: a CPU/C-API bundle, a `-gpu` bundle, web/task and vendor NPU builds. Which one is
     * right depends on the backend we are about to use, so the caller says what it wants.
     */
    val llmBundles: List<File> get() =
        filesIn(File(root, "gemma"))
            .filter { it.name.endsWith(".litertlm", true) || it.name.endsWith(".task", true) }
            .sortedBy { it.name }

    fun llmModelFor(preferGpu: Boolean, flavour: String = Config.llmFlavour): File {
        val bundles = llmBundles
        val forFlavour = bundles.filter { it.name.contains(flavour, true) }.ifEmpty { bundles }
        val gpu = forFlavour.filter { it.name.contains("gpu", true) }
        val cpu = forFlavour.filterNot {
            it.name.contains("gpu", true) || it.name.contains("web", true) || it.name.endsWith(".task", true)
        }
        val hint = Config.llmBundleHint
        if (!hint.isNullOrBlank()) {
            bundles.firstOrNull { it.name.contains(hint, true) }?.let { return it }
        }
        val pick = when {
            preferGpu && Config.llmGpuWeights -> gpu.ifEmpty { cpu }
            else -> cpu.ifEmpty { gpu }
        }
        return pick.firstOrNull() ?: File(root, "gemma/$flavour.litertlm")
    }

    val llmModel: File get() = llmModelFor(preferGpu = true)
    val cacheDir: File = File(root, "cache").apply { mkdirs() }

    fun describe(): List<Pair<String, File>> = listOf(
        "VAD (Silero v5)" to vadModel,
        "STT encoder (${whisperName})" to whisperEncoder,
        "STT decoder (${whisperName})" to whisperDecoder,
        "STT tokens" to whisperTokens,
        "TTS de (Piper)" to ttsDeModel,
        "TTS de tokens" to ttsDeTokens,
        "TTS de Phonemisierer" to File(root, "tts/de/espeak-ng-data/phontab"),
        "TTS en (Piper)" to ttsEnModel,
        "TTS en tokens" to ttsEnTokens,
        "TTS en Phonemisierer" to File(root, "tts/en/espeak-ng-data/phontab"),
        "LLM (Gemma 4 E2B, LiteRT-LM)" to llmModel,
    )

    /** What is still needed to talk. English is optional – Lumi speaks German (the download brings only „de“). */
    fun missing(): List<String> = describe()
        .filter { !it.first.startsWith("TTS en") }
        .filter { !it.second.exists() }
        .map { "${it.first}: ${it.second}" }

    companion object {
        /**
         * Models live in the app's private dir; a sibling location under the external files
         * dir is accepted when that is where a previous install put them. Import and lookup
         * must agree, so both ask here.
         */
        fun pickRoot(context: Context): File {
            val priv = File(context.filesDir, "models")
            val ext = File(context.getExternalFilesDir(null) ?: priv, "models")
            return when {
                File(priv, "gemma").isDirectory -> priv
                File(ext, "gemma").isDirectory -> ext
                else -> priv
            }
        }
    }
}
