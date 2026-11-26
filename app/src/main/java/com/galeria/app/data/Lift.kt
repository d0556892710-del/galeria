package com.galeria.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lifting the subject out of a photo (touch and hold): Google's subject model finds it at once, then
 * BiRefNet (MIT) redraws its outline sharply (hair, fur) in the background. All on the phone.
 */
object Lift {
    /** The lifted subject: [image] is the subject on transparent, placed at [left]…[bottom] (0…1 of the photo). */
    class Cutout(val image: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float, val sharp: Boolean)

    /** What the refinement needs, kept from the quick pass. */
    class Job internal constructor(val photo: Bitmap, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val rough: FloatArray)

    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build()
        )
    }

    /** Asks Google Play services for the subject model ahead of time (it is downloaded once). */
    fun prefetch(context: Context) {
        runCatching {
            com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(context).installModules(
                com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder().addApi(segmenter).build()
            )
        }
    }

    /**
     * The subject under ([nx], [ny]) (0…1 of [photo]), or null if nothing is there. [photo] is a software
     * bitmap, ideally about 2000 px long. Background thread.
     */
    fun quick(context: Context, photo: Bitmap, nx: Float, ny: Float): Pair<Cutout, Job>? {
        val w = photo.width; val h = photo.height
        // The model looks at a ≈1024 px copy; its mask is then lifted to the photo and snapped to its edges.
        val k = min(1f, 1024f / max(w, h))
        val sw = max(1, (w * k).roundToInt()); val sh = max(1, (h * k).roundToInt())
        val small = if (k < 1f) Bitmap.createScaledBitmap(photo, sw, sh, true) else photo
        val picked = runCatching { pick(small, nx, ny) }.onFailure { android.util.Log.w("Photos", "lift model", it) }
        if (small !== photo) small.recycle()
        // Nothing under the finger: nothing to lift. Google's model missing (no Play services): BiRefNet alone.
        val mask = if (picked.isSuccess) picked.getOrNull() ?: return null else return birefnetWhole(context, photo, nx, ny)
        // The subject's box in photo pixels, with a margin for the soft edge.
        val (mx0, my0, mx1, my1) = mask.box
        val pad = max(4, (max(w, h) * .01f).roundToInt())
        val x0 = max(0, (mx0 / k).toInt() - pad); val y0 = max(0, (my0 / k).toInt() - pad)
        val x1 = min(w, ((mx1 + 1) / k).toInt() + pad); val y1 = min(h, ((my1 + 1) / k).toInt() + pad)
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 8 || ch < 8) return null
        val rough = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) rough[y * cw + x] = mask.at((x0 + x + .5f) * k, (y0 + y + .5f) * k)
        val px = IntArray(cw * ch).also { photo.getPixels(it, 0, cw, x0, y0, cw, ch) }
        val grey = FloatArray(cw * ch) { val c = px[it]; (((c shr 16) and 0xFF) * .299f + ((c shr 8) and 0xFF) * .587f + (c and 0xFF) * .114f) / 255f }
        val alpha = Spatial.guided(grey, rough, cw, ch, max(2, max(cw, ch) / 200), 1e-3f)
        val cut = cutout(px, alpha, cw, ch, x0, y0, w, h, sharp = false)
        return cut to Job(photo, x0, y0, x1, y1, rough)
    }

    /** The same subject with BiRefNet's sharper outline, or null if it can't. Background thread. */
    fun refine(context: Context, j: Job): Cutout? = runCatching {
        if (!BiRef.available(context)) return null
        val w = j.photo.width; val h = j.photo.height
        // BiRefNet sees the subject with some room around it.
        val bw = j.x1 - j.x0; val bh = j.y1 - j.y0
        val rx0 = max(0, j.x0 - bw / 8); val ry0 = max(0, j.y0 - bh / 8)
        val rx1 = min(w, j.x1 + bw / 8); val ry1 = min(h, j.y1 + bh / 8)
        val region = Bitmap.createBitmap(j.photo, rx0, ry0, rx1 - rx0, ry1 - ry0)
        val m = BiRef.mask(context, region)
        if (region !== j.photo) region.recycle()
        // Only the chosen subject (the quick mask, a little wider), with BiRefNet's edge.
        val cw = j.x1 - j.x0; val ch = j.y1 - j.y0
        val near = Spatial.box(j.rough, cw, ch, max(3, max(cw, ch) / 40))
        val alpha = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val fx = (j.x0 + x - rx0 + .5f) / (rx1 - rx0); val fy = (j.y0 + y - ry0 + .5f) / (ry1 - ry0)
            val keep = (near[y * cw + x] * 3f).coerceIn(0f, 1f)
            alpha[y * cw + x] = m.at(fx, fy) * keep
        }
        val px = IntArray(cw * ch).also { j.photo.getPixels(it, 0, cw, j.x0, j.y0, cw, ch) }
        cutout(px, alpha, cw, ch, j.x0, j.y0, w, h, sharp = true)
    }.onFailure { android.util.Log.w("Photos", "lift refine", it) }.getOrNull()

    /** Saves the subject as a PNG with transparency (for Copy and Share). */
    fun save(context: Context, c: Cutout): File {
        val dir = File(context.cacheDir, "lift").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "Subject.png")
        f.outputStream().use { c.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    // ------------------------------------------------------------------ helpers

    private class Mask(val w: Int, val h: Int, val v: FloatArray, val box: IntArray) {
        fun at(x: Float, y: Float): Float {
            val fx = (x - .5f).coerceIn(0f, w - 1f); val fy = (y - .5f).coerceIn(0f, h - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val ax = fx - x0; val ay = fy - y0
            val a = v[y0 * w + x0] + (v[y0 * w + x1] - v[y0 * w + x0]) * ax
            val b = v[y1 * w + x0] + (v[y1 * w + x1] - v[y1 * w + x0]) * ax
            return a + (b - a) * ay
        }
    }

    /** Google's subject model: the subject under the finger (or the nearest one). */
    private fun pick(small: Bitmap, nx: Float, ny: Float): Mask? {
        val res = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)), 20, TimeUnit.SECONDS)
        val w = small.width; val h = small.height
        val px = (nx * w).toInt().coerceIn(0, w - 1); val py = (ny * h).toInt().coerceIn(0, h - 1)
        var best: com.google.mlkit.vision.segmentation.subject.Subject? = null; var bestScore = 0f
        for (s in res.subjects) {
            val m = s.confidenceMask ?: continue
            // Under the finger, or close to it (a finger is wide).
            var score = 0f
            val r = max(2, max(w, h) / 40)
            var yy = py - r
            while (yy <= py + r) {
                var xx = px - r
                while (xx <= px + r) {
                    val lx = xx - s.startX; val ly = yy - s.startY
                    if (lx in 0 until s.width && ly in 0 until s.height) score = max(score, m.get(ly * s.width + lx))
                    xx += max(1, r / 3)
                }
                yy += max(1, r / 3)
            }
            if (score > bestScore) { bestScore = score; best = s }
        }
        val s = best?.takeIf { bestScore >= .5f } ?: return null
        val m = s.confidenceMask!!
        val v = FloatArray(w * h)
        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        for (ly in 0 until s.height) for (lx in 0 until s.width) {
            val c = m.get(ly * s.width + lx)
            val x = s.startX + lx; val y = s.startY + ly
            if (x !in 0 until w || y !in 0 until h) continue
            v[y * w + x] = c
            if (c > .3f) { bx0 = min(bx0, x); by0 = min(by0, y); bx1 = max(bx1, x); by1 = max(by1, y) }
        }
        if (bx1 < 0) return null
        return Mask(w, h, v, intArrayOf(bx0, by0, bx1, by1))
    }

    /** Without Google's model (no Play services): BiRefNet on the whole photo, keeping the part under the finger. */
    private fun birefnetWhole(context: Context, photo: Bitmap, nx: Float, ny: Float): Pair<Cutout, Job>? = runCatching {
        if (!BiRef.available(context)) return null
        val m = BiRef.mask(context, photo)
        if (m.at(nx, ny) < .5f) return null
        val w = photo.width; val h = photo.height
        val alpha = FloatArray(w * h); var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val a = m.at((x + .5f) / w, (y + .5f) / h); alpha[y * w + x] = a
            if (a > .3f) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        }
        if (x1 < 0) return null
        x1++; y1++
        val cw = x1 - x0; val ch = y1 - y0
        val crop = FloatArray(cw * ch) { alpha[(y0 + it / cw) * w + x0 + it % cw] }
        val px = IntArray(cw * ch).also { photo.getPixels(it, 0, cw, x0, y0, cw, ch) }
        cutout(px, crop, cw, ch, x0, y0, w, h, sharp = true) to Job(photo, x0, y0, x1, y1, crop)
    }.getOrNull()

    private fun cutout(px: IntArray, alpha: FloatArray, cw: Int, ch: Int, x0: Int, y0: Int, w: Int, h: Int, sharp: Boolean): Cutout {
        val out = IntArray(cw * ch) { i ->
            val a = (((alpha[i] - .5f) * 1.6f + .5f).coerceIn(0f, 1f) * 255f).roundToInt()
            if (a == 0) 0 else (a shl 24) or (px[i] and 0xFFFFFF)
        }
        val b = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, cw, 0, 0, cw, ch) }
        return Cutout(b, x0.toFloat() / w, y0.toFloat() / h, (x0 + cw).toFloat() / w, (y0 + ch).toFloat() / h, sharp)
    }
}

