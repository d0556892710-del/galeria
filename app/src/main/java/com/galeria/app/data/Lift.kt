package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lifting the subject out of a photo (touch and hold): Google's subject model finds it, and its outline
 * is snapped to the photo's own edges. Without Google Play services, the selection model of Clean Up
 * (SAM 2.1) picks the object under the finger instead. All on the phone.
 */
object Lift {
    /** The lifted subject: [image] is the subject on transparent, placed at [left]…[bottom] (0…1 of the photo). */
    class Cutout(val image: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build()
        )
    }

    private fun googleReady(context: Context): Boolean = runCatching {
        Tasks.await(com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(context).areModulesAvailable(segmenter), 4, TimeUnit.SECONDS).areModulesAvailable()
    }.getOrDefault(false)

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
    fun subject(context: Context, photo: Bitmap, nx: Float, ny: Float): Cutout? {
        val w = photo.width; val h = photo.height
        // The model looks at a ≈1024 px copy; its mask is then lifted to the photo and snapped to its edges.
        val k = min(1f, 1024f / max(w, h))
        val sw = max(1, (w * k).roundToInt()); val sh = max(1, (h * k).roundToInt())
        val small = if (k < 1f) Bitmap.createScaledBitmap(photo, sw, sh, true) else photo
        val t0 = System.currentTimeMillis()
        // Google's model only once Play services have it (else SAM answers at once, while it downloads).
        val picked = if (googleReady(context)) runCatching { pick(small, nx, ny) }.onFailure { android.util.Log.w("Photos", "lift model", it) }
        else Result.failure(IllegalStateException("subject model not downloaded yet"))
        // Nothing under the finger: nothing to lift. Google's model missing (no Play services): SAM.
        val mask = if (picked.isSuccess) picked.getOrNull() else sam(context, small, nx, ny)
        android.util.Log.i("PhotosPerf", "lift via=${if (picked.isSuccess) "google" else "sam"} found=${mask != null} ms=${System.currentTimeMillis() - t0}")
        if (small !== photo) small.recycle()
        mask ?: return null
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
        return cutout(px, alpha, cw, ch, x0, y0, w, h)
    }

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

    /** Without Google's model: SAM picks the object under the finger. */
    private fun sam(context: Context, small: Bitmap, nx: Float, ny: Float): Mask? {
        if (!Sam.available(context)) return null
        return runCatching { samMask(context, small, nx, ny) }.onFailure { android.util.Log.w("Photos", "lift sam", it) }.getOrNull().also { Sam.release() }
    }

    private fun samMask(context: Context, small: Bitmap, nx: Float, ny: Float): Mask? {
        val e = Sam.embed(context, small)
        val m = Sam.select(context, e, point = floatArrayOf(nx * small.width, ny * small.height)) ?: return null
        val w = m.width; val h = m.height
        val px = IntArray(w * h).also { m.getPixels(it, 0, w, 0, 0, w, h) }
        m.recycle()
        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        val v = FloatArray(w * h) { i ->
            val on = (px[i] ushr 24) > 127
            if (on) { val x = i % w; val y = i / w; bx0 = min(bx0, x); by0 = min(by0, y); bx1 = max(bx1, x); by1 = max(by1, y) }
            if (on) 1f else 0f
        }
        if (bx1 < 0) return null
        // The mask may be smaller than the picture it came from: scale its box and values to [small].
        val sx = small.width.toFloat() / w; val sy = small.height.toFloat() / h
        val full = FloatArray(small.width * small.height)
        val mm = Mask(w, h, v, intArrayOf(bx0, by0, bx1, by1))
        for (y in 0 until small.height) for (x in 0 until small.width) full[y * small.width + x] = mm.at((x + .5f) / sx, (y + .5f) / sy)
        return Mask(small.width, small.height, full, intArrayOf((bx0 * sx).toInt(), (by0 * sy).toInt(), min(small.width - 1, ((bx1 + 1) * sx).toInt()), min(small.height - 1, ((by1 + 1) * sy).toInt())))
    }

    private fun cutout(px: IntArray, alpha: FloatArray, cw: Int, ch: Int, x0: Int, y0: Int, w: Int, h: Int): Cutout {
        val out = IntArray(cw * ch) { i ->
            val a = (((alpha[i] - .5f) * 1.6f + .5f).coerceIn(0f, 1f) * 255f).roundToInt()
            if (a == 0) 0 else (a shl 24) or (px[i] and 0xFFFFFF)
        }
        val b = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, cw, 0, 0, cw, ch) }
        return Cutout(b, x0.toFloat() / w, y0.toFloat() / h, (x0 + cw).toFloat() / w, (y0 + ch).toFloat() / h)
    }
}
