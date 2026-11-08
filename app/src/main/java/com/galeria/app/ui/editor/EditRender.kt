package com.galeria.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The colour pipeline (AGSL, runs on the GPU for both the live preview and the saved photo). */
private const val EDIT_SKSL = """
uniform shader image;
uniform float2 vcenter;
uniform float vradius;
uniform float rad;
uniform float exposure;
uniform float brilliance;
uniform float highlights;
uniform float shadows;
uniform float contrast;
uniform float brightness;
uniform float blackpoint;
uniform float saturation;
uniform float vibrance;
uniform float warmth;
uniform float tint;
uniform float sharpness;
uniform float definition;
uniform float noise;
uniform float vignette;
uniform float filterId;
uniform float filterAmt;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
float3 sat(float3 c, float s) { float l = luma(c); return mix(float3(l), c, s); }

float3 applyFilter(float3 c, float id) {
    float l = luma(c);
    if (id < 3.5) {
        float3 v = sat(c, 1.38);
        v = (v - 0.5) * 1.12 + 0.5;
        if (id > 1.5 && id < 2.5) v *= float3(1.07, 1.0, 0.9);
        if (id > 2.5) v *= float3(0.92, 0.99, 1.08);
        return v;
    }
    if (id < 6.5) {
        float3 d = sat(c, 0.7);
        d = (d - 0.5) * 1.3 + 0.5;
        d -= 0.07 * smoothstep(0.5, 1.0, l);
        if (id > 4.5 && id < 5.5) d *= float3(1.09, 1.0, 0.86);
        if (id > 5.5) d *= float3(0.88, 0.98, 1.12);
        return d;
    }
    if (id < 7.5) { float m = (l - 0.5) * 1.06 + 0.5; return float3(m); }
    if (id < 8.5) { float s = (l - 0.5) * 1.18 + 0.53; return float3(s * 0.99, s, s * 1.025); }
    float n = smoothstep(0.07, 0.93, l);
    n = (n - 0.5) * 1.22 + 0.5;
    return float3(n);
}

half4 main(float2 p) {
    half4 src = image.eval(p);
    float3 c = float3(src.rgb);
    if (sharpness > 0.0 || noise > 0.0 || definition > 0.0) {
        float3 n4 = (float3(image.eval(p + float2(rad, 0.0)).rgb) + float3(image.eval(p - float2(rad, 0.0)).rgb)
                   + float3(image.eval(p + float2(0.0, rad)).rgb) + float3(image.eval(p - float2(0.0, rad)).rgb)) * 0.25;
        c = mix(c, n4, noise * 0.8);
        c += (c - n4) * sharpness * 2.2;
        if (definition > 0.0) {
            float r2 = rad * 7.0;
            float3 m4 = (float3(image.eval(p + float2(r2, r2)).rgb) + float3(image.eval(p - float2(r2, r2)).rgb)
                       + float3(image.eval(p + float2(r2, -r2)).rgb) + float3(image.eval(p + float2(-r2, r2)).rgb)) * 0.25;
            float ld = luma(c);
            c += (c - m4) * definition * 0.85 * (1.0 - abs(ld - 0.5) * 1.2);
        }
    }
    c = clamp(c, 0.0, 1.0);

    float3 lin = c * c * exp2(exposure * 1.6);
    c = sqrt(max(lin, float3(0.0)));

    float sh = shadows + brilliance * 0.55;
    float hi = highlights - brilliance * 0.5;
    float con = contrast + brilliance * 0.08;
    float l = clamp(luma(c), 0.0, 1.0);
    float lo = l;
    float wS = 1.0 - smoothstep(0.0, 0.62, l);
    lo += sh * 0.3 * wS * (0.3 + l);
    float wH = smoothstep(0.38, 1.0, l);
    lo += hi * 0.3 * wH;
    lo = pow(clamp(lo, 0.0, 1.0), exp2(-brightness * 0.75));
    lo = clamp((lo - 0.5) * (1.0 + con * 0.65) + 0.5, 0.0, 1.0);
    float bp = blackpoint * 0.18;
    lo = clamp((lo - bp) / (1.0 - bp), 0.0, 1.0);
    float3 ratio = l > 0.0005 ? c * (lo / l) : float3(lo);
    c = mix(c + (lo - l), ratio, 0.5);
    c = clamp(c, 0.0, 1.0);

    c.r += warmth * 0.09; c.g += warmth * 0.01; c.b -= warmth * 0.11;
    c.g -= tint * 0.07; c.r += tint * 0.035; c.b += tint * 0.035;

    c = sat(c, 1.0 + saturation);
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    c = sat(c, 1.0 + vibrance * (1.0 - clamp(mx - mn, 0.0, 1.0)) * 1.3);
    c = clamp(c, 0.0, 1.0);

    if (filterId > 0.5) c = mix(c, clamp(applyFilter(c, filterId), 0.0, 1.0), filterAmt);

    if (vignette != 0.0) {
        float d = length(p - vcenter) / max(vradius, 1.0);
        float v = smoothstep(0.42, 1.22, d);
        if (vignette > 0.0) { c *= 1.0 - vignette * v * 0.85; }
        else { c = mix(c, float3(1.0), -vignette * v * 0.8); }
    }
    return half4(half3(clamp(c, 0.0, 1.0)), src.a);
}
"""

