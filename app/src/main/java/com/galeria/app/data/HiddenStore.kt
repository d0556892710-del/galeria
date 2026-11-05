package com.galeria.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Hidden items live in the app's private storage (no other app or gallery can see them;
 * Android encrypts this storage). An index keeps the original metadata so items can be
 * restored to the library exactly as they were.
 */
class HiddenStore(context: Context) {
    val dir: File = File(context.filesDir, "hidden").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val lock = Any()
    private val _items = MutableStateFlow<List<Media>>(emptyList())
    val items: StateFlow<List<Media>> = _items.asStateFlow()

    private data class Entry(
        val file: String, val sourceId: Long, val name: String, val mime: String, val video: Boolean,
        val taken: Long, val w: Int, val h: Int, val orientation: Int, val duration: Long, val size: Long,
        val rel: String, val bucket: String, val hiddenAt: Long,
    )

    private var entries = mutableListOf<Entry>()

    fun load() = synchronized(lock) {
        entries = runCatching {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    o.getString("file"), o.optLong("src"), o.optString("name"), o.optString("mime"), o.optBoolean("video"),
                    o.optLong("taken"), o.optInt("w"), o.optInt("h"), o.optInt("or"), o.optLong("dur"), o.optLong("size"),
                    o.optString("rel"), o.optString("bucket"), o.optLong("at"),
                )
            }.filter { File(dir, it.file).exists() }.toMutableList()
        }.getOrDefault(mutableListOf())
        publish()
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().apply {
                put("file", e.file); put("src", e.sourceId); put("name", e.name); put("mime", e.mime); put("video", e.video)
                put("taken", e.taken); put("w", e.w); put("h", e.h); put("or", e.orientation); put("dur", e.duration)
                put("size", e.size); put("rel", e.rel); put("bucket", e.bucket); put("at", e.hiddenAt)
            })
        }
        val tmp = File(dir, "index.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(index)
    }

    private fun publish() {
        _items.value = entries.mapIndexed { i, e ->
            val f = File(dir, e.file)
            Media(
                id = -(1000L + i), uri = Uri.fromFile(f), isVideo = e.video, mime = e.mime, dateTaken = e.taken,
                dateAdded = e.hiddenAt, dateModified = e.hiddenAt, width = e.w, height = e.h, orientation = e.orientation,
                duration = e.duration, size = e.size, bucketId = 0, bucketName = e.bucket, relativePath = e.rel,
                displayName = e.name, isFavorite = false, hiddenFile = e.file,
            )
        }.sortedBy { it.dateTaken }
    }

    fun import(m: Media, open: () -> InputStream?): Boolean {
        val ext = m.displayName.substringAfterLast('.', if (m.isVideo) "mp4" else "jpg")
        val name = "${UUID.randomUUID()}.$ext"
        val f = File(dir, name)
        val ok = runCatching { open()?.use { input -> f.outputStream().use { input.copyTo(it, 1 shl 16) } } != null }.getOrDefault(false)
        if (!ok || f.length() == 0L) {
            f.delete(); return false
        }
        synchronized(lock) {
            entries += Entry(
                name, m.id, m.displayName, m.mime, m.isVideo, m.dateTaken, m.width, m.height, m.orientation, m.duration,
                f.length(), m.relativePath, m.bucketName, System.currentTimeMillis(),
            )
            save(); publish()
        }
        return true
    }

    fun removeImported(sourceIds: List<Long>) = synchronized(lock) {
        val gone = entries.filter { it.sourceId in sourceIds }
        gone.forEach { File(dir, it.file).delete() }
        entries.removeAll(gone)
        save(); publish()
    }

    fun delete(m: Media) = synchronized(lock) {
        val name = m.hiddenFile ?: return@synchronized
        File(dir, name).delete()
        entries.removeAll { it.file == name }
        save(); publish()
    }
}
