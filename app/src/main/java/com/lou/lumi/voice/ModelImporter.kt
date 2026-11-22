package com.lou.lumi.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * Brings the model files into the app's private directory without adb.
 *
 * Three ways in: a ZIP archive (easiest – `zip -r models.zip models/`), a picked folder
 * (Storage Access Framework tree), or single files assigned to one of the five slots.
 * The classifier recognises the sherpa-onnx / LiteRT layouts by file name and directory
 * shape, so the user does not have to mirror our internal structure.
 */
class ModelImporter(private val context: Context) {

    data class Progress(val phase: String, val current: String, val index: Int, val total: Int, val doneBytes: Long, val totalBytes: Long)
    data class Outcome(
        val planned: Int,
        val copied: List<String>,
        val skipped: List<String>,
        val failed: List<String>,
        val bytes: Long,
        val notes: List<String>,
    )

    class Node(
        val path: String,
        val name: String,
        val size: Long,
        val zipEntry: String?,
        val doc: DocumentFile?,
    ) {
        val parent: String get() = path.substringBeforeLast('/', "")
    }

    private val root: File = ModelStore.pickRoot(context)

    // ------------------------------------------------------------------ public entry points

    fun importZip(uri: Uri, onProgress: (Progress) -> Unit): Outcome {
        val stage = File(context.cacheDir, "import.zip")
        stage.parentFile?.mkdirs()
        // The archive has to be staged (ZipFile needs random access) on top of the models,
        // so the cache must hold it before we even start.
        val archiveSize = DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
        if (archiveSize > 0 && context.cacheDir.usableSpace < archiveSize + (1L shl 30)) {
            return Outcome(
                0, emptyList(), emptyList(),
                listOf("Zu wenig Platz im App-Cache: Archiv %.1f GB, frei %.1f GB".format(archiveSize / 1e9, context.cacheDir.usableSpace / 1e9)),
                0, listOf("Alternative: „Ordner mit den Modell-Unterordnern“ wählen – dann wird nichts zwischen geparkt."),
            )
        }
        copyStream({ context.contentResolver.openInputStream(uri) }, stage) { done ->
            onProgress(Progress("Archiv laden", stage.name, 0, 0, done, -1L))
        }
        try {
            return importZipFile(stage, onProgress)
        } finally {
            runCatching { stage.delete() }
        }
    }

