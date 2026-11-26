package com.galeria.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import com.galeria.app.data.Inpaint
import com.galeria.app.data.LaMa
import com.galeria.app.data.Sam
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** iPhone Clean Up: brush over something and it disappears, filled from its surroundings. */
object CleanUp {
    /** Brush width as a fraction of the picture's width. */
    const val BRUSH = .045f

    /** The original with all Clean Up patches laid over it (in place when the bitmap is mutable). */
    fun apply(src: Bitmap, patches: List<CleanPatch>): Bitmap {
        if (patches.isEmpty()) return src
        val out = if (src.isMutable && src.config != Bitmap.Config.HARDWARE) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (p in patches) {
            val b = runCatching { BitmapFactory.decodeFile(p.file) }.getOrNull() ?: continue
            c.drawBitmap(b, null, RectF(p.left * out.width, p.top * out.height, p.right * out.width, p.bottom * out.height), paint)
            b.recycle()
        }
        return out
    }

    /** What a Clean Up gesture points at, in the picture's own pixels. */
    class Target(
        /** The object found by the selection model (opaque = object), drawn over the whole picture; or null. */
        val objMask: Bitmap?,
        val path: android.graphics.Path, val inv: Matrix, val ow: Float, val brushPx: Float, val lasso: Boolean,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    ) {
        val side get() = max(maxX - minX, maxY - minY)
    }

