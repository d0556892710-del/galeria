package com.galeria.app.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portrait on any photo with a clear subject: a lens blur that grows with distance from what is in
 * focus, from the depth model's map (its outline matched to the photo). Drawn by the GPU.
 */
object Portrait {
    /** The aperture dial, as on iPhone (ƒ1.4 blurs most, ƒ16 least). */
    val STOPS = floatArrayOf(1.4f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f, 6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f)
    const val DEFAULT_F = 4.5f

    /** The depth map for a photo (grey, white = near) and the depth that is in focus by default. */
    class Prepared(val depth: Bitmap, val subject: Float) {
        private val w = depth.width; private val h = depth.height
        private val px by lazy { IntArray(w * h).also { depth.getPixels(it, 0, w, 0, 0, w, h) } }
        /** Depth at a point (0…1 of the photo). */
        fun at(x: Float, y: Float): Float {
            val ix = (x * w).toInt().coerceIn(0, w - 1); val iy = (y * h).toInt().coerceIn(0, h - 1)
            return ((px[iy * w + ix] shr 8) and 0xFF) / 255f
        }
    }

    /** Everything Portrait needs for [src] (any size), worked out from scratch (background thread). */
    fun prepareFor(context: android.content.Context, src: Bitmap): Prepared {
        val k = minOf(1f, 1280f / max(src.width, src.height))
        val sw = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val photo = if (k < 1f) downscale(sw, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt())) else sw
        val d = Depth.estimate(context, photo)
        return prepare(photo, d, Spatial.analyse(d)).also { if (photo !== src && photo !== sw) photo.recycle() }
    }

    /** Depth for [photo] (long side about 1280): the subject's outline follows the photo exactly. */
    fun prepare(photo: Bitmap, d: Depth.Map, a: Spatial.Analysis): Prepared {
        val sep = Spatial.separate(photo, d, a)
        return Prepared(Spatial.greyBitmap(sep.depth(), sep.w, sep.h), a.focus)
    }

    // Blur radius ("circle of confusion") from depth: none near the focus, growing to the most at ƒ1.4.
    private const val COC = """
uniform float focus;
uniform float maxR;
uniform float amount;
float coc(float d) { return maxR * amount * clamp((abs(d - focus) - 0.03) / 0.32, 0.0, 1.0); }
"""

    // The blur itself, on a smaller copy: 64 samples over a disc; a sample counts only if its own blur
    // reaches this far (so sharp things don't smear into the blur), and bright ones count more (bokeh).
    private const val BLUR = """
uniform shader image;
uniform shader depth;
$COC
half4 main(float2 p) {
    float r0 = coc(depth.eval(p).r);
    if (r0 < 0.5) return image.eval(p);
    half3 acc = half3(0.0);
    float ws = 0.0;
    for (int i = 0; i < 64; i++) {
        float t = (float(i) + 0.5) / 64.0;
        float a = float(i) * 2.39996323;
        float rr = sqrt(t) * r0;
        float2 q = p + float2(cos(a), sin(a)) * rr;
        float rq = coc(depth.eval(q).r);
        float w = clamp(rq - rr + 1.0, 0.0, 1.0);
        half3 c = image.eval(q).rgb;
        float l = dot(c, half3(0.299, 0.587, 0.114));
        w *= 1.0 + 3.0 * l * l * l * l;
        acc += c * w;
        ws += w;
    }
    if (ws < 0.001) return image.eval(p);
    return half4(acc / ws, 1.0);
}
"""

    // Full size: the sharp photo where in focus, the blur elsewhere, blended smoothly.
    private const val MIX = """
uniform shader sharp;
uniform shader blurred;
uniform shader depth;
$COC
half4 main(float2 p) {
    float k = smoothstep(0.35, 1.25, coc(depth.eval(p).r));
    if (k <= 0.0) return sharp.eval(p);
    return mix(sharp.eval(p), blurred.eval(p), k);
}
"""

    /** Strength for an ƒ-number (ƒ1.4 = 1). */
    fun amount(f: Float): Float = (1.4f / f.coerceIn(1.4f, 16f)).let { (it - 1.4f / 16f) / (1f - 1.4f / 16f) }.coerceIn(0f, 1f)

    /**
     * [src] with the lens blur, at the same size (software bitmap). [focus] is the depth kept sharp.
     */
    @RequiresApi(33)
    fun render(src0: Bitmap, p: Prepared, focus: Float, f: Float): Bitmap {
        val src = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) else src0
        val w = src.width; val h = src.height
        val amt = amount(f)
        if (amt <= .001f) return src
        // The blur is worked out on a copy about 1024 px long (blur needs no more), then laid over.
        val s = minOf(1f, 1024f / max(w, h))
        val sw = max(1, (w * s).roundToInt()); val sh = max(1, (h * s).roundToInt())
        val small = downscale(src, sw, sh)
        val maxR = max(sw, sh) * .03f
        val blur = RuntimeShader(BLUR)
        blur.setInputShader("image", linear(small))
        blur.setInputShader("depth", scaled(p.depth, sw, sh))
        blur.setFloatUniform("focus", focus); blur.setFloatUniform("maxR", maxR); blur.setFloatUniform("amount", amt)
        val blurred = draw(sw, sh, blur)
        if (small !== src) small.recycle()
        val mix = RuntimeShader(MIX)
        mix.setInputShader("sharp", linear(src))
        mix.setInputShader("blurred", scaled(blurred, w, h))
        mix.setInputShader("depth", scaled(p.depth, w, h))
        mix.setFloatUniform("focus", focus); mix.setFloatUniform("maxR", maxR); mix.setFloatUniform("amount", amt)
        val out = draw(w, h, mix)
        blurred.recycle()
        return out
    }

    /** A bitmap as a shader stretched to [w]×[h] (smoothly). */
    @RequiresApi(33)
    private fun scaled(b: Bitmap, w: Int, h: Int): Shader = linear(b).apply {
        setLocalMatrix(Matrix().apply { setScale(w.toFloat() / b.width, h.toFloat() / b.height) })
    }

    @RequiresApi(33)
    fun linear(b: Bitmap): BitmapShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

    /** Runs [shader] over a [w]×[h] picture on the GPU, returned as a software bitmap. */
    private fun draw(w: Int, h: Int, shader: Shader): Bitmap {
        val pic = Picture()
        val c = pic.beginRecording(w, h)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        pic.endRecording()
        val hw = Bitmap.createBitmap(pic, w, h, Bitmap.Config.HARDWARE)
        return hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
    }

    /** Smaller copy without aliasing: halves first, then the last step. */
    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            val next = Bitmap.createBitmap(cur.width / 2, cur.height / 2, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, Matrix().apply { setScale(.5f, .5f) }, Paint(Paint.FILTER_BITMAP_FLAG))
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == w && cur.height == h) return cur
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && out !== cur) cur.recycle()
        return out
    }
}
