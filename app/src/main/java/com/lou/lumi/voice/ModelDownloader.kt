package com.lou.lumi.voice

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * The one-time „Gehirn“ download for phones that do not have the models yet (everyone who installs
 * Lumi from a store). This is the **only network code in the app**:
 *
 *  * runs only when the user taps „Herunterladen“ – never by itself, never again once complete;
 *  * only HTTPS, only to Hugging Face hosts ([isAllowedHost], checked after every redirect);
 *  * every file pinned to one commit in `assets/brain_manifest.tsv` (tools/make_brain_manifest.py),
 *    size checked for all, SHA-256 for the large ones;
 *  * resumable: a `.part` file keeps what arrived, the next run continues with an HTTP Range request.
 */
class ModelDownloader(private val context: Context) {

    data class Entry(val url: String, val target: String, val size: Long, val sha256: String?)

    data class Progress(
        val done: Long, val total: Long, val file: String, val index: Int, val count: Int, val bytesPerSec: Long,
    ) {
        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    sealed class Result {
        object Done : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val root: File get() = ModelStore.pickRoot(context)

    fun manifest(): List<Entry> =
        context.assets.open(MANIFEST).bufferedReader().use { parseManifest(it.readText()) }

    /** Entries whose file is not on disk yet with the right size. */
    fun missing(): List<Entry> = manifest().filter { e ->
        val f = File(root, e.target)
        !(f.isFile && f.length() == e.size)
    }

    fun bytesMissing(): Long = missing().sumOf { e ->
        val part = File(root, e.target + ".part")
        e.size - (if (part.isFile) part.length().coerceAtMost(e.size) else 0L)
    }

    /** Mobile data (or a hotspot): the UI asks before 3 GB go over it. */
    fun isMetered(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false }.getOrDefault(false)

    fun freeBytes(): Long = root.apply { mkdirs() }.usableSpace

    /** Blocking; call from a background thread. */
    fun run(cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result = run(missing(), cancel, onProgress)

    /** [entries] instead of the whole manifest – the network test fetches only the small files. */
    internal fun run(entries: List<Entry>, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
        val todo = entries.filter { e -> File(root, e.target).let { !(it.isFile && it.length() == e.size) } }
        if (todo.isEmpty()) return Result.Done
        val total = todo.sumOf { it.size }
        val need = todo.sumOf { e -> e.size - File(root, e.target + ".part").let { if (it.isFile) it.length().coerceAtMost(e.size) else 0L } } +
            300L * 1024 * 1024
        if (freeBytes() < need) {
            return Result.Failed("Zu wenig Speicher: %.1f GB nötig, %.1f GB frei".format(need / 1e9, freeBytes() / 1e9))
        }
        var doneBefore = 0L
        val t0 = System.currentTimeMillis()
        var lastReport = 0L
        Log.i(TAG, "download: ${todo.size} Dateien, %.2f GB".format(total / 1e9))
        todo.forEachIndexed { i, e ->
            var attempt = 0
            while (true) {
                if (cancel.get()) return Result.Cancelled
                val r = runCatching {
                    fetch(e, cancel) { fileDone ->
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250L) {
                            lastReport = now
                            val done = doneBefore + fileDone
                            val secs = ((now - t0) / 1000L).coerceAtLeast(1L)
                            onProgress(Progress(done, total, e.target.substringAfterLast('/'), i + 1, todo.size, done / secs))
                        }
                    }
                }
                if (r.isSuccess) break
                if (cancel.get()) return Result.Cancelled
                val err = r.exceptionOrNull()
                Log.w(TAG, "download ${e.target} Versuch ${attempt + 1}: ${err?.message}")
                if (err is SecurityException || err is IntegrityException || ++attempt >= 4) {
                    return Result.Failed("${e.target.substringAfterLast('/')}: ${err?.message ?: "Fehler"}")
                }
                Thread.sleep(1500L * attempt)
            }
            doneBefore += e.size
        }
        Log.i(TAG, "download fertig in ${(System.currentTimeMillis() - t0) / 1000} s")
        return Result.Done
    }

    private class IntegrityException(msg: String) : IOException(msg)

    private fun fetch(e: Entry, cancel: AtomicBoolean, onBytes: (Long) -> Unit) {
        val target = File(root, e.target)
        val part = File(root, e.target + ".part")
        target.parentFile?.mkdirs()
        if (part.length() > e.size) part.delete()

        val digest = e.sha256?.let { MessageDigest.getInstance("SHA-256") }
        var have = if (part.isFile) part.length() else 0L
        if (digest != null && have > 0) {                 // resume: the hash has to see the old bytes too
            FileInputStream(part).use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n <= 0) break; digest.update(buf, 0, n) }
            }
        }

        if (have < e.size) {
            val conn = open(e.url, have)
            try {
                val code = conn.responseCode
                // the final host after redirects (Hugging Face hands large files to its CDN)
                if (!isAllowedHost(conn.url.host)) throw SecurityException("unerwarteter Server ${conn.url.host}")
                val append = when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> false
                    else -> throw IOException("HTTP $code")
                }
                if (!append && have > 0) {               // server ignored the range: start over
                    have = 0L
                    digest?.reset()
                }
                FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(1 shl 18)
                        while (true) {
                            if (cancel.get()) return
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            have += n
                            onBytes(have)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        if (part.length() != e.size) throw IOException("unvollständig (${part.length()} von ${e.size} Bytes)")
        if (digest != null) {
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(e.sha256, ignoreCase = true)) {
                part.delete()
                throw IntegrityException("Prüfsumme stimmt nicht")
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen")
    }

    private fun open(url: String, from: Long): HttpsURLConnection {
        val u = URL(url)
        require(u.protocol == "https" && isAllowedHost(u.host)) { "nicht erlaubt: $url" }
        val c = u.openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = true                  // https → https only (HttpURLConnection never downgrades)
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Lumi/1.0 (Android)")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    companion object {
        private const val TAG = "Lumi.DL"
        const val MANIFEST = "brain_manifest.tsv"

        /** Hugging Face and its download CDNs – nothing else. */
        fun isAllowedHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co" || h.endsWith(".hf.co")
        }

        fun parseManifest(text: String): List<Entry> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val c = line.split('\t')
                require(c.size == 4) { "Manifest-Zeile kaputt: $line" }
                Entry(c[0], c[1], c[2].toLong(), c[3].takeIf { it != "-" })
            }
            .toList()
    }
}
