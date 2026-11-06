package com.galeria.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Width / height the crop must keep, in the rotated frame; null when free. */
fun aspectRatio(p: EditParams, bw: Float, bh: Float): Float? {
    val a = p.aspect
    if (a == Aspect.Freeform) return null
    val rb = when (a) {
        Aspect.Original -> max(bw, bh) / min(bw, bh)
        else -> a.w.toFloat() / a.h
    }
    return if (p.portraitAspect) 1f / rb else rb
}

/** The largest crop of the chosen ratio inside the current one, centred. */
fun applyAspect(p: EditParams, w: Float, h: Float): EditParams {
    val (bw, bh) = EditRender.baseSize(p, w, h)
    val r = aspectRatio(p, bw, bh) ?: return p
    val c = p.crop
    val cw = c.width * bw; val ch = c.height * bh
    var (nw, nh) = if (cw / ch > r) ch * r to ch else cw to cw / r
    nw = min(nw, bw); nh = min(nh, bh)
    val cx = c.center.x * bw; val cy = c.center.y * bh
    var l = cx - nw / 2f; var t = cy - nh / 2f
    l = l.clampTo(0f, bw - nw); t = t.clampTo(0f, bh - nh)
    return p.copy(crop = Rect(l / bw, t / bh, (l + nw) / bw, (t + nh) / bh))
}

/** coerceIn that never throws: an empty range (from float rounding) collapses to its lower end. */
internal fun Float.clampTo(lo: Float, hi: Float): Float = if (hi < lo) lo else coerceIn(lo, hi)

/** Rotate the crop with a 90° counter-clockwise turn. */
fun rotateCrop(c: Rect): Rect = Rect(c.top, 1f - c.right, c.bottom, 1f - c.left)
fun flipCrop(c: Rect): Rect = Rect(1f - c.right, c.top, 1f - c.left, c.bottom)

private const val PAN = 8
private const val PINCH = 9

/**
 * The crop canvas: the whole (rotated, straightened) photo, the crop frame with corner handles,
 * a rule-of-thirds grid while touching, and the outside dimmed. Releasing a handle zooms the new
 * crop back to fill the space, as on iPhone.
 */