object EditRender {
    val gpu: Boolean = Build.VERSION.SDK_INT >= 33
    @Volatile private var failed = false

    fun newShader(): Any? = if (gpu && !failed) runCatching { RuntimeShader(EDIT_SKSL) }.onFailure { failed = true }.getOrNull() else null

    // ------------------------------------------------------------------ geometry

    fun baseSize(p: EditParams, w: Float, h: Float): Pair<Float, Float> = if (p.rot % 2 == 1) h to w else w to h

    /** Maps source pixels to the rotated, straightened frame (bw × bh), zoomed so no blank corners show. */
    fun matrix(p: EditParams, w: Float, h: Float): Matrix {
        val (bw, bh) = baseSize(p, w, h)
        val m = Matrix()
        m.postTranslate(-w / 2f, -h / 2f)
        m.postRotate(-90f * p.rot)
        if (p.flip) m.postScale(-1f, 1f)
        if (abs(p.vertical) > .001f || abs(p.horizontal) > .001f) m.postConcat(keystone(p, bw, bh))
        m.postRotate(p.straighten)
        val s = coverScale(m, w, h, bw, bh)
        m.postScale(s, s)
        m.postTranslate(bw / 2f, bh / 2f)
        return m
    }

    private fun keystone(p: EditParams, bw: Float, bh: Float): Matrix {
        val kv = .3f * p.vertical; val kh = .3f * p.horizontal
        val x = bw / 2f; val y = bh / 2f
        val src = floatArrayOf(-x, -y, x, -y, x, y, -x, y)
        val dst = floatArrayOf(
            -x * (1 - kv), -y * (1 - kh),
            x * (1 - kv), -y * (1 + kh),
            x * (1 + kv), y * (1 + kh),
            -x * (1 + kv), y * (1 - kh),
        )
        return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
    }

    private fun coverScale(m: Matrix, w: Float, h: Float, bw: Float, bh: Float): Float {
        val q = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        m.mapPoints(q)
        val pts = floatArrayOf(-bw / 2f, -bh / 2f, bw / 2f, -bh / 2f, bw / 2f, bh / 2f, -bw / 2f, bh / 2f)
        fun inside(s: Float): Boolean {
            for (i in 0 until 4) if (!inQuad(q, pts[i * 2] / s, pts[i * 2 + 1] / s)) return false
            return true
        }
        if (inside(1f)) return 1f
        var lo = 1f; var hi = 8f
        repeat(22) { val mid = (lo + hi) / 2f; if (inside(mid)) hi = mid else lo = mid }
        return hi
    }

    private fun inQuad(q: FloatArray, x: Float, y: Float): Boolean {
        var pos = false; var neg = false
        for (i in 0 until 4) {
            val ax = q[i * 2]; val ay = q[i * 2 + 1]
            val bx = q[(i * 2 + 2) % 8]; val by = q[(i * 2 + 3) % 8]
            val cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            if (cr > .01f) pos = true else if (cr < -.01f) neg = true
            if (pos && neg) return false
        }
        return true
    }

    /** Size of the edited picture in source pixels. */
    fun outputSize(p: EditParams, w: Float, h: Float): Pair<Float, Float> {
        val (bw, bh) = baseSize(p, w, h)
        return max(1f, p.crop.width * bw) to max(1f, p.crop.height * bh)
    }

    // ------------------------------------------------------------------ colour

