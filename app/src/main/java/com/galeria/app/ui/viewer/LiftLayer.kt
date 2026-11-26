package com.galeria.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.galeria.app.data.Lift
import com.galeria.app.data.Spatial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** A soft silhouette of [img] (blurred alpha in one colour), with [pad] px of room around it. */
private class Halo(val bmp: Bitmap, val pad: Float)

private fun halo(img: Bitmap, color: Int, blur: Float): Halo {
    // Worked out on a small copy: it is blurry anyway.
    val k = minOf(1f, 320f / max(img.width, img.height))
    val sw = max(1, (img.width * k).roundToInt()); val sh = max(1, (img.height * k).roundToInt())
    val small = Bitmap.createScaledBitmap(img, sw, sh, true)
    val r = max(1, (blur * k).roundToInt())
    val w = sw + 4 * r; val h = sh + 4 * r
    val a = FloatArray(w * h)
    val px = IntArray(sw * sh).also { small.getPixels(it, 0, sw, 0, 0, sw, sh) }
    if (small !== img) small.recycle()
    for (y in 0 until sh) for (x in 0 until sw) a[(y + 2 * r) * w + x + 2 * r] = (px[y * sw + x] ushr 24) / 255f
    var b = a
    repeat(3) { b = Spatial.box(b, w, h, r) }
    val rgb = color and 0xFFFFFF
    val out = IntArray(w * h) { ((b[it] * 255f).roundToInt().coerceIn(0, 255) shl 24) or rgb }
    return Halo(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), 2f * r / k)
}

/**
 * The lifted subject, drawn over its place in the photo ([fit] is where the photo is drawn): a white
 * light runs round its outline, then it rises a little with a soft shadow, like iPhone. [onPlaced]
 * gets the subject's rectangle on screen (for the menu).
 */
@Composable
fun LiftLayer(cut: Lift.Cutout, fit: Rect, onPlaced: (Rect) -> Unit) {
    val density = LocalDensity.current
    val glowPx = with(density) { androidx.compose.ui.unit.Dp(9f).toPx() }
    val shadowPx = with(density) { androidx.compose.ui.unit.Dp(14f).toPx() }
    val scaleToScreen = fit.width / max(1f, cut.image.width / (cut.right - cut.left))
    val glow by produceState<Halo?>(null, cut) { value = withContext(Dispatchers.Default) { halo(cut.image, 0xFFFFFF, glowPx / scaleToScreen) } }
    val shadow by produceState<Halo?>(null, cut) { value = withContext(Dispatchers.Default) { halo(cut.image, 0x000000, shadowPx / scaleToScreen) } }
    val img = remember(cut) { cut.image.asImageBitmap() }
    val rise = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val shine = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { shine.animateTo(1f, tween(260)); shine.animateTo(.45f, tween(900, easing = FastOutSlowInEasing)) }
        launch { sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch { rise.animateTo(1f, spring(dampingRatio = .62f, stiffness = 380f)) }
    }
    val placed by rememberUpdatedState(onPlaced)
    val r = Rect(fit.left + cut.left * fit.width, fit.top + cut.top * fit.height, fit.left + cut.right * fit.width, fit.top + cut.bottom * fit.height)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> placed(r.translate(c.positionInRoot())) }
            .drawBehind {
                val s = 1f + .035f * rise.value
                val c = r.center
                fun scaled(x: Rect) = Rect(c.x + (x.left - c.x) * s, c.y + (x.top - c.y) * s, c.x + (x.right - c.x) * s, c.y + (x.bottom - c.y) * s)
                fun Halo.rect(): Rect { val p = pad * scaleToScreen; return Rect(r.left - p, r.top - p, r.right + p, r.bottom + p) }
                // Soft shadow under the risen subject.
                shadow?.let { h ->
                    val d = scaled(h.rect()).translate(Offset(0f, 6f * density.density * rise.value))
                    drawImage(h.bmp.asImageBitmap(), IntOffset.Zero, IntSize(h.bmp.width, h.bmp.height),
                        IntOffset(d.left.roundToInt(), d.top.roundToInt()), IntSize(d.width.roundToInt(), d.height.roundToInt()), alpha = .32f * rise.value)
                }
                // The white light: it runs round the outline once, then stays as a faint glow.
                glow?.let { h ->
                    val d = scaled(h.rect())
                    drawIntoCanvas { cv -> cv.saveLayer(d, Paint()) }
                    drawImage(h.bmp.asImageBitmap(), IntOffset.Zero, IntSize(h.bmp.width, h.bmp.height),
                        IntOffset(d.left.roundToInt(), d.top.roundToInt()), IntSize(d.width.roundToInt(), d.height.roundToInt()), alpha = shine.value)
                    if (sweep.value < 1f) rotate(360f * sweep.value, d.center) {
                        drawRect(
                            Brush.sweepGradient(listOf(Color.White.copy(alpha = .35f), Color.White, Color.White.copy(alpha = .35f), Color.White.copy(alpha = .35f)), d.center),
                            topLeft = Offset(d.left - d.width, d.top - d.height), size = androidx.compose.ui.geometry.Size(d.width * 3f, d.height * 3f),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    drawIntoCanvas { cv -> cv.restore() }
                }
                val d = scaled(r)
                drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(d.left.roundToInt(), d.top.roundToInt()), IntSize(d.width.roundToInt(), d.height.roundToInt()))
            }
    )
}
