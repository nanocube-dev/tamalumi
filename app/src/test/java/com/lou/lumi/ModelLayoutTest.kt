package com.lou.lumi

import com.lou.lumi.voice.ModelLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLayoutTest {

    private val root = File("/data/user/0/com.lou.lumi/files/models")

    private fun n(path: String, size: Long = 10L) = ModelLayout.Node(path, path.substringAfterLast('/'), size)

    /** Exactly what scripts/download_models.sh produces, zipped up as `models/…`. */
    private fun realisticNodes(): List<ModelLayout.Node> = listOf(
        n("models/silero_vad_v5.onnx", 2_313_101),
        n("models/sherpa-onnx-whisper-small/small-encoder.int8.onnx", 112_442_483),
        n("models/sherpa-onnx-whisper-small/small-decoder.int8.onnx", 262_226_114),
        n("models/sherpa-onnx-whisper-small/small-tokens.txt", 461),
        n("models/sherpa-onnx-whisper-small/small-encoder.onnx", 409_000_000),
        n("models/sherpa-onnx-whisper-small/MODEL_CARD", 100),
        n("models/sherpa-onnx-whisper-small/test_wavs/de.wav", 90_000),
        n("models/sherpa-onnx-whisper-base/base-encoder.int8.onnx", 81_000_000),
        n("models/sherpa-onnx-whisper-base/base-decoder.int8.onnx", 78_000_000),
        n("models/sherpa-onnx-whisper-base/base-tokens.txt", 461),
        n("models/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx", 63_000_000),
        n("models/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx.json", 5_000),
        n("models/vits-piper-de_DE-thorsten-medium-int8/tokens.txt", 1_200),
        n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phontab", 8_900),
        n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phon", 500_000),
        n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/voices/de_de/f1", 400_000),
        n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/lang/de/de", 3_000),
        n("models/vits-piper-en_US-lessac-medium-int8/en_US-lessac-medium.onnx", 62_000_000),
        n("models/vits-piper-en_US-lessac-medium-int8/en_US-lessac-medium.onnx.json", 5_000),
        n("models/vits-piper-en_US-lessac-medium-int8/tokens.txt", 1_100),
        n("models/vits-piper-en_US-lessac-medium-int8/espeak-ng-data/phontab", 8_900),
        n("models/gemma/gemma-4-E2B-it.litertlm", 2_588_147_712),
    )

    private fun targetFor(nodes: List<ModelLayout.Node>): Map<String, String> =
        ModelLayout.classify(nodes, root).associate { it.node.path to it.target.invariantSeparatorsPath.removePrefix(root.invariantSeparatorsPath + "/") }

    @Test
    fun `maps every downloaded artifact onto the app layout`() {
        val map = targetFor(realisticNodes())

        assertEquals("vad/silero_vad_v5.onnx", map["models/silero_vad_v5.onnx"])
        assertEquals("asr/small-encoder.int8.onnx", map["models/sherpa-onnx-whisper-small/small-encoder.int8.onnx"])
        assertEquals("asr/small-decoder.int8.onnx", map["models/sherpa-onnx-whisper-small/small-decoder.int8.onnx"])
        assertEquals("asr/small-tokens.txt", map["models/sherpa-onnx-whisper-small/small-tokens.txt"])
        assertEquals("asr/base-tokens.txt", map["models/sherpa-onnx-whisper-base/base-tokens.txt"])
        assertEquals("tts/de/de_DE-thorsten-medium.onnx", map["models/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx"])
        assertEquals("tts/en/en_US-lessac-medium.onnx", map["models/vits-piper-en_US-lessac-medium-int8/en_US-lessac-medium.onnx"])
        assertEquals("gemma/gemma-4-E2B-it.litertlm", map["models/gemma/gemma-4-E2B-it.litertlm"])
    }

    @Test
    fun `espeak-ng-data follows the language of its voice folder`() {
        val map = targetFor(realisticNodes())
        assertEquals(
            "tts/de/espeak-ng-data/voices/de_de/f1",
            map["models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/voices/de_de/f1"],
        )
        assertEquals(
            "tts/en/espeak-ng-data/phontab",
            map["models/vits-piper-en_US-lessac-medium-int8/espeak-ng-data/phontab"],
        )
    }

    @Test
    fun `noise is not imported`() {
        val map = targetFor(realisticNodes())
        assertTrue(map.values.none { it.contains("MODEL_CARD") })
        assertTrue(map.values.none { it.endsWith("de.wav") })
        // the fp32 twin of an int8 model is dropped so the phone does not hold both
        assertTrue(map["models/sherpa-onnx-whisper-small/small-encoder.onnx"] == null)
        assertTrue(map.values.none { it.endsWith("/small-encoder.onnx") })
    }

    @Test
    fun `flat trees without wrapper folders work too`() {
        val flat = listOf(
            n("silero_vad.onnx"),
            n("asr/base-encoder.int8.onnx"),
            n("asr/base-decoder.int8.onnx"),
            n("asr/tokens.txt"),
            n("gemma-4-E2B-it.litertlm"),
        )
        val map = targetFor(flat)
        assertEquals("vad/silero_vad_v5.onnx", map["silero_vad.onnx"])
        assertEquals("asr/base-encoder.int8.onnx", map["asr/base-encoder.int8.onnx"])
        assertEquals("asr/tokens.txt", map["asr/tokens.txt"])
        assertEquals("gemma/gemma-4-E2B-it.litertlm", map["gemma-4-E2B-it.litertlm"])
    }

    @Test
    fun `a TTS folder with a text_encoder is not mistaken for whisper`() {
        val ttsOnly = listOf(
            n("models/sherpa-onnx-supertonic-tts-int8/text_encoder.int8.onnx"),
            n("models/sherpa-onnx-supertonic-tts-int8/duration_predictor.int8.onnx"),
            n("models/sherpa-onnx-supertonic-tts-int8/vector_estimator.int8.onnx"),
            n("models/sherpa-onnx-supertonic-tts-int8/vocoder.int8.onnx"),
            n("models/sherpa-onnx-supertonic-tts-int8/tokens.txt"),
        )
        assertTrue(ModelLayout.classify(ttsOnly, root).isEmpty())
    }

    @Test
    fun `whisper with unprefixed tokens file still resolves`() {
        val odd = listOf(
            n("models/whisper-medium/medium-encoder.int8.onnx"),
            n("models/whisper-medium/medium-decoder.int8.onnx"),
            n("models/whisper-medium/tokens.txt"),
        )
        val map = targetFor(odd)
        assertEquals("asr/medium-encoder.int8.onnx", map["models/whisper-medium/medium-encoder.int8.onnx"])
        assertEquals("asr/medium-decoder.int8.onnx", map["models/whisper-medium/medium-decoder.int8.onnx"])
        assertEquals("asr/tokens.txt", map["models/whisper-medium/tokens.txt"])
    }

    @Test
    fun `extension-less phonemiser files are kept and mapped`() {
        val tree = listOf(
            n("models/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/tokens.txt"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phon"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phontab"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phondata"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/lang/de/de"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/dict/de_dict"),
            n("models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/voices/de_de/f1"),
        )
        val map = targetFor(tree)
        assertEquals("tts/de/espeak-ng-data/phon", map["models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phon"])
        assertEquals("tts/de/espeak-ng-data/dict/de_dict", map["models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/dict/de_dict"])
        assertEquals(
            "tts/de/espeak-ng-data/voices/de_de/f1",
            map["models/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/voices/de_de/f1"],
        )
        assertTrue(ModelLayout.notes(tree, ModelLayout.classify(tree, root), root).none { it.contains("espeak-ng-data") })
    }

    @Test
    fun `notes point at what is missing`() {
        val withoutLlm = realisticNodes().filterNot { it.name.endsWith(".litertlm") }
        val plans = ModelLayout.classify(withoutLlm, root)
        val notes = ModelLayout.notes(withoutLlm, plans, root)
        assertTrue(notes.any { it.contains("litertlm") })
        assertTrue(notes.none { it.contains("Whisper") })

        val full = ModelLayout.classify(realisticNodes(), root)
        assertTrue(ModelLayout.notes(realisticNodes(), full, root).isEmpty())

        val voiceWithoutPhonemiser = listOf(
            n("vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx"),
            n("vits-piper-de_DE-thorsten-medium-int8/tokens.txt"),
        )
        val p = ModelLayout.classify(voiceWithoutPhonemiser, root)
        assertTrue(ModelLayout.notes(voiceWithoutPhonemiser, p, root).any { it.contains("espeak-ng-data") })
    }

    @Test
    fun `three german voices get one folder each and keep their own tokens`() {
        val nodes = listOf(
            n("vits/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx", 61_000_000),
            n("vits/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx.json", 1200),
            n("vits/vits-piper-de_DE-thorsten-medium-int8/tokens.txt", 900),
            n("vits/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phontab", 3000),
            n("vits/piper-eva_k/de_DE-eva_k-x_low.onnx", 19_000_000),
            n("vits/piper-eva_k/de_DE-eva_k-x_low.onnx.json", 1100),
            n("vits/piper-eva_k/tokens.txt", 850),
            n("vits/piper-eva_k/espeak-ng-data/de/de", 400),
            n("vits/piper-kerstin/de_DE-kerstin-low.onnx", 24_000_000),
            n("vits/piper-kerstin/de_DE-kerstin-low.onnx.json", 1100),
            n("vits/piper-kerstin/tokens.txt", 860),
            n("models/silero_vad_v5.onnx", 2_200_000),
        )
        val plans = ModelLayout.classify(nodes, File("/root"))
        fun target(path: String) = plans.first { it.node.path == path }.target.invariantSeparatorsPath

        assertEquals("/root/tts/de/thorsten/de_DE-thorsten-medium.onnx",
            target("vits/vits-piper-de_DE-thorsten-medium-int8/de_DE-thorsten-medium.onnx"))
        assertEquals("/root/tts/de/eva_k/de_DE-eva_k-x_low.onnx",
            target("vits/piper-eva_k/de_DE-eva_k-x_low.onnx"))
        assertEquals("/root/tts/de/kerstin/de_DE-kerstin-low.onnx",
            target("vits/piper-kerstin/de_DE-kerstin-low.onnx"))
        // each voice keeps its own tokens file, the phonemiser stays shared per language
        assertEquals("/root/tts/de/eva_k/eva_k-tokens.txt", target("vits/piper-eva_k/tokens.txt"))
        assertEquals("/root/tts/de/thorsten/thorsten-tokens.txt",
            target("vits/vits-piper-de_DE-thorsten-medium-int8/tokens.txt"))
        assertEquals("/root/tts/de/espeak-ng-data/phontab",
            target("vits/vits-piper-de_DE-thorsten-medium-int8/espeak-ng-data/phontab"))
    }

    @Test
    fun `a single voice still lands flat in the language folder`() {
        val nodes = listOf(
            n("vits-piper-en_US-lessac-medium-int8/en_US-lessac-medium.onnx", 61_000_000),
            n("vits-piper-en_US-lessac-medium-int8/en_US-lessac-medium.onnx.json", 1200),
            n("vits-piper-en_US-lessac-medium-int8/tokens.txt", 900),
        )
        val plans = ModelLayout.classify(nodes, File("/root"))
        assertTrue(plans.all { it.target.invariantSeparatorsPath.startsWith("/root/tts/en/") })
        assertTrue(plans.none { it.target.invariantSeparatorsPath.contains("lessac/") })
    }
}