    fun configure(shader: Any?, p: EditParams, w: Float, h: Float, input: Shader, filterOverride: Filter? = null, colourOnly: Boolean = false) {
        val rs = shader as? RuntimeShader ?: return
        rs.setInputShader("image", input)
        fun u(n: String, v: Float) = runCatching { rs.setFloatUniform(n, v) }
        val m = matrix(p, w, h)
        val inv = Matrix().also { m.invert(it) }
        val (bw, bh) = baseSize(p, w, h)
        val c = floatArrayOf(p.crop.center.x * bw, p.crop.center.y * bh)
        inv.mapPoints(c)
        val half = hypot(p.crop.width * bw, p.crop.height * bh) / 2f
        runCatching { rs.setFloatUniform("vcenter", c[0], c[1]) }
        u("vradius", inv.mapRadius(half))
        u("rad", max(1f, max(w, h) / 1400f))
        u("exposure", p.eff(Tool.Exposure)); u("brilliance", p.eff(Tool.Brilliance))
        u("highlights", p.eff(Tool.Highlights)); u("shadows", p.eff(Tool.Shadows))
        u("contrast", p.eff(Tool.Contrast)); u("brightness", p.eff(Tool.Brightness))
        u("blackpoint", p.eff(Tool.BlackPoint)); u("saturation", p.eff(Tool.Saturation))
        u("vibrance", p.eff(Tool.Vibrance)); u("warmth", p.eff(Tool.Warmth)); u("tint", p.eff(Tool.Tint))
        u("sharpness", if (colourOnly) 0f else p.eff(Tool.Sharpness)); u("definition", if (colourOnly) 0f else p.eff(Tool.Definition))
        u("noise", if (colourOnly) 0f else p.eff(Tool.Noise)); u("vignette", if (colourOnly) 0f else p.eff(Tool.Vignette))
        val f = filterOverride ?: p.filter
        u("filterId", f.ordinal.toFloat()); u("filterAmt", if (filterOverride != null) 1f else p.filterAmount)
    }

