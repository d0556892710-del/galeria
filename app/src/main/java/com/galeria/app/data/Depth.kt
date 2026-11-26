package com.galeria.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Depth Anything V2 (small; Apache-2.0) on the phone: how near or far each part of a photo is, from a
 * single ordinary photo. Powers Portrait on any photo and Spatial Scenes. Nothing leaves the phone.
 */
object Depth {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private const val NAME = "depth_small.onnx"

    /** A depth map: [w]×[h] values 0…1, 1 = nearest. */
    class Map(val w: Int, val h: Int, val v: FloatArray) {
        /** Nearness at a point given in 0…1 photo coordinates (bilinear). */
        fun at(x: Float, y: Float): Float {
            val fx = (x * w - .5f).coerceIn(0f, w - 1f); val fy = (y * h - .5f).coerceIn(0f, h - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
            val ax = fx - x0; val ay = fy - y0
            val a = v[y0 * w + x0] + (v[y0 * w + x1] - v[y0 * w + x0]) * ax
            val b = v[y1 * w + x0] + (v[y1 * w + x1] - v[y1 * w + x0]) * ax
            return a + (b - a) * ay
        }

        /** As an 8-bit grey picture (for caching and for the GPU). */
        fun toBitmap(): Bitmap {
            val px = IntArray(w * h) { val g = (v[it] * 255f).roundToInt().coerceIn(0, 255); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        companion object {
            fun fromBitmap(b: Bitmap): Map {
                val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                return Map(b.width, b.height, FloatArray(px.size) { ((px[it] shr 8) and 0xFF) / 255f })
            }
        }
    }

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

    /**
     * The depth of [bmp] (any size). The model sees the photo with its long side at [side] px (a multiple
     * of 14, aspect kept); the result has that size too.
     */
    @Synchronized
    fun estimate(context: Context, bmp: Bitmap, side: Int = 518): Map {
        val k = side.toFloat() / max(bmp.width, bmp.height)
        val w = max(14, ((bmp.width * k) / 14f).roundToInt() * 14); val h = max(14, ((bmp.height * k) / 14f).roundToInt() * 14)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val n = w * h
        val px = IntArray(n).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        if (scaled !== bmp) scaled.recycle()
        val buf = FloatBuffer.allocate(3 * n)
        val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        for (i in 0 until n) {
            val c = px[i]
            buf.put(i, (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0])
            buf.put(n + i, (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1])
            buf.put(2 * n + i, ((c and 0xFF) / 255f - mean[2]) / std[2])
        }
        val s = session(context)
        val raw = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(minOf(n, fb.capacity())) { fb.get(it) }
            }
        }
        return Map(w, h, normalise(raw))
    }

    /** Relative depth → 0…1 (robust to a few extreme pixels). */
    private fun normalise(d: FloatArray): FloatArray {
        val sorted = d.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * .01f).toInt()]; val hi = sorted[((sorted.size - 1) * .995f).toInt()]
        val span = max(1e-6f, hi - lo)
        return FloatArray(d.size) { ((d[it] - lo) / span).coerceIn(0f, 1f) }
    }
}
