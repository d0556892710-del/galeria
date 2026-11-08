package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** A person found in your photos (People). */
data class PersonInfo(val id: Long, val name: String, val mediaIds: Set<Long>, val avatar: String?)

/**
 * People, on the device: faces are found by Google's on-device detector and matched with a
 * classic face-texture signature (uniform LBP histograms). Grouping is cautious; you can name,
 * merge and correct people, and those choices are kept.
 */
class FaceStore(private val context: Context) {
    private class FaceRec(val key: String, val mediaId: Long, val desc: ShortArray, val crop: String, var person: Long)
    private data class PersonRec(val id: Long, var name: String, var hidden: Boolean)

    private val faces = ArrayList<FaceRec>()
    private val persons = LinkedHashMap<Long, PersonRec>()
    private val done = HashMap<Long, Long>() // mediaId -> modified when processed
    private val centroids = HashMap<Long, FloatArray>()
    private val counts = HashMap<Long, Int>()
    private var loaded = false
    private val dir = File(context.filesDir, "faces").apply { mkdirs() }
    private val _people = MutableStateFlow<List<PersonInfo>>(emptyList())
    val people: StateFlow<List<PersonInfo>> = _people.asStateFlow()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(.1f)
                .build()
        )
    }

    @Synchronized fun ensureLoaded() { if (!loaded) { load(); loaded = true; publish() } }
    @Synchronized fun needs(m: Media): Boolean = done[m.id] != m.dateModified

    /** Finds and matches the faces in one photo (runs on the indexing thread). */
    fun process(m: Media, bmp: Bitmap) {
        val found = runCatching { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) }.getOrDefault(emptyList())
        val recs = ArrayList<Pair<ShortArray, Bitmap>>()
        for (f in found) {
            if (f.boundingBox.width() < 56 || abs(f.headEulerAngleY) > 32f) continue
            describe(bmp, f)?.let { recs += it }
        }
        synchronized(this) {
            faces.removeAll { it.mediaId == m.id && it.key.endsWith("@${m.dateModified}").not() }
            recs.forEachIndexed { i, (d, crop) ->
                val key = "${m.id}:$i@${m.dateModified}"
                if (faces.any { it.key == key }) return@forEachIndexed
                val file = File(dir, "${m.id}_$i.jpg")
                runCatching { file.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                crop.recycle()
                faces += FaceRec(key, m.id, d, file.absolutePath, assign(d))
            }
            done[m.id] = m.dateModified
        }
    }

    /** Nearest person if close enough, otherwise a new one. */
    private fun assign(d: ShortArray): Long {
        val v = FloatArray(d.size) { d[it] / 10000f }
        var best = -1L; var bestD = Float.MAX_VALUE
        for ((id, c) in centroids) {
            if (persons[id]?.hidden == true) continue
            val dist = chi2(v, c)
            if (dist < bestD) { bestD = dist; best = id }
        }
        val id = if (best >= 0 && bestD < THRESHOLD) best else {
            val nid = (persons.keys.maxOrNull() ?: 0L) + 1
            persons[nid] = PersonRec(nid, "", false); nid
        }
        val c = centroids.getOrPut(id) { FloatArray(v.size) }
        val n = (counts[id] ?: 0) + 1
        for (k in c.indices) c[k] += (v[k] - c[k]) / n
        counts[id] = n
        return id
    }

    @Synchronized fun rename(id: Long, name: String) { persons[id]?.name = name.trim(); save(); publish() }
    @Synchronized fun hide(id: Long) { persons[id]?.hidden = true; save(); publish() }

    /** Everything of [from] becomes [into]. */
    @Synchronized fun merge(into: Long, from: Long) {
        if (into == from) return
        faces.forEach { if (it.person == from) it.person = into }
        val name = persons[into]?.name.orEmpty().ifBlank { persons[from]?.name.orEmpty() }
        persons[into]?.name = name
        persons.remove(from)
        rebuildCentroids(); save(); publish()
    }

    /** "Not This Person": those photos' faces leave the person (and stay out). */
    @Synchronized fun detach(id: Long, mediaIds: Set<Long>) {
        var next = (persons.keys.maxOrNull() ?: 0L) + 1
        faces.filter { it.person == id && it.mediaId in mediaIds }.forEach {
            persons[next] = PersonRec(next, "", true); it.person = next; next++
        }
        rebuildCentroids(); save(); publish()
    }

    @Synchronized fun finishScan(existing: Set<Long>) {
        faces.removeAll { it.mediaId !in existing }
        done.keys.retainAll(existing)
        val used = faces.mapTo(HashSet()) { it.person }
        persons.keys.retainAll(used)
        rebuildCentroids(); save(); publish()
    }

    @Synchronized fun publish() {
        val byPerson = faces.groupBy { it.person }
        _people.value = persons.values.filter { !it.hidden }.mapNotNull { p ->
            val fs = byPerson[p.id] ?: return@mapNotNull null
            val ids = fs.mapTo(LinkedHashSet()) { it.mediaId }
            // Only people seen in several photos (or named) are shown, like iPhone.
            if (ids.size < MIN_PHOTOS && p.name.isBlank()) null else PersonInfo(p.id, p.name, ids, fs.last().crop)
        }.sortedWith(compareByDescending<PersonInfo> { it.name.isNotBlank() }.thenByDescending { it.mediaIds.size })
    }

    private fun rebuildCentroids() {
        centroids.clear(); counts.clear()
        for (f in faces) {
            val c = centroids.getOrPut(f.person) { FloatArray(f.desc.size) }
            val n = (counts[f.person] ?: 0) + 1
            for (k in c.indices) c[k] += (f.desc[k] / 10000f - c[k]) / n
            counts[f.person] = n
        }
    }

    // ---------------------------------------------------------------- persistence

    @Synchronized fun save() = runCatching {
        val tmp = File(dir, "faces.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(2); o.writeInt(faces.size)
            for (f in faces) {
                o.writeUTF(f.key); o.writeLong(f.mediaId); o.writeUTF(f.crop); o.writeLong(f.person)
                o.writeInt(f.desc.size); f.desc.forEach { o.writeShort(it.toInt()) }
            }
            o.writeInt(done.size); for ((k, v) in done) { o.writeLong(k); o.writeLong(v) }
        }
        tmp.renameTo(File(dir, "faces.bin"))
        val arr = JSONArray()
        persons.values.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("hidden", it.hidden)) }
        File(dir, "people.json").writeText(arr.toString())
    }

    private fun load() = runCatching {
        File(dir, "people.json").takeIf { it.exists() }?.let { f ->
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                persons[o.getLong("id")] = PersonRec(o.getLong("id"), o.optString("name"), o.optBoolean("hidden"))
            }
        }
        val bin = File(dir, "faces.bin")
        if (bin.exists()) DataInputStream(bin.inputStream().buffered()).use { i ->
            if (i.readInt() != 2) return@use
            repeat(i.readInt()) {
                val key = i.readUTF(); val mid = i.readLong(); val crop = i.readUTF(); val person = i.readLong()
                val d = ShortArray(i.readInt()) { i.readShort() }
                faces += FaceRec(key, mid, d, crop, person)
                if (person !in persons) persons[person] = PersonRec(person, "", false)
            }
            repeat(i.readInt()) { done[i.readLong()] = i.readLong() }
        }
        rebuildCentroids()
    }

    companion object {
        private const val THRESHOLD = 1.55f
        private const val MIN_PHOTOS = 3
        private const val SIDE = 64
        private const val GRID = 3

        private val UNIFORM: IntArray = IntArray(256).also { map ->
            var next = 0
            for (v in 0 until 256) {
                var t = 0
                for (b in 0 until 8) if (((v shr b) and 1) != ((v shr ((b + 1) % 8)) and 1)) t++
                map[v] = if (t <= 2) next++ else -1
            }
            for (v in 0 until 256) if (map[v] < 0) map[v] = 58
        }

        fun chi2(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (k in a.indices) { val d = a[k] - b[k]; val q = a[k] + b[k]; if (q > 1e-6f) s += d * d / q }
            return s
        }

        /** Aligned, equalised face → uniform-LBP histograms on a 3×3 grid; plus a colour crop for People. */
        fun describe(bmp: Bitmap, f: Face): Pair<ShortArray, Bitmap>? {
            val box = f.boundingBox
            val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val angle = if (le != null && re != null) Math.toDegrees(atan2((re.y - le.y).toDouble(), (re.x - le.x).toDouble())).toFloat() else f.headEulerAngleZ
            val cx = box.exactCenterX(); val cy = box.exactCenterY()
            val side = max(box.width(), box.height()) * 1.15f
            fun render(size: Int): Bitmap {
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val m = Matrix().apply {
                    postTranslate(-cx, -cy); postRotate(-angle); postScale(size / side, size / side); postTranslate(size / 2f, size / 2f)
                }
                Canvas(out).apply { drawColor(android.graphics.Color.GRAY); drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
                return out
            }
            val small = render(SIDE)
            val px = IntArray(SIDE * SIDE).also { small.getPixels(it, 0, SIDE, 0, 0, SIDE, SIDE) }
            small.recycle()
            // Grey + histogram equalisation (evens out lighting).
            val g = IntArray(px.size) { val c = px[it]; (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8 }
            val hist = IntArray(256); g.forEach { hist[it]++ }
            val cdf = IntArray(256); var acc = 0
            for (v in 0 until 256) { acc += hist[v]; cdf[v] = acc }
            val lo = cdf.first { it > 0 }
            for (k in g.indices) g[k] = ((cdf[g[k]] - lo) * 255f / max(1, g.size - lo)).toInt().coerceIn(0, 255)
            val cell = SIDE / GRID
            val out = FloatArray(GRID * GRID * 59)
            for (y in 1 until SIDE - 1) for (x in 1 until SIDE - 1) {
                val c = g[y * SIDE + x]
                var code = 0
                if (g[(y - 1) * SIDE + x - 1] >= c) code = code or 1
                if (g[(y - 1) * SIDE + x] >= c) code = code or 2
                if (g[(y - 1) * SIDE + x + 1] >= c) code = code or 4
                if (g[y * SIDE + x + 1] >= c) code = code or 8
                if (g[(y + 1) * SIDE + x + 1] >= c) code = code or 16
                if (g[(y + 1) * SIDE + x] >= c) code = code or 32
                if (g[(y + 1) * SIDE + x - 1] >= c) code = code or 64
                if (g[y * SIDE + x - 1] >= c) code = code or 128
                val cxI = (x / cell).coerceAtMost(GRID - 1); val cyI = (y / cell).coerceAtMost(GRID - 1)
                out[(cyI * GRID + cxI) * 59 + UNIFORM[code]] += 1f
            }
            for (c in 0 until GRID * GRID) {
                var s = 0f
                for (b in 0 until 59) s += out[c * 59 + b]
                if (s > 0f) for (b in 0 until 59) out[c * 59 + b] /= s
            }
            val desc = ShortArray(out.size) { (out[it] * 10000f).toInt().toShort() }
            return desc to render(160)
        }
    }
}