    /** Fallback for Android 11–12: a colour matrix that covers the main sliders and filters. */
    fun colorMatrix(p: EditParams, filterOverride: Filter? = null): ColorMatrix {
        val cm = ColorMatrix()
        val e = Math.pow(2.0, p.eff(Tool.Exposure) * .8).toFloat() * (1f + p.eff(Tool.Brightness) * .25f)
        cm.setScale(e, e, e, 1f)
        val con = 1f + (p.eff(Tool.Contrast) + p.eff(Tool.Brilliance) * .1f) * .6f
        val t = (1f - con) * 128f - p.eff(Tool.BlackPoint) * 20f
        cm.postConcat(ColorMatrix(floatArrayOf(con, 0f, 0f, 0f, t, 0f, con, 0f, 0f, t, 0f, 0f, con, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val w = p.eff(Tool.Warmth); val ti = p.eff(Tool.Tint)
        cm.postConcat(ColorMatrix(floatArrayOf(1f + w * .09f + ti * .03f, 0f, 0f, 0f, 0f, 0f, 1f - ti * .07f, 0f, 0f, 0f, 0f, 0f, 1f - w * .11f + ti * .03f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        cm.postConcat(ColorMatrix().apply { setSaturation(1f + p.eff(Tool.Saturation) + p.eff(Tool.Vibrance) * .5f) })
        when (filterOverride ?: p.filter) {
            Filter.Vivid, Filter.VividWarm, Filter.VividCool -> cm.postConcat(ColorMatrix().apply { setSaturation(1.35f) })
            Filter.Dramatic, Filter.DramaticWarm, Filter.DramaticCool -> cm.postConcat(ColorMatrix().apply { setSaturation(.7f) })
            Filter.Mono, Filter.Silvertone, Filter.Noir -> cm.postConcat(ColorMatrix().apply { setSaturation(0f) })
            Filter.Original -> Unit
        }
        return cm
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the edited picture into [canvas] at [scale] (1 = source pixels).
     * [bmp] may be smaller than the source; params are resolution independent.
     */
    fun draw(
        canvas: Canvas, bmp: Bitmap, p: EditParams, shader: Any?, scale: Float, paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
        input: BitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
    ) {
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val (bw, bh) = baseSize(p, w, h)
        if (shader is RuntimeShader) {
            configure(shader, p, w, h, input)
            paint.shader = shader
            paint.colorFilter = null
        } else {
            paint.shader = input
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix(p))
        }
        canvas.save()
        canvas.scale(scale, scale)
        canvas.save()
        canvas.translate(-p.crop.left * bw, -p.crop.top * bh)
        canvas.concat(matrix(p, w, h))
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restore()
        if (p.strokes.isNotEmpty()) {
            val (ow, oh) = outputSize(p, w, h)
            drawStrokes(canvas, p.strokes, ow, oh)
        }
        canvas.restore()
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    /** Markup: smooth pen, translucent marker, fine pencil. */
    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>, ow: Float, oh: Float) {
        for (st in strokes) {
            val pts = st.points
            if (pts.size < 2) continue
            val paint = strokePaint
            paint.color = st.color
            paint.strokeCap = if (st.tool == Stroke.MARKER) Paint.Cap.SQUARE else Paint.Cap.ROUND
            paint.alpha = when (st.tool) { Stroke.MARKER -> 110; Stroke.PENCIL -> 215; else -> 255 }
            paint.strokeWidth = (st.width * ow).coerceAtLeast(1f)
            val path = android.graphics.Path()
            path.moveTo(pts[0] * ow, pts[1] * oh)
            if (pts.size == 2) path.lineTo(pts[0] * ow + .5f, pts[1] * oh + .5f)
            var i = 2
            while (i + 1 < pts.size) {
                val px = pts[i - 2] * ow; val py = pts[i - 1] * oh
                val x = pts[i] * ow; val y = pts[i + 1] * oh
                path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
                i += 2
            }
            path.lineTo(pts[pts.size - 2] * ow, pts[pts.size - 1] * oh)
            canvas.drawPath(path, paint)
        }
    }

    /** Full-resolution render for saving. Runs on the GPU when it can. */
    fun render(src: Bitmap, p: EditParams, maxSide: Int = 8192): Bitmap {
        val (ow, oh) = outputSize(p, src.width.toFloat(), src.height.toFloat())
        val s = min(1f, maxSide / max(ow, oh))
        val outW = max(1, (ow * s).toInt()); val outH = max(1, (oh * s).toInt())
        val shader = newShader()
        val gpuResult = runCatching {
            val pic = Picture()
            val c = pic.beginRecording(outW, outH)
            draw(c, src, p, shader, s)
            pic.endRecording()
            val hw = Bitmap.createBitmap(pic, outW, outH, Bitmap.Config.HARDWARE)
            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
        }.getOrNull()
        if (gpuResult != null) return gpuResult
        val sw = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), sw, p, shader, s)
        return out
    }

    // ------------------------------------------------------------------ auto

    /** What the magic wand does for this photo, from its luminance histogram. */
    fun autoVector(small: Bitmap): List<Float> {
        val b = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        var satSum = 0f
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            hist[((r * 54 + g * 183 + bl * 19) shr 8).coerceIn(0, 255)]++
            satSum += (max(r, max(g, bl)) - min(r, min(g, bl))) / 255f
        }
        val n = px.size.coerceAtLeast(1)
        fun pct(q: Float): Float {
            var acc = 0; val target = (n * q).toInt()
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i / 255f }
            return 1f
        }
        val p02 = pct(.02f); val p50 = pct(.5f); val p98 = pct(.98f)
        val meanSat = satSum / n
        val v = MutableList(Tool.entries.size) { 0f }
        val lin = max(.002f, p50 * p50)
        v[Tool.Exposure.ordinal] = ((ln(.19f / lin) / ln(2f)) / 1.6f * .55f).coerceIn(-.45f, .45f)
        v[Tool.Brilliance.ordinal] = .18f
        v[Tool.Shadows.ordinal] = if (p02 < .06f) .22f else .08f
        v[Tool.Highlights.ordinal] = if (p98 > .95f) -.3f else -.1f
        v[Tool.Contrast.ordinal] = if (p98 - p02 < .65f) .16f else .04f
        v[Tool.BlackPoint.ordinal] = if (p02 > .07f) .12f else .03f
        v[Tool.Vibrance.ordinal] = if (meanSat < .25f) .18f else .08f
        v[Tool.Saturation.ordinal] = .04f
        return v
    }

    /** Fit [w]×[h] into a box, centred. Returns scale and offset. */
    fun fit(w: Float, h: Float, boxW: Float, boxH: Float): Pair<Float, Offset> {
        val s = min(boxW / w, boxH / h)
        return s to Offset((boxW - w * s) / 2f, (boxH - h * s) / 2f)
    }

    @Suppress("unused")
    private fun len(x: Float, y: Float) = sqrt(x * x + y * y)
}
