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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import com.galeria.app.data.Inpaint
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

    /**
     * Removes what was brushed over. [points] are x,y pairs in 0…1 of the edited picture (as shown);
     * returns a patch in the original's coordinates.
     */
    fun remove(context: Context, src: Bitmap, p: EditParams, points: List<Float>, brush: Float): CleanPatch? = runCatching {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val (bw, bh) = EditRender.baseSize(p, w, h)
        val (ow, oh) = EditRender.outputSize(p, w, h)
        val full = Matrix(EditRender.matrix(p, w, h)).apply { postTranslate(-p.crop.left * bw, -p.crop.top * bh) }
        val inv = Matrix().also { full.invert(it) }
        // The brushed area, drawn in the original's pixels.
        val mask = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ALPHA_8)
        val path = android.graphics.Path()
        path.moveTo(points[0] * ow, points[1] * oh)
        var i = 2
        while (i + 1 < points.size) { path.lineTo(points[i] * ow, points[i + 1] * oh); i += 2 }
        if (points.size == 2) path.lineTo(points[0] * ow + 1f, points[1] * oh + 1f)
        Canvas(mask).apply {
            concat(inv)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = brush * ow; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = android.graphics.Color.BLACK
            })
        }
        // Bounds of the brushed area, plus surroundings to borrow texture from.
        val pts = FloatArray(points.size) { k -> if (k % 2 == 0) points[k] * ow else points[k] * oh }
        inv.mapPoints(pts)
        val rad = inv.mapRadius(brush * ow / 2f) + 2f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in pts.indices step 2) { minX = min(minX, pts[k]); maxX = max(maxX, pts[k]); minY = min(minY, pts[k + 1]); maxY = max(maxY, pts[k + 1]) }
        minX -= rad; minY -= rad; maxX += rad; maxY += rad
        val pad = max(32f, max(maxX - minX, maxY - minY) * .9f)
        val rl = (minX - pad).roundToInt().coerceIn(0, src.width - 1)
        val rt = (minY - pad).roundToInt().coerceIn(0, src.height - 1)
        val rr = (maxX + pad).roundToInt().coerceIn(rl + 1, src.width)
        val rb = (maxY + pad).roundToInt().coerceIn(rt + 1, src.height)
        val rw = rr - rl; val rh = rb - rt
        // Work at up to 512 px for speed.
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
        // Soft edge so the patch blends in.
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
        CleanPatch(f.absolutePath, rl / w, rt / h, rr / w, rb / h)
    }.getOrNull()

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
fun CleanUpLayer(bmp: Bitmap, params: EditParams, enabled: Boolean, onStroke: (List<Float>) -> Unit, modifier: Modifier = Modifier) {
    val cur by rememberUpdatedState(params)
    val cb by rememberUpdatedState(onStroke)
    val on by rememberUpdatedState(enabled)
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
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