@Composable
fun CropView(bmp: Bitmap, shader: Any?, params: EditParams, onChange: (EditParams) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val mx = with(density) { 30.dp.toPx() }
    val my = with(density) { 26.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
    val cur by rememberUpdatedState(params)
    val change by rememberUpdatedState(onChange)
    var mode by remember { mutableIntStateOf(-1) } // -1 idle, 0..7 handles (frozen view), PAN/PINCH follow
    var touching by remember { mutableStateOf(false) }
    val vs = remember { Animatable(0f) }
    val vx = remember { Animatable(0f) }
    val vy = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val input = remember(bmp) { BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }

    fun target(p: EditParams): Triple<Float, Float, Float> {
        val (bw, bh) = EditRender.baseSize(p, w, h)
        val cw = max(1f, p.crop.width * bw); val ch = max(1f, p.crop.height * bh)
        val s = min((box.width - 2 * mx) / cw, (box.height - 2 * my) / ch)
        val cx = p.crop.center.x * bw; val cy = p.crop.center.y * bh
        return Triple(s, box.width / 2f - cx * s, box.height / 2f - cy * s)
    }

    LaunchedEffect(params.crop, params.rot, params.straighten, box, mode) {
        if (box == IntSize.Zero || mode in 0..7) return@LaunchedEffect
        val (s, ox, oy) = target(params)
        if (!ready || mode == PAN || mode == PINCH) {
            vs.snapTo(s); vx.snapTo(ox); vy.snapTo(oy); ready = true
        } else {
            val sp = spring<Float>(dampingRatio = .92f, stiffness = 280f)
            launch { vs.animateTo(s, sp) }; launch { vx.animateTo(ox, sp) }; launch { vy.animateTo(oy, sp) }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(w, h) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p0 = cur
                    val (bw, bh) = EditRender.baseSize(p0, w, h)
                    var l = p0.crop.left * bw; var t = p0.crop.top * bh; var r = p0.crop.right * bw; var b = p0.crop.bottom * bh
                    val ratio = aspectRatio(p0, bw, bh)
                    fun vX(x: Float) = x * vs.value + vx.value
                    fun vY(y: Float) = y * vs.value + vy.value
                    val pos = down.position
                    val touch = 34.dp.toPx()
                    val corners = listOf(Offset(vX(l), vY(t)), Offset(vX(r), vY(t)), Offset(vX(r), vY(b)), Offset(vX(l), vY(b)))
                    var m = corners.indexOfFirst { (it - pos).getDistance() < touch }
                    if (m < 0) m = when {
                        abs(pos.x - vX(l)) < touch * .7f && pos.y in vY(t)..vY(b) -> 4
                        abs(pos.y - vY(t)) < touch * .7f && pos.x in vX(l)..vX(r) -> 5
                        abs(pos.x - vX(r)) < touch * .7f && pos.y in vY(t)..vY(b) -> 6
                        abs(pos.y - vY(b)) < touch * .7f && pos.x in vX(l)..vX(r) -> 7
                        else -> PAN
                    }
                    mode = m; touching = true
                    val minB = max(max(bw, bh) * .06f, 64.dp.toPx() / max(vs.value, 1e-3f))
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            mode = PINCH
                            val z = ev.calculateZoom()
                            if (z != 1f) {
                                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                                var nw = ((r - l) / z).clampTo(minB, bw); var nh = ((b - t) / z).clampTo(minB, bh)
                                if (ratio != null) { if (nw / nh > ratio) nw = nh * ratio else nh = nw / ratio }
                                l = (cx - nw / 2f).clampTo(0f, bw - nw); t = (cy - nh / 2f).clampTo(0f, bh - nh); r = l + nw; b = t + nh
                            }
                        } else {
                            val ch = ev.changes.firstOrNull { it.pressed } ?: ev.changes.first()
                            val d = ch.positionChange() / max(vs.value, 1e-3f)
                            when (mode) {
                                PAN, PINCH -> {
                                    val nw = r - l; val nh = b - t
                                    l = (l - d.x).clampTo(0f, bw - nw); t = (t - d.y).clampTo(0f, bh - nh); r = l + nw; b = t + nh
                                }
                                else -> {
                                    val mL = mode == 0 || mode == 3 || mode == 4
                                    val mR = mode == 1 || mode == 2 || mode == 6
                                    val mT = mode == 0 || mode == 1 || mode == 5
                                    val mB = mode == 2 || mode == 3 || mode == 7
                                    if (mL) l = (l + d.x).clampTo(0f, r - minB)
                                    if (mR) r = (r + d.x).clampTo(l + minB, bw)
                                    if (mT) t = (t + d.y).clampTo(0f, b - minB)
                                    if (mB) b = (b + d.y).clampTo(t + minB, bh)
                                    if (ratio != null) {
                                        // Keep the ratio, anchored on the opposite side.
                                        val horizontal = mode == 4 || mode == 6
                                        val vertical = mode == 5 || mode == 7
                                        var nw = r - l; var nh = b - t
                                        if (horizontal) nh = nw / ratio
                                        else if (vertical) nw = nh * ratio
                                        else if (nw / nh > ratio) nh = nw / ratio else nw = nh * ratio
                                        if (nw > bw) { nw = bw; nh = nw / ratio }
                                        if (nh > bh) { nh = bh; nw = nh * ratio }
                                        if (horizontal) { val cy = (t + b) / 2f; t = cy - nh / 2f; b = cy + nh / 2f }
                                        else if (mT) t = b - nh else b = t + nh
                                        if (vertical) { val cx = (l + r) / 2f; l = cx - nw / 2f; r = cx + nw / 2f }
                                        else if (mL) l = r - nw else r = l + nw
                                        if (l < 0f) { r -= l; l = 0f }; if (r > bw) { l -= r - bw; r = bw }
                                        if (t < 0f) { b -= t; t = 0f }; if (b > bh) { t -= b - bh; b = bh }
                                        l = l.coerceAtLeast(0f); t = t.coerceAtLeast(0f)
                                    }
                                }
                            }
                        }
                        change(cur.copy(crop = Rect(l / bw, t / bh, r / bw, b / bh)))
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    touching = false
                    mode = -1
                }
            }
    ) {
        if (!ready) return@Canvas
        val p = cur
        val (bw, bh) = EditRender.baseSize(p, w, h)
        val s = vs.value; val ox = vx.value; val oy = vy.value
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(ox, oy); nc.scale(s, s)
            nc.concat(EditRender.matrix(p, w, h))
            if (shader is RuntimeShader) {
                EditRender.configure(shader, p, w, h, input)
                paint.shader = shader; paint.colorFilter = null
            } else {
                paint.shader = input; paint.colorFilter = ColorMatrixColorFilter(EditRender.colorMatrix(p))
            }
            nc.drawRect(0f, 0f, w, h, paint)
            nc.restore()
        }
        val cr = Rect(p.crop.left * bw * s + ox, p.crop.top * bh * s + oy, p.crop.right * bw * s + ox, p.crop.bottom * bh * s + oy)
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size)); addRect(cr)
        }
        drawPath(dim, Color.Black.copy(alpha = if (touching) .5f else .72f))
        if (touching) {
            val gc = Color.White.copy(alpha = .55f); val gw = .8.dp.toPx()
            for (k in 1..2) {
                val x = cr.left + cr.width * k / 3f; val y = cr.top + cr.height * k / 3f
                drawLine(gc, Offset(x, cr.top), Offset(x, cr.bottom), gw)
                drawLine(gc, Offset(cr.left, y), Offset(cr.right, y), gw)
            }
        }
        drawRect(Color.White.copy(alpha = .9f), cr.topLeft, cr.size, style = Stroke(1.2.dp.toPx()))
        val cl = 22.dp.toPx(); val cw = 3.2.dp.toPx(); val o = cw / 2f
        val cc = Color.White
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(cc, Offset(x - dx * o, y - dy * o), Offset(x + dx * cl, y - dy * o), cw, StrokeCap.Square)
            drawLine(cc, Offset(x - dx * o, y - dy * o), Offset(x - dx * o, y + dy * cl), cw, StrokeCap.Square)
        }
        corner(cr.left, cr.top, 1f, 1f); corner(cr.right, cr.top, -1f, 1f)
        corner(cr.right, cr.bottom, -1f, -1f); corner(cr.left, cr.bottom, 1f, -1f)
        // Side handles
        val sl = 18.dp.toPx()
        drawLine(cc, Offset(cr.center.x - sl / 2f, cr.top - o), Offset(cr.center.x + sl / 2f, cr.top - o), cw)
        drawLine(cc, Offset(cr.center.x - sl / 2f, cr.bottom + o), Offset(cr.center.x + sl / 2f, cr.bottom + o), cw)
        drawLine(cc, Offset(cr.left - o, cr.center.y - sl / 2f), Offset(cr.left - o, cr.center.y + sl / 2f), cw)
        drawLine(cc, Offset(cr.right + o, cr.center.y - sl / 2f), Offset(cr.right + o, cr.center.y + sl / 2f), cw)
        @Suppress("UNUSED_VARIABLE") val unused = Size.Zero
    }
}
