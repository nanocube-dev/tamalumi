package com.lou.lumi.voice

import java.io.File

/**
 * Pure mapping from "whatever the user picked" (a ZIP or a folder tree) onto the app's
 * internal model layout. Kept free of Android types so it can be unit tested on a JVM.
 *
 * Recognised shapes (case-insensitive, any nesting depth):
 *  - silero_vad*.onnx                                        → vad/silero_vad_v5.onnx
 *  - a dir with *-encoder*.onnx + *-decoder*.onnx + tokens   → asr/<original name>
 *  - <lang>_<REGION>-<voice>.onnx + tokens.txt               → tts/<lang>/…
 *  - espeak-ng-data/ under such a voice dir                  → tts/<lang>/espeak-ng-data/…
 *  - *.litertlm / *.task                                     → gemma/<original name>
 */
object ModelLayout {

    class Node(val path: String, val name: String, val size: Long) {
        val parent: String get() = path.substringBeforeLast('/', "")
    }

    data class Plan(val node: Node, val target: File)

    private val PIPER_NAME = Regex("^([a-z]{2})_[A-Za-z]{2}-.+\\.onnx$")
    private val ASR_ENCODER = Regex("^(.+?)-encoder(\\.[A-Za-z0-9]+)*\\.onnx$")
    private val ASR_DECODER = Regex("^(.+?)-decoder(\\.[A-Za-z0-9]+)*\\.onnx$")

    val SKIP_DIRS = setOf("test_wavs", "__MACOSX", ".git", "cache", "selftest")
    val SKIP_FILES = setOf("MODEL_CARD", "README", "README.md", "LICENSE", "LICENSE.md", "checksum.txt", ".DS_Store")

    /** `de_DE-eva_k-x_low.onnx` → `eva_k` (quality suffix dropped, it is obvious in the file). */
    fun voiceId(modelName: String): String {
        val stem = modelName.removeSuffix(".onnx")
        val noLang = stem.replace(Regex("^[a-z]{2}_[A-Za-z]{2}-"), "")
        return noLang.replace(Regex("-(medium|medium-int8|low|x_low|high|x_high|tts-v1|v2)$"), "")
    }

    fun isSkippedDir(name: String) = SKIP_DIRS.any { name.equals(it, true) }
    fun isSkippedFile(name: String) = SKIP_FILES.any { it.equals(name, true) } || name.endsWith(".tar.bz2", true)

