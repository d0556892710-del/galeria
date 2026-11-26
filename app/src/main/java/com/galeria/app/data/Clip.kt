package com.galeria.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.JsonReader
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MobileCLIP2 (Apple) on the phone: turns photos and words into points in the same space, so a search
 * like "dog on the beach at sunset" finds the photos that look like it. Nothing leaves the phone.
 */
object Clip {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var vision: OrtSession? = null
    private var text: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null
    const val DIM = 512

    fun available(context: Context): Boolean = runCatching {
        context.assets.openFd("models/clip_vision.onnx").use { true } && context.assets.openFd("models/clip_text.onnx").use { true } &&
            context.assets.open("models/clip_tokenizer.json").use { true }
    }.getOrDefault(false)

    private fun file(context: Context, name: String): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val f = File(dir, name)
        val expected = runCatching { context.assets.openFd("models/$name").use { it.length } }.getOrDefault(-1L)
        if (!f.exists() || (expected > 0 && f.length() != expected)) {
            val tmp = File(dir, "$name.tmp")
            context.assets.open("models/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.renameTo(f)
        }
        return f
    }

    @Synchronized
    private fun vision(context: Context): OrtSession = vision ?: env.createSession(
        file(context, "clip_vision.onnx").absolutePath,
        // Indexing runs in the background: two threads keep the phone responsive.
        OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(2) },
    ).also { vision = it }

    @Synchronized
    private fun text(context: Context): OrtSession = text ?: env.createSession(
        file(context, "clip_text.onnx").absolutePath,
        OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(Cpu.fastCores) },
    ).also { text = it }

    @Synchronized
    private fun tokenizer(context: Context): ClipTokenizer =
        tokenizer ?: context.assets.open("models/clip_tokenizer.json").use { ClipTokenizer.load(it) }.also { tokenizer = it }

    /** A photo's point (unit length). [bmp] is any size; it is resized so the short side is 256 and centre-cropped. */
    fun image(context: Context, bmp: Bitmap): FloatArray {
        val s = 256
        val k = s.toFloat() / minOf(bmp.width, bmp.height)
        val w = max(s, (bmp.width * k).roundToInt()); val h = max(s, (bmp.height * k).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(s * s).also { scaled.getPixels(it, 0, s, (w - s) / 2, (h - s) / 2, s, s) }
        if (scaled !== bmp) scaled.recycle()
        val n = s * s
        val buf = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) {
            val c = px[i]
            buf.put(i, ((c shr 16) and 0xFF) / 255f); buf.put(n + i, ((c shr 8) and 0xFF) / 255f); buf.put(2 * n + i, (c and 0xFF) / 255f)
        }
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, s.toLong(), s.toLong())).use { t ->
            vision(context).run(mapOf("pixel_values" to t)).use { r -> return unit((r.get(0) as OnnxTensor).floatBuffer) }
        }
    }

    /** Words' point (unit length), in English. */
    fun text(context: Context, query: String): FloatArray {
        val ids = tokenizer(context).encode(query)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
            text(context).run(mapOf("input_ids" to t)).use { r -> return unit((r.get(0) as OnnxTensor).floatBuffer) }
        }
    }

    private fun unit(fb: FloatBuffer): FloatArray {
        val v = FloatArray(DIM) { fb.get(it) }
        var n = 0f; for (x in v) n += x * x
        val inv = 1f / sqrt(max(n, 1e-12f))
        for (i in v.indices) v[i] *= inv
        return v
    }

    @Synchronized
    fun releaseVision() { runCatching { vision?.close() }; vision = null }
}

/** OpenAI CLIP's byte-level BPE tokenizer (lower-case, 77 tokens, start/end markers). */
class ClipTokenizer private constructor(private val vocab: HashMap<String, Int>, private val ranks: HashMap<String, Int>) {
    companion object {
        private const val SOT = 49406
        private const val EOT = 49407
        private val PAT = Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")
        private val BYTE: Array<String> = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs); var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val out = arrayOfNulls<String>(256)
            for (i in bs.indices) out[bs[i]] = String(Character.toChars(cs[i]))
            Array(256) { out[it]!! }
        }

        fun load(input: java.io.InputStream): ClipTokenizer {
            val vocab = HashMap<String, Int>(65536); val ranks = HashMap<String, Int>(65536)
            JsonReader(input.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "model") { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "vocab" -> { r.beginObject(); while (r.hasNext()) { val k = r.nextName(); vocab[k] = r.nextInt() }; r.endObject() }
                        "merges" -> {
                            r.beginArray(); var i = 0
                            while (r.hasNext()) {
                                // Merges come as "a b" strings (or [a, b] pairs in newer files).
                                val m = if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                    r.beginArray(); val a = r.nextString(); val b = r.nextString(); r.endArray(); "$a $b"
                                } else r.nextString()
                                ranks[m] = i++
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                    r.endObject()
                }
                r.endObject()
            }
            return ClipTokenizer(vocab, ranks)
        }
    }

    private fun bpe(word: String): List<String> {
        val cps = word.codePoints().toArray().map { String(Character.toChars(it)) }.toMutableList()
        if (cps.isEmpty()) return emptyList()
        cps[cps.lastIndex] = cps.last() + "</w>"
        var w = cps
        while (w.size > 1) {
            var best = Int.MAX_VALUE; var at = -1
            for (i in 0 until w.size - 1) {
                val r = ranks["${w[i]} ${w[i + 1]}"] ?: continue
                if (r < best) { best = r; at = i }
            }
            if (at < 0) break
            w = (w.subList(0, at) + (w[at] + w[at + 1]) + w.subList(at + 2, w.size)).toMutableList()
        }
        return w
    }

    fun encode(text: String, length: Int = 77): LongArray {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Int>(); ids += SOT
        for (m in PAT.findAll(clean)) {
            val u = m.value.toByteArray(Charsets.UTF_8).joinToString("") { BYTE[it.toInt() and 0xFF] }
            for (tok in bpe(u)) ids += vocab[tok] ?: EOT
        }
        val body = ids.take(length - 1).toMutableList(); body += EOT
        return LongArray(length) { if (it < body.size) body[it].toLong() else 0L }
    }
}
