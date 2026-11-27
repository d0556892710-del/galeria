package com.galeria.app.data

import android.content.Context
import android.util.JsonReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The on-device AI models. They are not inside the app (phones refuse to install an app of over a
 * gigabyte); the app downloads them once, in the background, resuming where it stopped, and checks
 * every file before using it. Builds that carry them inside (the test builds) use those directly.
 */
object Models {
    /** A fixed set of files (a new set gets a new address, so a file never changes under an app). */
    private const val BASE = "https://github.com/d0556892710-del/galeria/releases/download/models-1/"

    class Entry(val name: String, val size: Long, val sha256: String)

    /** Download state for the notification: bytes so far of all bytes, and whether everything is here. */
    data class Progress(val done: Long, val total: Long, val ready: Boolean)

    private val _progress = MutableStateFlow(Progress(0, 0, false))
    val progress: StateFlow<Progress> = _progress
    private val listeners = ArrayList<() -> Unit>()

    @Volatile private var manifest: List<Entry>? = null

    /** What the app needs, from the list bundled with it (name, size, SHA-256). */
    fun manifest(context: Context): List<Entry> = manifest ?: runCatching {
        val out = ArrayList<Entry>()
        context.assets.open("models/models.json").use { input ->
            JsonReader(input.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var name = ""; var size = 0L; var sha = ""
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "name" -> name = r.nextString(); "size" -> size = r.nextLong(); "sha256" -> sha = r.nextString(); else -> r.skipValue()
                    }
                    r.endObject()
                    if (name.isNotEmpty()) out += Entry(name, size, sha.lowercase())
                }
                r.endArray()
            }
        }
        out.toList()
    }.getOrDefault(emptyList()).also { manifest = it }

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Tesseract wants its files in a "tessdata" folder. */
    private fun target(context: Context, name: String): File =
        if (name.endsWith(".traineddata")) File(dir(context), "tessdata").apply { mkdirs() }.let { File(it, name) } else File(dir(context), name)

    private fun okMark(f: File) = File(f.parentFile, f.name + ".ok")

    private val bundledCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The size of [name] if it is carried inside the app (1 if its size is unknown), else -1. */
    private fun bundled(context: Context, name: String): Long = bundledCache.getOrPut(name) {
        runCatching { context.assets.openFd("models/$name").use { it.length } }.getOrElse {
            runCatching { context.assets.open("models/$name").use { if (it.available() > 0) 1L else -1L } }.getOrDefault(-1L)
        }
    }

    /** Whether [name] can be used now (downloaded and checked, or carried inside the app). */
    fun ready(context: Context, name: String): Boolean {
        val f = target(context, name)
        if (f.exists() && okMark(f).exists()) return true
        return bundled(context, name) > 0
    }

    /** The model's file on disk, or null if it isn't here yet. */
    fun file(context: Context, name: String): File? {
        val f = target(context, name)
        if (f.exists() && okMark(f).exists()) return f
        // Carried inside the app: copied out once (ONNX Runtime and Tesseract read files).
        val len = bundled(context, name)
        if (len <= 0) return null
        if (!f.exists() || (len > 1 && f.length() != len)) {
            val tmp = File(f.parentFile, "$name.tmp")
            context.assets.open("models/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.renameTo(f)
        }
        return f
    }

    /** Called once every model is here (features that need them then start). */
    fun onReady(l: () -> Unit) { synchronized(listeners) { listeners += l } }

    /** True if something still has to be downloaded. */
    fun missing(context: Context): List<Entry> = manifest(context).filter { !ready(context, it.name) }

    /**
     * Downloads whatever is missing (blocking; on a background thread). Resumes partial files, checks
     * each one's SHA-256, and retries a few times. Returns true when everything is here.
     */
    fun download(context: Context): Boolean {
        val all = manifest(context)
        if (all.isEmpty()) return false
        // Files left by earlier versions (copied out of the app) are kept if they check out.
        for (e in all) {
            val f = target(context, e.name)
            if (f.exists() && !okMark(f).exists() && f.length() == e.size && sha(f) == e.sha256) okMark(f).writeText("ok")
        }
        val todo = all.filter { !ready(context, it.name) }
        val total = all.sumOf { it.size }
        var have = all.filter { ready(context, it.name) }.sumOf { it.size }
        _progress.value = Progress(have, total, todo.isEmpty())
        for (e in todo) {
            var tries = 0
            while (true) {
                try {
                    fetch(context, e) { n -> _progress.value = Progress(have + n, total, false) }
                    have += e.size
                    _progress.value = Progress(have, total, false)
                    break
                } catch (x: IOException) {
                    android.util.Log.w("Photos", "model download ${e.name}", x)
                    if (++tries >= 5) return false
                    Thread.sleep(3000L * tries)
                }
            }
        }
        _progress.value = Progress(total, total, true)
        android.util.Log.i("PhotosPerf", "models ready n=${all.size} bytes=$total")
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
        return true
    }

    /** One file: resumes from its .part, then checks it. [onBytes] gets this file's bytes so far. */
    private fun fetch(context: Context, e: Entry, onBytes: (Long) -> Unit) {
        val f = target(context, e.name)
        val part = File(f.parentFile, e.name + ".part")
        if (part.exists() && part.length() > e.size) part.delete()
        if (part.length() < e.size) {
            val c = (URL(BASE + e.name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            try {
                val code = c.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                // A server that ignores the range sends everything again.
                val start = if (code == 206) part.length() else 0L
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(start); out.seek(start)
                    var n = start
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    c.inputStream.use { input ->
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r); n += r
                            if (n - last > (1 shl 20)) { onBytes(n); last = n }
                        }
                    }
                    if (n != e.size) throw IOException("short ${e.name}: $n of ${e.size}")
                }
            } finally { c.disconnect() }
        }
        if (sha(part) != e.sha256) { part.delete(); throw IOException("checksum ${e.name}") }
        f.delete()
        if (!part.renameTo(f)) throw IOException("rename ${e.name}")
        okMark(f).writeText("ok")
        onBytes(e.size)
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