/** BiRefNet lite (MIT): a precise outline of the main subject, 1024 × 1024. */
object BiRef {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private const val NAME = "birefnet_lite.onnx"
    private const val S = 1024

    fun available(context: Context): Boolean = runCatching { context.assets.openFd("models/$NAME").use { true } }.getOrDefault(false)

    private fun file(context: Context): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val f = File(dir, NAME)
        val expected = runCatching { context.assets.openFd("models/$NAME").use { it.length } }.getOrDefault(-1L)
        if (!f.exists() || (expected > 0 && f.length() != expected)) {
            val tmp = File(dir, "$NAME.tmp")
            context.assets.open("models/$NAME").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.renameTo(f)
        }
        return f
    }

    @Synchronized
    private fun session(context: Context): OrtSession = session ?: env.createSession(
        file(context).absolutePath,
        OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(Cpu.fastCores) },
    ).also { session = it }

    @Synchronized
    fun release() { runCatching { session?.close() }; session = null }

    class Mask(private val v: FloatArray) {
        /** Subject (1) or not (0) at a point, 0…1 of the picture given. */
        fun at(x: Float, y: Float): Float {
            val fx = (x * S - .5f).coerceIn(0f, S - 1f); val fy = (y * S - .5f).coerceIn(0f, S - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, S - 1); val y1 = min(y0 + 1, S - 1)
            val ax = fx - x0; val ay = fy - y0
            val a = v[y0 * S + x0] + (v[y0 * S + x1] - v[y0 * S + x0]) * ax
            val b = v[y1 * S + x0] + (v[y1 * S + x1] - v[y1 * S + x0]) * ax
            return a + (b - a) * ay
        }
    }

    @Synchronized
    fun mask(context: Context, bmp: Bitmap): Mask {
        val t0 = System.currentTimeMillis()
        val sq = Bitmap.createScaledBitmap(bmp, S, S, true)
        val n = S * S
        val px = IntArray(n).also { sq.getPixels(it, 0, S, 0, 0, S, S) }
        if (sq !== bmp) sq.recycle()
        val buf = FloatBuffer.allocate(3 * n)
        val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        for (i in 0 until n) {
            val c = px[i]
            buf.put(i, (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0])
            buf.put(n + i, (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1])
            buf.put(2 * n + i, ((c and 0xFF) / 255f - mean[2]) / std[2])
        }
        val s = session(context)
        val v = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(n) { 1f / (1f + exp(-fb.get(it))) }
            }
        }
        android.util.Log.i("PhotosPerf", "birefnet ms=${System.currentTimeMillis() - t0}")
        return Mask(v)
    }
}
