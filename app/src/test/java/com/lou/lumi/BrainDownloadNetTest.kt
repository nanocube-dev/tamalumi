package com.lou.lumi

import com.lou.lumi.voice.ModelDownloader
import com.lou.lumi.voice.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real downloads from Hugging Face through [ModelDownloader] – only with `-Plumi.net=true`
 * (needs internet). Uses the small files of the manifest: the Silero VAD (hash-checked), Kerstin's
 * tokens and config. Covers redirects + host allowlist, resume via Range, and a wrong checksum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainDownloadNetTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun downloadsResumesAndRejectsWrongChecksums() {
        assumeTrue("nur mit -Plumi.net=true", System.getProperty("lumi.net") == "true")
        val dl = ModelDownloader(app)
        val root = ModelStore.pickRoot(app)
        val all = dl.manifest()
        val vad = all.first { it.target == "vad/silero_vad_v5.onnx" }
        val small = all.filter { it.target.startsWith("tts/de/kerstin/") && it.size < 10_000 }

        // 1) resume: the first 1 MB of the VAD is already there as .part
        val part = File(root, vad.target + ".part").apply { parentFile!!.mkdirs() }
        URL(vad.url).openStream().use { ins ->
            FileOutputStream(part).use { out -> val b = ByteArray(1 shl 20); var n = 0; while (n < b.size) { val k = ins.read(b, n, b.size - n); if (k <= 0) break; n += k }; out.write(b, 0, n) }
        }
        assertEquals(1L shl 20, part.length())
        var reports = 0
        val r = dl.run(listOf(vad) + small, AtomicBoolean(false)) { reports++ }
        assertEquals(ModelDownloader.Result.Done, r)
        assertEquals(vad.size, File(root, vad.target).length())
        small.forEach { assertEquals(it.target, it.size, File(root, it.target).length()) }
        assertTrue("kein .part übrig", !part.exists())

        // 2) a wrong checksum is refused and nothing broken stays behind
        val bad = vad.copy(target = "vad/kaputt.onnx", sha256 = "0".repeat(64))
        val r2 = dl.run(listOf(bad), AtomicBoolean(false)) { }
        assertTrue(r2.toString(), r2 is ModelDownloader.Result.Failed && r2.message.contains("Prüfsumme"))
        assertTrue(!File(root, bad.target).exists() && !File(root, bad.target + ".part").exists())

        // 3) nothing left to do → done without touching the network
        assertEquals(ModelDownloader.Result.Done, dl.run(listOf(vad), AtomicBoolean(false)) { })
        println("BrainDownloadNetTest: ok, $reports Fortschrittsmeldungen")
    }
}
