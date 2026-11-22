package com.lou.lumi

import com.lou.lumi.voice.ModelDownloader
import com.lou.lumi.voice.ModelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrainManifestTest {

    private val entries = ModelDownloader.parseManifest(File("src/main/assets/brain_manifest.tsv").readText())

    @Test fun everythingTheBrainNeedsIsInTheDownload() {
        val targets = entries.map { it.target }.toSet()
        listOf(
            "gemma/gemma-4-E2B-it.litertlm",
            "asr/small-encoder.int8.onnx", "asr/small-decoder.int8.onnx", "asr/small-tokens.txt",
            "vad/silero_vad_v5.onnx",
            "tts/de/kerstin/de_DE-kerstin-low.onnx", "tts/de/kerstin/de_DE-kerstin-low.onnx.json", "tts/de/kerstin/tokens.txt",
            "tts/de/espeak-ng-data/phontab",
        ).forEach { assertTrue("fehlt: $it", it in targets) }
        // never the -gpu bundle (gibberish on the S23), never an fp32 Whisper twin
        assertTrue(entries.none { it.target.contains("-gpu") || it.target.matches(Regex(".*small-(en|de)coder\\.onnx")) })
    }

    @Test fun onlyPinnedHttpsHuggingFaceUrls() {
        entries.forEach { e ->
            assertTrue(e.url, e.url.startsWith("https://huggingface.co/"))
            assertTrue("nicht auf einen Commit festgelegt: ${e.url}", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(e.url))
            assertTrue(e.size > 0)
            assertFalse("kein Pfad nach oben: ${e.target}", e.target.contains(".."))
        }
        // the big files are checked by hash
        entries.filter { it.size > 1_000_000 }.forEach { assertTrue(it.target, it.sha256?.length == 64) }
        val gb = entries.sumOf { it.size } / 1e9
        assertTrue("~3 GB, war $gb", gb in 2.9..3.2)
    }

    @Test fun downloadedLayoutIsWhatTheAppLooksFor() {
        // the same classifier the importer uses must accept the downloaded tree as one German voice
        val nodes = entries.map { ModelLayout.Node("models/" + it.target, it.target.substringAfterLast('/'), it.size) }
        val notes = ModelLayout.notes(nodes, ModelLayout.classify(nodes, File("/root")), File("/root"))
        assertTrue(notes.toString(), notes.none { it.contains("de:") || it.contains("litertlm") || it.contains("Whisper") || it.contains("Silero") })
    }

    @Test fun onlyHuggingFaceHostsAreAllowed() {
        listOf("huggingface.co", "cdn-lfs.huggingface.co", "cas-bridge.xethub.hf.co", "hf.co").forEach {
            assertTrue(it, ModelDownloader.isAllowedHost(it))
        }
        listOf("evil.com", "huggingface.co.evil.com", "nothf.co", "github.com", "", null).forEach {
            assertFalse(it.toString(), ModelDownloader.isAllowedHost(it))
        }
        assertEquals(2, ModelDownloader.parseManifest("# c\nhttps://huggingface.co/a\tx/y\t1\t-\n\nhttps://huggingface.co/b\tz\t2\tab\n").size)
    }
}