    /**
     * Works out what to remove: tapping or circling picks the whole object (and only it) with the
     * selection model, like iPhone; brushing removes exactly what was brushed.
     */
    fun target(context: Context, src: Bitmap, p: EditParams, points: List<Float>, brush: Float, sam: Sam.Embedding?): Target {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val (bw, bh) = EditRender.baseSize(p, w, h)
        val (ow, oh) = EditRender.outputSize(p, w, h)
        val full = Matrix(EditRender.matrix(p, w, h)).apply { postTranslate(-p.crop.left * bw, -p.crop.top * bh) }
        val inv = Matrix().also { full.invert(it) }
        val path = android.graphics.Path()
        path.moveTo(points[0] * ow, points[1] * oh)
        var i = 2
        while (i + 1 < points.size) { path.lineTo(points[i] * ow, points[i + 1] * oh); i += 2 }
        if (points.size == 2) path.lineTo(points[0] * ow + 1f, points[1] * oh + 1f)
        val brushPx = brush * ow
        val lasso = isLoop(points, ow, oh, brushPx)
        val pts = FloatArray(points.size) { k -> if (k % 2 == 0) points[k] * ow else points[k] * oh }
        inv.mapPoints(pts)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in pts.indices step 2) { minX = min(minX, pts[k]); maxX = max(maxX, pts[k]); minY = min(minY, pts[k + 1]); maxY = max(maxY, pts[k + 1]) }
        val rad = inv.mapRadius(brushPx / 2f) + 2f
        val tap = max(maxX - minX, maxY - minY) < rad * 1.2f
        // The selection model: the object in the circle, or under the finger.
        val obj = if (sam != null && (lasso || tap)) runCatching {
            if (lasso) Sam.select(context, sam, box = floatArrayOf(minX, minY, maxX, maxY))
            else Sam.select(context, sam, point = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f))
        }.onFailure { android.util.Log.w("Photos", "selection", it) }.getOrNull() else null
        if (obj != null) {
            // Bounds of the object, in the picture's pixels.
            val ow2 = obj.width; val oh2 = obj.height
            val a = IntArray(ow2 * oh2).also { obj.getPixels(it, 0, ow2, 0, 0, ow2, oh2) }
            var l = ow2; var t = oh2; var r = -1; var b = -1
            for (y in 0 until oh2) for (x in 0 until ow2) if ((a[y * ow2 + x] ushr 24) > 127) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
            if (r >= l && b >= t) {
                val fx = w / ow2; val fy = h / oh2
                return Target(obj, path, inv, ow, brushPx, lasso, l * fx - rad, t * fy - rad, (r + 1) * fx + rad, (b + 1) * fy + rad)
            }
        }
        return Target(null, path, inv, ow, brushPx, lasso, minX - rad, minY - rad, maxX + rad, maxY + rad)
    }

    /** Is a sharper second pass worth it (big object, and the big model is there)? */
    fun wantsRefine(context: Context, t: Target): Boolean = t.side * 1.6f + 64f > 640f && 1024 in LaMa.available(context)

    /**
     * Fills in [t] with LaMa: [model] 512 (fast) or 1024 (sharp, for big objects). Writes the patch to
     * [into] (or a new file). Falls back to the classic fill when the model can't run.
     */
    fun fill(context: Context, src: Bitmap, t: Target, model: Int = 512, into: File? = null, timeoutMs: Long = 0): CleanPatch? = runCatching {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val sizes = LaMa.available(context)
        if (model !in sizes) return@runCatching removeClassic(context, src, t)

        // The area around the object the model sees (it needs the surroundings), at most `model` px.
        val want = t.side * 1.6f + 64f
        val side = max(want, model.toFloat()).coerceAtMost(max(w, h)).roundToInt()
        val cx = (t.minX + t.maxX) / 2f; val cy = (t.minY + t.maxY) / 2f
        val cw = min(side, src.width); val ch = min(side, src.height)
        val rl = (cx - cw / 2f).roundToInt().coerceIn(0, src.width - cw)
        val rt = (cy - ch / 2f).roundToInt().coerceIn(0, src.height - ch)
        val sq = max(cw, ch)

        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val region = Bitmap.createBitmap(soft, rl, rt, cw, ch)
        val square = Bitmap.createBitmap(sq, sq, Bitmap.Config.ARGB_8888)
        Canvas(square).drawRect(0f, 0f, sq.toFloat(), sq.toFloat(), Paint().apply {
            shader = android.graphics.BitmapShader(region, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        })
        val input = Bitmap.createScaledBitmap(square, model, model, true)

        // What to remove, at the model's size.
        val maskBmp = Bitmap.createBitmap(model, model, Bitmap.Config.ALPHA_8)
        val k = model.toFloat() / sq
        Canvas(maskBmp).apply {
            scale(k, k); translate(-rl.toFloat(), -rt.toFloat())
            if (t.objMask != null) {
                drawBitmap(t.objMask, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                concat(t.inv)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                if (t.lasso) drawPath(android.graphics.Path(t.path).apply { close() }, paint.apply { style = Paint.Style.FILL })
                drawPath(t.path, paint.apply { style = Paint.Style.STROKE; strokeWidth = t.brushPx })
            }
        }
        val mp = IntArray(model * model).also { maskBmp.getPixels(it, 0, model, 0, 0, model, model) }
        var hole = BooleanArray(model * model) { (mp[it] ushr 24) > 24 }
        // A little extra around the edges, so no outline or shadow is left behind.
        repeat(max(2, (model * (if (t.objMask != null) .012f else .008f)).roundToInt())) { hole = dilate(hole, model, model) }
        if (hole.none { it }) return@runCatching null

        val t0 = android.os.SystemClock.elapsedRealtime()
        val filled = try {
            LaMa.inpaint(context, model, input, hole, timeoutMs)
        } catch (e: Throwable) {
            android.util.Log.w("Photos", "LaMa failed, classic fill", e)
            listOf(region, square, input, maskBmp).forEach { if (it !== src) runCatching { it.recycle() } }
            return@runCatching if (into == null) removeClassic(context, src, t) else null
        }
        android.util.Log.i("PhotosPerf", "cleanup model=$model area=$sq object=${t.objMask != null} lasso=${t.lasso} ms=${android.os.SystemClock.elapsedRealtime() - t0}")

        // Soft edge: fully replaced inside, blending out over a few pixels.
        val holeBmp = Bitmap.createBitmap(IntArray(model * model) { if (hole[it]) -0x1000000 else 0 }, model, model, Bitmap.Config.ARGB_8888)
        val blur = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(holeBmp, max(1, model / 48), max(1, model / 48), true), model, model, true)
        val bl = IntArray(model * model).also { blur.getPixels(it, 0, model, 0, 0, model, model) }
        val fp = IntArray(model * model).also { filled.getPixels(it, 0, model, 0, 0, model, model) }
        for (q in fp.indices) {
            val a = if (hole[q]) 255 else ((bl[q] ushr 24) * 2).coerceAtMost(255)
            fp[q] = (a shl 24) or (fp[q] and 0xFFFFFF)
        }
        val patchSq = Bitmap.createScaledBitmap(Bitmap.createBitmap(fp, model, model, Bitmap.Config.ARGB_8888), sq, sq, true)
        val patch = Bitmap.createBitmap(patchSq, 0, 0, cw, ch)
        val f = into ?: File(File(context.filesDir, "cleanup").apply { mkdirs() }, UUID.randomUUID().toString() + ".png")
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { patch.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
        listOf(region, square, input, maskBmp, filled, holeBmp, blur, patchSq, patch).forEach { if (it !== src) runCatching { it.recycle() } }
        if (soft !== src) soft.recycle()
        CleanPatch(f.absolutePath, rl / w, rt / h, (rl + cw) / w, (rt + ch) / h)
    }.getOrElse { e ->
        android.util.Log.w("Photos", "clean up", e)
        null
    }

    /** A stroke that comes back near where it started, around an area: "circle it to remove it". */
    private fun isLoop(points: List<Float>, ow: Float, oh: Float, brushPx: Float): Boolean {
        if (points.size < 16) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in points.indices step 2) {
            val x = points[k] * ow; val y = points[k + 1] * oh
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        val bw = maxX - minX; val bh = maxY - minY
        if (bw < brushPx * 2.5f || bh < brushPx * 2.5f) return false
        val dx = (points[points.size - 2] - points[0]) * ow; val dy = (points[points.size - 1] - points[1]) * oh
        return kotlin.math.hypot(dx, dy) < max(bw, bh) * .35f + brushPx
    }

    /** The previous fill (no model bundled, or it couldn't run): patch-based, from the surroundings. */
    private fun removeClassic(context: Context, src: Bitmap, t: Target): CleanPatch? {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val minX0 = t.minX; val minY0 = t.minY; val maxX0 = t.maxX; val maxY0 = t.maxY
        val mask = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ALPHA_8)
        Canvas(mask).apply {
            if (t.objMask != null) drawBitmap(t.objMask, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            else {
                concat(t.inv)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                if (t.lasso) drawPath(android.graphics.Path(t.path).apply { close() }, paint.apply { style = Paint.Style.FILL })
                drawPath(t.path, paint.apply { style = Paint.Style.STROKE; strokeWidth = t.brushPx })
            }
        }
        val pad = max(32f, max(maxX0 - minX0, maxY0 - minY0) * .9f)
        val rl = (minX0 - pad).roundToInt().coerceIn(0, src.width - 1)
        val rt = (minY0 - pad).roundToInt().coerceIn(0, src.height - 1)
        val rr = (maxX0 + pad).roundToInt().coerceIn(rl + 1, src.width)
        val rb = (maxY0 + pad).roundToInt().coerceIn(rt + 1, src.height)
        val rw = rr - rl; val rh = rb - rt
        val ws = min(1f, 512f / max(rw, rh))
        val ww = max(8, (rw * ws).roundToInt()); val wh = max(8, (rh * ws).roundToInt())
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val region = Bitmap.createScaledBitmap(Bitmap.createBitmap(soft, rl, rt, rw, rh), ww, wh, true)
        val maskRegion = Bitmap.createBitmap(mask, rl, rt, rw, rh)
        val maskSmall = Bitmap.createScaledBitmap(maskRegion, ww, wh, true)
        val mp = IntArray(ww * wh).also { maskSmall.getPixels(it, 0, ww, 0, 0, ww, wh) }
        var holes = BooleanArray(ww * wh) { (mp[it] ushr 24) > 30 }
        repeat(2) { holes = dilate(holes, ww, wh) }
        if (holes.none { it }) return null
        val px = IntArray(ww * wh).also { region.getPixels(it, 0, ww, 0, 0, ww, wh) }
        val filled = Inpaint.fill(px, ww, wh, holes)
        val small = Bitmap.createBitmap(filled, ww, wh, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(small, rw, rh, true)
        val feather = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(maskRegion, max(1, rw / 8), max(1, rh / 8), true), rw, rh, true)
        val fa = IntArray(rw * rh).also { feather.getPixels(it, 0, rw, 0, 0, rw, rh) }
        val bp = IntArray(rw * rh).also { big.getPixels(it, 0, rw, 0, 0, rw, rh) }
        for (k in bp.indices) {
            val a = (((fa[k] ushr 24) * 2.6f).toInt()).coerceIn(0, 255)
            bp[k] = (a shl 24) or (bp[k] and 0xFFFFFF)
        }
        val patch = Bitmap.createBitmap(bp, rw, rh, Bitmap.Config.ARGB_8888)
        val dir = File(context.filesDir, "cleanup").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString() + ".png")
        f.outputStream().use { patch.compress(Bitmap.CompressFormat.PNG, 100, it) }
        listOf(mask, maskRegion, maskSmall, region, small, big, feather, patch).forEach { runCatching { it.recycle() } }
        if (soft !== src) soft.recycle()
        return CleanPatch(f.absolutePath, rl / w, rt / h, rr / w, rb / h)
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = m.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) o[yy * w + xx] = true
            }
        }
        return o
    }
}