    fun importZipFile(zip: File, onProgress: (Progress) -> Unit): Outcome = ZipFile(zip).use { zf ->
        val nodes = zf.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('/').isNotEmpty() }
            .map { e ->
                Node(normalise(e.name), e.name.substringAfterLast('/'), e.size, e.name, null)
            }
            .toList()
        plan(nodes, onProgress) { n -> { zf.getInputStream(zf.getEntry(n.zipEntry!!)) } }
    }

    fun importTree(treeUri: Uri, onProgress: (Progress) -> Unit): Outcome {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Outcome(0, emptyList(), emptyList(), listOf("Ordner konnte nicht gelesen werden"), 0, emptyList())
        val nodes = ArrayList<Node>()
        walk(tree, "", nodes)
        Log.i(TAG, "tree ${tree.name}: ${nodes.size} Dateien")
        onProgress(Progress("Ordner gelesen", tree.name ?: "", nodes.size, nodes.size, 0, 0))
        return plan(nodes, onProgress) { n -> { context.contentResolver.openInputStream(n.doc!!.uri) } }
    }

    /** One file into one slot (the file keeps its own name). */
    fun importSingle(uri: Uri, slotDir: String, onProgress: (Progress) -> Unit): Outcome {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: "model.bin"
        val node = Node(name, name, doc?.length() ?: 0L, null, doc)
        val target = File(File(root, slotDir), name)
        onProgress(Progress("Kopieren", name, 1, 1, 0, node.size))
        val copied = runCatching { copyTo(node, target) }
            .onFailure { Log.e(TAG, "import of $name failed", it) }
        val ok = copied.isSuccess
        return Outcome(
            planned = 1,
            copied = if (ok) listOf(target.name) else emptyList(),
            skipped = emptyList(),
            failed = if (ok) emptyList() else listOf("$name: ${copied.exceptionOrNull()?.message}"),
            bytes = if (ok) node.size else 0L,
            notes = listOf("Ziel: ${target.path}"),
        )
    }

    /** Slot-based folder import, e.g. an espeak-ng-data directory → tts/de/espeak-ng-data. */
    fun importTreeInto(uri: Uri, targetDir: String, onProgress: (Progress) -> Unit): Outcome {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: return Outcome(0, emptyList(), emptyList(), listOf("Ordner konnte nicht gelesen werden"), 0, emptyList())
        val nodes = ArrayList<Node>()
        walk(tree, "", nodes)
        val dest = File(root, targetDir)
        val items = nodes.map { Item(it.name, it.size, File(dest, it.path)) { context.contentResolver.openInputStream(it.doc!!.uri) } }
        return write(items, emptyList(), onProgress)
    }

    // ------------------------------------------------------------------ classification

    private class Item(val name: String, val size: Long, val target: File, val open: () -> InputStream?)

    private fun plan(nodes: List<Node>, onProgress: (Progress) -> Unit, source: (Node) -> () -> InputStream?): Outcome {
        val layoutNodes = nodes.map { ModelLayout.Node(it.path, it.name, it.size) }
        val byPath = nodes.associateBy { it.path }
        val items = ModelLayout.classify(layoutNodes, root).mapNotNull { p ->
            byPath[p.node.path]?.let { Item(p.target.name, p.node.size, p.target, source(it)) }
        }
        val notes = ArrayList(ModelLayout.notes(layoutNodes, ModelLayout.classify(layoutNodes, root), root))
        if (items.isEmpty()) {
            notes += "Nichts erkannt – bitte einen Modellordner oder ein ZIP mit vad/, asr/, tts/…, gemma/ wählen"
        }
        if (items.isNotEmpty()) {
            val totalBytes = items.sumOf { max(0L, it.size) }
            val free = root.usableSpace
            if (totalBytes > 0 && free < totalBytes + 512L * 1024 * 1024) {
                return Outcome(
                    items.size, emptyList(), emptyList(),
                    listOf("Zu wenig Speicher: %.1f GB nötig, %.1f GB frei".format(totalBytes / 1e9, free / 1e9)),
                    0, notes,
                )
            }
        }
        return write(items, notes, onProgress)
    }

    private fun write(items: List<Item>, notes: List<String>, onProgress: (Progress) -> Unit): Outcome {
        val copied = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        var bytes = 0L
        val total = items.sumOf { max(0L, it.size) }
        var done = 0L
        items.forEachIndexed { i, item ->
            val name = item.target.name
            if (item.target.isFile && item.size > 0 && item.target.length() == item.size) {
                skipped += name
                done += item.size
                onProgress(Progress("übersprungen (identisch)", name, i + 1, items.size, done, total))
                return@forEachIndexed
            }
            onProgress(Progress("kopiere", name, i + 1, items.size, done, total))
            val res = runCatching {
                item.target.parentFile?.mkdirs()
                copyStream(item.open, item.target) { }
            }
            if (res.isSuccess) {
                bytes += item.size
                done += max(0L, item.size)
                copied += name
            } else {
                failed += "$name: ${res.exceptionOrNull()?.message ?: "Fehler"}"
                runCatching { item.target.delete() }
            }
        }
        Log.i(TAG, "import: ${copied.size} kopiert, ${skipped.size} übersprungen, ${failed.size} Fehler, ${bytes / 1e6} MB")
        return Outcome(items.size, copied, skipped, failed, bytes, notes)
    }

    // ------------------------------------------------------------------ helpers

    private fun walk(doc: DocumentFile, prefix: String, out: ArrayList<Node>) {
        for (child in doc.listFiles()) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (ModelLayout.isSkippedDir(name)) continue
                walk(child, path, out)
            } else if (child.isFile) {
                // No extension filter here: the eSpeak phonemiser data is full of extension-less
                // files (phon, phontab, de_dict, voices/de_de/f1 …).
                if (ModelLayout.isSkippedFile(name)) continue
                out += Node(path, name, child.length(), null, child)
            }
        }
    }

    private fun normalise(entry: String): String {
        var p = entry.replace('\\', '/').trimStart('/')
        // "models/…", "./models/…" and the tarball's own folder are noise for the classifier
        val parts = p.split('/').filter { it != "." }
        return parts.joinToString("/")
    }

    private fun copyStream(open: () -> InputStream?, target: File, onBytes: (Long) -> Unit) {
        FileOutputStream(target).use { out ->
            open().use { input ->
                requireNotNull(input) { "Quelle nicht lesbar" }
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
                out.flush()
            }
        }
    }

    private fun copyTo(n: Node, target: File): Long {
        target.parentFile?.mkdirs()
        var written = 0L
        val input = when {
            n.doc != null -> context.contentResolver.openInputStream(n.doc.uri)
            n.zipEntry != null -> null
            else -> FileInputStream(File(n.path))
        }
        input.use { ins ->
            requireNotNull(ins) { "Quelle nicht lesbar" }
            FileOutputStream(target).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val k = ins.read(buf)
                    if (k <= 0) break
                    out.write(buf, 0, k)
                    written += k
                }
            }
        }
        return written
    }

    private fun canonical(f: File): String = f.absolutePath

    private companion object {
        const val TAG = "Lumi.IMPORT"


    }
}
