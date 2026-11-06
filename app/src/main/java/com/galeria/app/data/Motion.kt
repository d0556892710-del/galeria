package com.galeria.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Where the video of a Live (motion) photo sits inside its JPEG. */
data class MotionInfo(val start: Long, val length: Long, val keyUs: Long)

/** Kinds of photo found by reading the file's own metadata. */
data class MediaTags(val live: Set<Long> = emptySet(), val portrait: Set<Long> = emptySet())

/**
 * Live Photos on Android are "motion photos": a JPEG with a short MP4 appended and XMP that says
 * where it is (Google / Xiaomi / most phones). This reads only the JPEG headers of each photo once,
 * in the background, and remembers the answer.
 */
class MotionStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tags = MutableStateFlow(MediaTags())
    val tags: StateFlow<MediaTags> = _tags.asStateFlow()

    private data class Entry(val modified: Long, val flags: Int)
    private val known = HashMap<Long, Entry>()
    private var cacheLoaded = false
    private val cacheFile get() = File(context.filesDir, "media_tags_v1.txt")
    private var job: Job? = null

    fun scan(lib: List<Media>) {
        job?.cancel()
        job = scope.launch {
            if (!cacheLoaded) { loadCache(); cacheLoaded = true }
            val images = lib.filter { !it.isVideo && it.hiddenFile == null && isJpeg(it) }
            publish(images)
            var changed = false
            var sincePublish = 0
            // Newest first: those are the ones people look at.
            for (m in images.asReversed()) {
                if (!isActive) break
                val e = known[m.id]
                if (e != null && e.modified == m.dateModified) continue
                known[m.id] = Entry(m.dateModified, detect(m))
                changed = true
                if (++sincePublish >= 150) { sincePublish = 0; publish(images); saveCache() }
            }
            if (changed) { publish(images); saveCache() }
        }
    }

    /** Re-read one photo after it was edited. */
    fun rescan(m: Media) = scope.launch {
        known[m.id] = Entry(m.dateModified, detect(m))
        val cur = _tags.value
        val f = known[m.id]!!.flags
        _tags.value = MediaTags(
            if (f and LIVE != 0) cur.live + m.id else cur.live - m.id,
            if (f and PORTRAIT != 0) cur.portrait + m.id else cur.portrait - m.id,
        )
        saveCache()
    }

    private fun publish(images: List<Media>) {
        val live = HashSet<Long>(); val portrait = HashSet<Long>()
        for (m in images) {
            val e = known[m.id] ?: continue
            if (e.modified != m.dateModified) continue
            if (e.flags and LIVE != 0) live += m.id
            if (e.flags and PORTRAIT != 0) portrait += m.id
        }
        _tags.value = MediaTags(live, portrait)
    }

    private fun detect(m: Media): Int = runCatching {
        val xmp = context.contentResolver.openInputStream(m.uri)?.use { readXmp(it) } ?: ""
        flagsOf(xmp, m.displayName)
    }.getOrDefault(0)

    private fun loadCache() = runCatching {
        cacheFile.forEachLine { line ->
            val p = line.split(',')
            if (p.size == 3) known[p[0].toLong()] = Entry(p[1].toLong(), p[2].toInt())
        }
    }

    private fun saveCache() = runCatching {
        val tmp = File(context.filesDir, "media_tags_v1.tmp")
        tmp.bufferedWriter().use { w -> for ((id, e) in known) { w.write("$id,${e.modified},${e.flags}\n") } }
        tmp.renameTo(cacheFile)
    }

    // ---------------------------------------------------------------- video

    /** The Live Photo's video as a playable file (extracted once into the cache). */
    suspend fun video(m: Media): Pair<File, MotionInfo>? = withContext(Dispatchers.IO) {
        runCatching {
            val info = info(m) ?: return@runCatching null
            val dir = File(context.cacheDir, "live").apply { mkdirs() }
            val out = File(dir, "${m.id}_${m.dateModified}.mp4")
            if (!out.exists() || out.length() != info.length) {
                context.contentResolver.openFileDescriptor(m.uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        val tmp = File(dir, out.name + ".tmp")
                        tmp.outputStream().channel.use { o -> ch.transferTo(info.start, info.length, o) }
                        tmp.renameTo(out)
                    }
                }
            }
            if (out.exists()) out to info else null
        }.getOrNull()
    }

    fun info(m: Media): MotionInfo? {
        val r = context.contentResolver
        val size = r.openFileDescriptor(m.uri, "r")?.use { it.statSize } ?: return null
        val xmp = r.openInputStream(m.uri)?.use { readXmp(it) } ?: ""
        return infoFromXmp(xmp, size) { r.openFileDescriptor(m.uri, "r")?.use { pfd -> findMp4(FileInputStream(pfd.fileDescriptor), size) } }
    }

    fun infoFromXmp(xmp: String, size: Long, fallback: () -> Long?): MotionInfo? {
        val keyUs = num(xmp, "MotionPhotoPresentationTimestampUs") ?: num(xmp, "MicroVideoPresentationTimestampUs") ?: -1L
        var len = itemLength(xmp) ?: num(xmp, "MicroVideoOffset")
        if (len == null || len <= 0 || len >= size) {
            // No usable directory: look for the MP4 header in the tail of the file.
            val start = fallback() ?: return null
            len = size - start
        }
        return MotionInfo(size - len, len, keyUs)
    }

    fun findMp4InFile(f: File): Long? = runCatching { FileInputStream(f).use { findMp4(it, f.length()) } }.getOrNull()

    private fun findMp4(input: FileInputStream, size: Long): Long? {
        val window = minOf(size, 24L * 1024 * 1024)
        val from = size - window
        val ch = input.channel
        val buf = java.nio.ByteBuffer.allocate(window.toInt())
        ch.position(from)
        while (buf.hasRemaining() && ch.read(buf) > 0) Unit
        val a = buf.array()
        var i = 4
        while (i < a.size - 8) {
            if (a[i] == 'f'.code.toByte() && a[i + 1] == 't'.code.toByte() && a[i + 2] == 'y'.code.toByte() && a[i + 3] == 'p'.code.toByte()) {
                val boxSize = ((a[i - 4].toInt() and 0xff) shl 24) or ((a[i - 3].toInt() and 0xff) shl 16) or ((a[i - 2].toInt() and 0xff) shl 8) or (a[i - 1].toInt() and 0xff)
                if (boxSize in 8..256) return from + i - 4
            }
            i++
        }
        return null
    }

    companion object {
        const val LIVE = 1
        const val PORTRAIT = 2

        fun isJpeg(m: Media) = m.mime.equals("image/jpeg", true) || m.displayName.endsWith(".jpg", true) || m.displayName.endsWith(".jpeg", true)

        fun flagsOf(xmp: String, name: String): Int {
            var f = 0
            val motion = Regex("""(MotionPhoto|MicroVideo)\s*=\s*"1"|<GCamera:(MotionPhoto|MicroVideo)>1<""").containsMatchIn(xmp) ||
                name.startsWith("MVIMG_", true) || name.contains(".MP.", true)
            if (motion) f = f or LIVE
            if (xmp.contains("GDepth:") || xmp.contains("DepthMap", true) || Regex("""(?i)(portrait|bokeh)\w*\s*=\s*"(1|true)"""").containsMatchIn(xmp)) f = f or PORTRAIT
            return f
        }

        private fun num(xmp: String, name: String): Long? =
            Regex("""$name\s*=\s*"(\d+)"""").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("""<[\w:]*$name>(\d+)<""").find(xmp)?.groupValues?.get(1)?.toLongOrNull()

        private fun itemLength(xmp: String): Long? {
            for (tag in Regex("""<Container:Item\b[^>]*>""").findAll(xmp)) {
                val t = tag.value
                if (t.contains("Semantic=\"MotionPhoto\"")) return Regex("""Item:Length\s*=\s*"(\d+)"""").find(t)?.groupValues?.get(1)?.toLongOrNull()
            }
            // rdf:li form: <Container:Item><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>N</Item:Length>
            val m = Regex("""Semantic>MotionPhoto<.*?Length>(\d+)<""", RegexOption.DOT_MATCHES_ALL).find(xmp)
            return m?.groupValues?.get(1)?.toLongOrNull()
        }

        /** Reads only the header segments of a JPEG and returns its XMP packet, if any. */
        fun readXmp(input: InputStream): String? {
            val d = DataInputStream(BufferedInputStream(input, 8192))
            if (d.readUnsignedShort() != 0xFFD8) return null
            val sig = "http://ns.adobe.com/xap/1.0/\u0000"
            while (true) {
                if (d.readUnsignedByte() != 0xFF) return null
                var type = d.readUnsignedByte()
                while (type == 0xFF) type = d.readUnsignedByte()
                if (type == 0xD9 || type == 0xDA) return null
                if (type in 0xD0..0xD7 || type == 0x01) continue
                val len = d.readUnsignedShort() - 2
                if (len < 0) return null
                if (type == 0xE1 && len > sig.length) {
                    val head = ByteArray(sig.length)
                    d.readFully(head)
                    if (String(head, Charsets.ISO_8859_1) == sig) {
                        val body = ByteArray(len - sig.length)
                        d.readFully(body)
                        return String(body, Charsets.UTF_8)
                    }
                    skip(d, (len - sig.length).toLong())
                } else skip(d, len.toLong())
            }
        }

        private fun skip(d: DataInputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = d.skip(left)
                if (s <= 0) { if (d.read() < 0) return; left-- } else left -= s
            }
        }

        /**
         * Builds a motion-photo JPEG: the still, XMP that points at the video (both the current
         * Google format and the older MicroVideo one, which Xiaomi reads), then the MP4 itself.
         */
        fun buildMotionJpeg(jpeg: ByteArray, video: ByteArray, keyUs: Long): ByteArray {
            val ts = if (keyUs >= 0) keyUs else 0L
            val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Photos"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$ts" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${video.size}" GCamera:MicroVideoPresentationTimestampUs="$ts"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
            val seg = ByteArrayOutputStream()
            seg.write(0xFF); seg.write(0xE1)
            val l = payload.size + 2
            seg.write(l shr 8 and 0xff); seg.write(l and 0xff)
            seg.write(payload)
            val out = ByteArrayOutputStream(jpeg.size + payload.size + video.size + 16)
            // Keep EXIF (APP1) first, then our XMP.
            var pos = 2
            out.write(jpeg, 0, 2)
            while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xff) == 0xFF) {
                val type = jpeg[pos + 1].toInt() and 0xff
                if (type != 0xE0 && type != 0xE1) break
                val len = ((jpeg[pos + 2].toInt() and 0xff) shl 8) or (jpeg[pos + 3].toInt() and 0xff)
                out.write(jpeg, pos, len + 2)
                pos += len + 2
            }
            out.write(seg.toByteArray())
            out.write(jpeg, pos, jpeg.size - pos)
            out.write(video)
            return out.toByteArray()
        }
    }
}
