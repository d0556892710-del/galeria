package com.galeria.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * LaMa ("Resolution-robust Large Mask Inpainting", Samsung AI Center; Apache-2.0), run on the phone
 * with ONNX Runtime. Two sizes of the same model: 512 px for small things (fast) and 1024 px for big
 * ones (sharper). Nothing leaves the phone.
 */
object LaMa {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<Int, OrtSession>()

    /** Model sizes that are bundled with this build. */
    fun available(context: Context): List<Int> = listOf(512, 1024).filter { s -> Models.ready(context, "lama_$s.onnx") }

    /** The model file on disk (downloaded once, or copied out of the app). */
    private fun file(context: Context, size: Int): File = Models.file(context, "lama_$size.onnx") ?: error("lama_$size not downloaded yet")

    // Sessions in use are never closed under a running fill: a release waits for the last user.
    private var users = 0
    private var releaseWanted = false

    @Synchronized
    private fun acquire(context: Context, size: Int): OrtSession { users++; releaseWanted = false; return try { session(context, size) } catch (e: Throwable) { users--; throw e } }

    @Synchronized
    private fun done() { users--; if (users == 0 && releaseWanted) closeAll() }

    @Synchronized
    private fun session(context: Context, size: Int): OrtSession = sessions.getOrPut(size) {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Cpu.fastCores)
        }
        env.createSession(file(context, size).absolutePath, opts)
    }

    /** Frees the models' memory (when leaving the editor). */
    @Synchronized
    fun release() { if (users > 0) releaseWanted = true else closeAll() }

    private fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear(); releaseWanted = false
    }

    /**
     * Fills [hole] (true = remove) in a [size]×[size] picture. [img] must be size×size, software ARGB.
     * Returns the model's picture (same size).
     */
    fun inpaint(context: Context, size: Int, img: Bitmap, hole: BooleanArray, timeoutMs: Long = 0): Bitmap {
        val n = size * size
        val px = IntArray(n).also { img.getPixels(it, 0, size, 0, 0, size, size) }
        val image = FloatBuffer.allocate(3 * n)
        val mask = FloatBuffer.allocate(n)
        for (i in 0 until n) {
            val c = px[i]
            image.put(i, ((c shr 16) and 0xFF) / 255f)
            image.put(n + i, ((c shr 8) and 0xFF) / 255f)
            image.put(2 * n + i, (c and 0xFF) / 255f)
            mask.put(i, if (hole[i]) 1f else 0f)
        }
        val s = acquire(context, size)
        try { return run(s, size, image, mask, n, timeoutMs) } finally { done() }
    }

    private fun run(s: OrtSession, size: Int, image: FloatBuffer, mask: FloatBuffer, n: Int, timeoutMs: Long): Bitmap {
        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        val out = IntArray(n)
        OnnxTensor.createTensor(env, image, shape).use { ti ->
            OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, size.toLong(), size.toLong())).use { tm ->
                OrtSession.RunOptions().use { ro ->
                // A watchdog: past the time limit the model is stopped (the caller then falls back).
                val watchdog = if (timeoutMs > 0) Thread {
                    try { Thread.sleep(timeoutMs); ro.setTerminate(true) } catch (_: InterruptedException) {}
                }.apply { isDaemon = true; start() } else null
                val result = try { s.run(mapOf("image" to ti, "mask" to tm), ro) } finally { watchdog?.interrupt() }
                result.use { res ->
                    val fb = (res.get(0) as OnnxTensor).floatBuffer
                    // Output is 0…1 (or 0…255 in some exports).
                    var mx = 0f
                    for (i in 0 until minOf(fb.capacity(), 4096)) mx = maxOf(mx, fb.get(i))
                    val k = if (mx > 2f) 1f else 255f
                    for (i in 0 until n) {
                        val r = (fb.get(i) * k).toInt().coerceIn(0, 255)
                        val g = (fb.get(n + i) * k).toInt().coerceIn(0, 255)
                        val b = (fb.get(2 * n + i) * k).toInt().coerceIn(0, 255)
                        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                }
            }
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }
}