/** The Clean Up brush, drawn as a soft glow over the photo while you paint. */
@Composable
fun CleanUpLayer(bmp: Bitmap, params: EditParams, enabled: Boolean, onStroke: (List<Float>) -> Unit, modifier: Modifier = Modifier, highlight: Bitmap? = null) {
    val cur by rememberUpdatedState(params)
    val cb by rememberUpdatedState(onStroke)
    val on by rememberUpdatedState(enabled)
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val glow = if (highlight != null) androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "g",
    ).value else 0f
    fun rect(w: Float, h: Float): FloatArray {
        val (ow, oh) = EditRender.outputSize(cur, bmp.width.toFloat(), bmp.height.toFloat())
        val (s, o) = EditRender.fit(ow, oh, w, h)
        return floatArrayOf(o.x, o.y, ow * s, oh * s)
    }
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(bmp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!on) return@awaitEachGesture
                    val pts = arrayListOf(down.position)
                    live = pts.toList()
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.pressed && (c.position - pts.last()).getDistance() > 2f) { pts += c.position; live = pts.toList() }
                        c.consume()
                    } while (ev.changes.any { it.pressed })
                    val r = rect(size.width.toFloat(), size.height.toFloat())
                    val norm = ArrayList<Float>(pts.size * 2)
                    for (o in pts) { norm += ((o.x - r[0]) / r[2]).coerceIn(0f, 1f); norm += ((o.y - r[1]) / r[3]).coerceIn(0f, 1f) }
                    live = emptyList()
                    cb(norm)
                }
            }
    ) {
        // The object being removed glows softly while the fill is worked out (like iPhone).
        if (highlight != null) {
            val r = rect(size.width, size.height)
            val (ow, _) = EditRender.outputSize(cur, bmp.width.toFloat(), bmp.height.toFloat())
            val s = r[2] / ow
            val (bw, bh) = EditRender.baseSize(cur, bmp.width.toFloat(), bmp.height.toFloat())
            val m = Matrix(EditRender.matrix(cur, bmp.width.toFloat(), bmp.height.toFloat())).apply { postTranslate(-cur.crop.left * bw, -cur.crop.top * bh) }
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save(); nc.translate(r[0], r[1]); nc.scale(s, s); nc.concat(m)
                nc.drawBitmap(highlight, null, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                    alpha = (90 + 70 * glow).toInt()
                })
                nc.restore()
            }
        }
        if (live.isEmpty()) return@Canvas
        val r = rect(size.width, size.height)
        val path = Path().apply {
            moveTo(live[0].x, live[0].y)
            live.drop(1).forEach { lineTo(it.x, it.y) }
            if (live.size == 1) lineTo(live[0].x + 1f, live[0].y + 1f)
        }
        val wpx = CleanUp.BRUSH * r[2]
        drawPath(path, Color.White.copy(alpha = .22f), style = DrawStroke(wpx * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = .55f), style = DrawStroke(wpx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}


/** Hint and undo / redo under the photo while cleaning up. */
@Composable
fun CleanUpPanel(backdrop: com.galeria.app.glass.Backdrop, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(com.galeria.app.R.string.clean_up_hint),
            style = com.galeria.app.ui.theme.Type.subhead, color = Color.White.copy(alpha = .75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 30.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            com.galeria.app.ui.components.GlassIconButton(backdrop, com.galeria.app.ui.components.Sym.undo, androidx.compose.ui.res.stringResource(com.galeria.app.R.string.undo), onUndo, enabled = canUndo)
            com.galeria.app.ui.components.GlassIconButton(backdrop, com.galeria.app.ui.components.Sym.redo, androidx.compose.ui.res.stringResource(com.galeria.app.R.string.redo), onRedo, enabled = canRedo)
        }
    }
}
