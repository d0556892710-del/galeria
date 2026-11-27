package com.galeria.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Segment Anything 2.1 (Meta, Apache-2.0), on the phone: finds the exact outline of the thing you
 * circled or tapped, so Clean Up removes just that object and nothing around it.
 */
object Sam {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var decoder: OrtSession? = null
    private val MEAN = floatArrayOf(.485f, .456f, .406f)
    private val STD = floatArrayOf(.229f, .224f, .225f)

    /** What the model understood about one picture (computed once, then each selection is instant). */
    class Embedding(val embed: FloatBuffer, val hr0: FloatBuffer, val hr1: FloatBuffer, val width: Int, val height: Int)

    fun available(context: Context): Boolean = Models.ready(context, "sam_encoder.onnx") && Models.ready(context, "sam_decoder.onnx")

    private fun file(context: Context, name: String): File = Models.file(context, name) ?: error("$name not downloaded yet")

    private fun options() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Cpu.fastCores)
    }

    /** Looks at the whole picture (a few seconds; done in the background when Clean Up opens). */
    fun embed(context: Context, bmp: Bitmap): Embedding {
        val s = 1024
        val small = Bitmap.createScaledBitmap(if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp, s, s, true)
        val px = IntArray(s * s).also { small.getPixels(it, 0, s, 0, 0, s, s) }
        if (small !== bmp) small.recycle()
        val n = s * s
        val input = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) {
            val c = px[i]
            input.put(i, (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
            input.put(n + i, (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1])
            input.put(2 * n + i, ((c and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        // The encoder is big: load it for this picture only, then free it.
        env.createSession(file(context, "sam_encoder.onnx").absolutePath, options()).use { enc ->
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, s.toLong(), s.toLong())).use { t ->
                enc.run(mapOf("image" to t)).use { r ->
                    fun copy(name: String): FloatBuffer {
                        val src = (r.get(name).get() as OnnxTensor).floatBuffer
                        val dst = ByteBuffer.allocateDirect(src.remaining() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        dst.put(src); dst.rewind(); return dst
                    }
                    return Embedding(copy("image_embed"), copy("high_res_feats_0"), copy("high_res_feats_1"), bmp.width, bmp.height)
                }
            }
        }
    }

    @Synchronized
    private fun decoder(context: Context): OrtSession =
        decoder ?: env.createSession(file(context, "sam_decoder.onnx").absolutePath, options()).also { decoder = it }

    @Synchronized
    fun release() { runCatching { decoder?.close() }; decoder = null }

    /**
     * The object inside [box] (l, t, r, b) or under [point] (x, y), in the picture's pixels.
     * Returns a mask (opaque = the object; the picture scaled so its long side is at most 1024) or null.
     */
    fun select(context: Context, e: Embedding, box: FloatArray? = null, point: FloatArray? = null): Bitmap? {
        val sx = 1024f / e.width; val sy = 1024f / e.height
        val coords = ArrayList<Float>(); val labels = ArrayList<Float>()
        if (box != null) {
            coords += listOf(box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy); labels += listOf(2f, 3f)
        }
        if (point != null) { coords += listOf(point[0] * sx, point[1] * sy); labels += 1f }
        if (labels.isEmpty()) return null
        val np = labels.size
        val dec = decoder(context)
        val tensors = listOf(
            "image_embed" to OnnxTensor.createTensor(env, e.embed.duplicate().also { it.rewind() }, longArrayOf(1, 256, 64, 64)),
            "high_res_feats_0" to OnnxTensor.createTensor(env, e.hr0.duplicate().also { it.rewind() }, longArrayOf(1, 32, 256, 256)),
            "high_res_feats_1" to OnnxTensor.createTensor(env, e.hr1.duplicate().also { it.rewind() }, longArrayOf(1, 64, 128, 128)),
            "point_coords" to OnnxTensor.createTensor(env, FloatBuffer.wrap(coords.toFloatArray()), longArrayOf(1, np.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, FloatBuffer.wrap(labels.toFloatArray()), longArrayOf(1, np.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
        )
        try {
            dec.run(tensors.toMap()).use { r ->
                val masks = (r.get("masks").get() as OnnxTensor)
                val iou = (r.get("iou_predictions").get() as OnnxTensor).floatBuffer
                val shape = masks.info.shape // [1, k, mh, mw]
                val k = shape[1].toInt(); val mh = shape[2].toInt(); val mw = shape[3].toInt()
                var best = 0
                for (i in 1 until k) if (iou.get(i) > iou.get(best)) best = i
                val fb = masks.floatBuffer
                val off = best * mh * mw
                // Up-sample the logits (bilinear) to the picture's shape and keep what's > 0.
                val scale = min(1f, 1024f / max(e.width, e.height))
                val ow = max(1, (e.width * scale).roundToInt()); val oh = max(1, (e.height * scale).roundToInt())
                val out = IntArray(ow * oh)
                var any = false
                for (y in 0 until oh) {
                    val fy = ((y + .5f) * mh / oh - .5f).coerceIn(0f, mh - 1f)
                    val y0 = fy.toInt(); val y1 = min(mh - 1, y0 + 1); val wy = fy - y0
                    for (x in 0 until ow) {
                        val fx = ((x + .5f) * mw / ow - .5f).coerceIn(0f, mw - 1f)
                        val x0 = fx.toInt(); val x1 = min(mw - 1, x0 + 1); val wx = fx - x0
                        val v = (fb.get(off + y0 * mw + x0) * (1 - wx) + fb.get(off + y0 * mw + x1) * wx) * (1 - wy) +
                            (fb.get(off + y1 * mw + x0) * (1 - wx) + fb.get(off + y1 * mw + x1) * wx) * wy
                        if (v > 0f) { out[y * ow + x] = -0x1000000; any = true }
                    }
                }
                if (!any) return null
                return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
            }
        } finally {
            tensors.forEach { runCatching { it.second.close() } }
        }
    }
}

/** The phone's fast cores (AI models run only on those: the small cores would slow everything down). */
object Cpu {
    val fastCores: Int by lazy {
        runCatching {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }
            val top = freqs.maxOrNull() ?: return@runCatching 4
            freqs.count { it >= top * 0.72 }.coerceIn(2, 8)
        }.getOrDefault(4)
    }
}
