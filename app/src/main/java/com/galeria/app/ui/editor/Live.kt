package com.galeria.app.ui.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Keeps one decoder open on a Live Photo's video while the editor is up. */
class LiveSession(file: File) {
    private val r = MediaMetadataRetriever()
    val durationUs: Long
    private val vw: Int
    private val vh: Int

    init {
        r.setDataSource(file.absolutePath)
        durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { vw = h; vh = w } else { vw = w; vh = h }
    }

    @Synchronized
    fun frame(us: Long, maxSide: Int, exact: Boolean = true): Bitmap? = runCatching {
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val s = if (vw > 0 && vh > 0) minOf(1f, maxSide.toFloat() / max(vw, vh)) else 1f
        if (s < 1f) r.getScaledFrameAtTime(us.coerceAtLeast(0L), opt, max(1, (vw * s).roundToInt()), max(1, (vh * s).roundToInt()))
        else r.getFrameAtTime(us.coerceAtLeast(0L), opt)
    }.getOrNull()

    @Synchronized
    fun release() = runCatching { r.release() }
}

/**
 * The Live Photo frame strip: frames across the clip, a white frame on the chosen moment,
 * a dot over the original key photo and trim handles at both ends.
 */
@Composable
fun LiveStrip(
    frames: List<ImageBitmap?>,
    durationUs: Long,
    scrubUs: Long,
    originalKeyUs: Long,
    trimStartUs: Long,
    trimEndUs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onTrim: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val trim by rememberUpdatedState(onTrim)
    val ts by rememberUpdatedState(trimStartUs)
    val te by rememberUpdatedState(if (trimEndUs < 0) durationUs else trimEndUs)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 20.dp)
                .pointerInput(durationUs) {
                    if (durationUs <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        fun usAt(x: Float) = ((x / w).coerceIn(0f, 1f) * durationUs).toLong()
                        val xs = ts.toFloat() / durationUs * w
                        val xe = te.toFloat() / durationUs * w
                        val grab = 22.dp.toPx()
                        val mode = when {
                            abs(down.position.x - xs) < grab && abs(down.position.x - xs) <= abs(down.position.x - xe) -> 1
                            abs(down.position.x - xe) < grab -> 2
                            else -> 0
                        }
                        if (mode == 0) scrub(usAt(down.position.x))
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.first()
                            val x = c.position.x
                            when (mode) {
                                0 -> scrub(usAt(x).coerceIn(ts, te))
                                1 -> trim(usAt(x).coerceIn(0L, (te - 300_000L).coerceAtLeast(0L)), te)
                                2 -> trim(ts, usAt(x).coerceIn(ts + 300_000L, durationUs))
                            }
                            c.consume()
                        } while (ev.changes.any { it.pressed })
                        if (mode == 0) end()
                    }
                }
        ) {
            val stripH = 46.dp.toPx()
            val top = (size.height - stripH) / 2f
            val n = frames.size.coerceAtLeast(1)
            val tw = size.width / n
            for (i in 0 until n) {
                val f = frames.getOrNull(i)
                val l = i * tw
                if (f == null) {
                    drawRect(Color.White.copy(alpha = .08f), Offset(l, top), Size(tw - 1f, stripH))
                } else clipRect(l, top, l + tw + .5f, top + stripH) { drawCover(f, Offset(l, top), Size(tw + .5f, stripH)) }
            }
            if (durationUs > 0) {
                val xs = ts.toFloat() / durationUs * size.width
                val xe = te.toFloat() / durationUs * size.width
                val trimmed = ts > 0 || te < durationUs
                drawRect(Color.Black.copy(alpha = .6f), Offset(0f, top), Size(xs, stripH))
                drawRect(Color.Black.copy(alpha = .6f), Offset(xe, top), Size(size.width - xe, stripH))
                val hc = if (trimmed) EditYellow else Color.White.copy(alpha = .9f)
                val hw = 7.dp.toPx()
                drawRoundRect(hc, Offset(xs - hw, top - 2.dp.toPx()), Size(hw, stripH + 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                drawRoundRect(hc, Offset(xe, top - 2.dp.toPx()), Size(hw, stripH + 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                if (trimmed) {
                    drawLine(EditYellow, Offset(xs, top - 1.dp.toPx()), Offset(xe, top - 1.dp.toPx()), 2.dp.toPx())
                    drawLine(EditYellow, Offset(xs, top + stripH + 1.dp.toPx()), Offset(xe, top + stripH + 1.dp.toPx()), 2.dp.toPx())
                }
                if (originalKeyUs >= 0) {
                    val kx = originalKeyUs.toFloat() / durationUs * size.width
                    drawCircle(Color.White, 2.6.dp.toPx(), Offset(kx, top - 8.dp.toPx()))
                }
                val sx = scrubUs.coerceIn(0L, durationUs).toFloat() / durationUs * size.width
                val selW = max(tw * 1.15f, 34.dp.toPx())
                val selH = size.height - 2.dp.toPx()
                val sl = (sx - selW / 2f).coerceIn(0f, size.width - selW)
                val fi = ((scrubUs.toFloat() / durationUs) * n).toInt().coerceIn(0, n - 1)
                frames.getOrNull(fi)?.let { f -> clipRect(sl, 1.dp.toPx(), sl + selW, 1.dp.toPx() + selH) { drawCover(f, Offset(sl, 1.dp.toPx()), Size(selW, selH)) } }
                drawRoundRect(Color.White, Offset(sl, 1.dp.toPx()), Size(selW, selH), CornerRadius(5.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawCover(img: ImageBitmap, o: Offset, s: Size) {
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    val sc = max(s.width / iw, s.height / ih)
    val sw = s.width / sc; val sh = s.height / sc
    drawImage(
        img,
        IntOffset(((iw - sw) / 2f).roundToInt().coerceAtLeast(0), ((ih - sh) / 2f).roundToInt().coerceAtLeast(0)),
        IntSize(sw.roundToInt().coerceIn(1, img.width), sh.roundToInt().coerceIn(1, img.height)),
        IntOffset(o.x.roundToInt(), o.y.roundToInt()),
        IntSize(s.width.roundToInt(), s.height.roundToInt()),
    )
}