    fun classify(nodes: List<Node>, root: File): List<Plan> {
        val byDir = nodes.groupBy { it.parent }
        // Voices per language in this import: one may live flat in tts/<lang>, several need a
        // folder each because every voice carries its own tokens.txt and config file.
        val voicesByLang: Map<String, Int> = nodes
            .filter { PIPER_NAME.matches(it.name) }
            .groupingBy { PIPER_NAME.find(it.name)!!.groupValues[1].lowercase() }
            .eachCount()
        val plans = LinkedHashMap<String, Plan>()

        fun add(n: Node, dir: String, asName: String = n.name) {
            val target = File(File(root, dir), asName)
            plans[target.absolutePath] = Plan(n, target)
        }

        for ((dir, files) in byDir) {
            files.filter { it.name.endsWith(".litertlm", true) || it.name.endsWith(".task", true) }
                .forEach { add(it, "gemma") }

            files.firstOrNull { it.name.equals("silero_vad_v5.onnx", true) || it.name.equals("silero_vad.onnx", true) }
                ?.let { add(it, "vad", "silero_vad_v5.onnx") }

            // Whisper triplets are recognised by an encoder/decoder pair sharing one stem, so a
            // TTS folder that happens to hold a “text_encoder.onnx” is not mistaken for STT.
            val stems = files.mapNotNull { ASR_ENCODER.matchEntire(it.name)?.groupValues?.get(1) }.distinct()
                .filter { stem -> files.any { ASR_DECODER.matchEntire(it.name)?.groupValues?.get(1) == stem } }
            for (stem in stems) {
                val models = files.filter { it.name.endsWith(".onnx", true) && it.name.startsWith("$stem-") }
                // A quantised build wins; carrying the fp32 twin along would double the ASR footprint.
                val preferInt8 = models.any { it.name.contains("int8") }
                models.filter { !preferInt8 || it.name.contains("int8") }.forEach { add(it, "asr") }
                val tokens = files.filter { it.name.endsWith(".txt", true) && it.name.startsWith("$stem-") }
                (if (tokens.isNotEmpty()) tokens else files.filter { it.name.equals("tokens.txt", true) })
                    .forEach { add(it, "asr") }
            }

            val voices = files.filter { PIPER_NAME.matches(it.name) }
            if (voices.isNotEmpty()) {
                // One voice per folder keeps the flat tts/<lang> layout the app already knows.
                // As soon as a folder carries several voices, each one gets its own subfolder –
                // otherwise their tokens.txt and config would overwrite each other.
                for (voice in voices) {
                    val lang = PIPER_NAME.find(voice.name)!!.groupValues[1].lowercase()
                    val flat = voicesByLang.getValue(lang) == 1
                    val dir = if (flat) "tts/$lang" else "tts/$lang/${voiceId(voice.name)}"
                    files.filter {
                        (it.name.endsWith(".onnx", true) && it.name == voice.name) ||
                            it.name == "${voice.name}.json"
                    }.forEach { add(it, dir) }
                    // "tokens.txt" from the downloads, or "<voice>-tokens.txt" as the app itself stores
                    // several voices of one language (a copy of files/models imported on another phone)
                    files.filter {
                        it.name.equals("tokens.txt", true) || it.name.equals("${voiceId(voice.name)}-tokens.txt", true)
                    }.forEach { add(it, dir, if (flat) "tokens.txt" else "${voiceId(voice.name)}-tokens.txt") }
                }
            }

        }

        // espeak-ng-data may nest (voices/de_de/f1, lang/de/de, dict/…) – keep the relative path
        for (n in nodes) {
            val idx = n.path.indexOf("espeak-ng-data/")
            if (idx < 0) continue
            val ownerDir = n.path.substring(0, idx).removeSuffix("/")
            val ownerVoice = byDir[ownerDir]?.firstOrNull { PIPER_NAME.matches(it.name) }
                ?: byDir[ownerDir]?.firstOrNull { it.name.endsWith(".onnx", true) }
            val lang = ownerVoice?.let { PIPER_NAME.find(it.name)?.groupValues?.get(1)?.lowercase() }
                ?: ownerDir.substringAfterLast('/').takeIf { it.length in 2..3 }?.lowercase()
                ?: "de"
            val rel = n.path.substring(idx + "espeak-ng-data/".length)
            val target = File(File(File(root, "tts/$lang"), "espeak-ng-data"), rel)
            plans[target.absolutePath] = Plan(n, target)
        }
        return plans.values.toList()
    }

    /** Warnings about what a picked tree *should* still contain. */
    fun notes(nodes: List<Node>, plans: List<Plan>, root: File): List<String> {
        val out = ArrayList<String>()
        val targets = plans.map { it.target.invariantSeparatorsPath }
        for (lang in listOf("de", "en")) {
            val dir = File(root, "tts/$lang").invariantSeparatorsPath
            val hasVoice = targets.any { it.startsWith("$dir/") && it.endsWith(".onnx") }
            if (hasVoice && targets.none { it.startsWith("$dir/espeak-ng-data/phon") }) {
                out += "TTS $lang: »espeak-ng-data« fehlt – ohne Phonemisierer spricht das Gerät nicht. Kompletten Modellordner importieren."
            }
        }
        if (nodes.none { it.name.endsWith(".litertlm", true) }) out += "Kein .litertlm (Gemma) im ausgewählten Inhalt gefunden."
        if (nodes.none { it.name.contains("encoder", true) && it.name.endsWith(".onnx", true) })
            out += "Kein Whisper-Modell (encoder + decoder) gefunden."
        if (nodes.none { it.name.equals("silero_vad_v5.onnx", true) || it.name.equals("silero_vad.onnx", true) })
            out += "Kein Silero-VAD (silero_vad*.onnx) gefunden."
        return out
    }
}
