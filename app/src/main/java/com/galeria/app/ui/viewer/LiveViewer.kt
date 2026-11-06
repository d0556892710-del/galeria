package com.galeria.app.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.galeria.app.R
import com.galeria.app.data.LiveEffect
import com.galeria.app.data.Media
import com.galeria.app.data.MotionStore
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.MenuItem
import com.galeria.app.ui.components.MenuSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.onRootRect
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.editor.EditParams
import com.galeria.app.ui.editor.LiveSession
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun liveEffectLabel(e: LiveEffect): Int = when (e) {
    LiveEffect.Live -> R.string.le_live
    LiveEffect.Loop -> R.string.le_loop
    LiveEffect.Bounce -> R.string.le_bounce
    LiveEffect.LongExposure -> R.string.le_long
    LiveEffect.Off -> R.string.le_off
}

/** The "LIVE ⌄" glass badge under the title, like iOS. */
@Composable
fun LiveBadge(backdrop: Backdrop, effect: LiveEffect, onMenu: (Rect) -> Unit, modifier: Modifier = Modifier) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    GlassSurface(backdrop, modifier.height(30.dp).onRootRect { rect = it }.pressable { onMenu(rect) }, RoundedCornerShape(15.dp)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (effect == LiveEffect.Off) Sym.liveOff else Sym.live, null, Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(liveEffectLabel(effect)).uppercase(), style = Type.caption1Semibold.copy(fontSize = 12.sp, letterSpacing = .8.sp))
            Spacer(Modifier.width(4.dp))
            Icon(Sym.chevronDown, null, Modifier.size(10.dp))
        }
    }
}

fun liveMenu(current: LiveEffect, res: android.content.res.Resources, onPick: (LiveEffect) -> Unit): MenuSpec {
    fun item(e: LiveEffect, icon: androidx.compose.ui.graphics.vector.ImageVector) = MenuItem(res.getString(liveEffectLabel(e)), icon, checked = e == current) { onPick(e) }
    return MenuSpec(groups = listOf(
        listOf(item(LiveEffect.Live, Sym.live), item(LiveEffect.Loop, Sym.rotate), item(LiveEffect.Bounce, Sym.flip), item(LiveEffect.LongExposure, Sym.longExposure)),
        listOf(item(LiveEffect.Off, Sym.liveOff)),
    ))
}

/**
 * Plays a Live Photo's motion over the still: while pressed (Live) or continuously (Loop).
 * Fades back to the still when it ends, like iOS.
 */
@Composable
fun LiveLayer(m: Media, video: VideoController, motion: MotionStore, playing: Boolean, loop: Boolean, edit: EditParams?) {
    var file by remember(m.id, m.dateModified) { mutableStateOf<File?>(null) }
    LaunchedEffect(m.id, m.dateModified, playing) { if (playing && file == null) file = motion.video(m)?.first }
    val uri = file?.let { Uri.fromFile(it) }
    LaunchedEffect(playing, uri, loop) {
        if (uri == null) return@LaunchedEffect
        if (playing) {
            val s = (edit?.trimStartUs ?: 0L) / 1000L
            val e = edit?.trimEndUs?.takeIf { it > 0 }?.div(1000L) ?: -1L
            video.playLive(uri, loop, edit?.muted == true, s, e)
        } else if (video.current == uri) video.stopLive()
    }
    val show = playing && uri != null && video.current == uri && video.firstFrame && !video.ended
    val a by animateFloatAsState(if (show) 1f else 0f, tween(if (show) 140 else 320), label = "live")
    if (playing || a > .01f) {
        val ratio = m.aspect.coerceIn(.2f, 5f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).graphicsLayer { alpha = a }) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx) },
                    update = { tv -> if (playing) video.player.setVideoTextureView(tv) },
                    onRelease = { tv -> video.player.clearVideoTextureView(tv) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private object EffectCache {
    var bounceId = -1L
    var bounce: List<ImageBitmap> = emptyList()
    val longExposure = object : android.util.LruCache<Long, ImageBitmap>(3) {}
}

/** Bounce: the motion forwards then backwards, forever. */
@Composable
fun BounceLayer(m: Media, motion: MotionStore, active: Boolean) {
    var frames by remember(m.id) { mutableStateOf(if (EffectCache.bounceId == m.id) EffectCache.bounce else emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.id, active) {
        if (!active || frames.isNotEmpty()) return@LaunchedEffect
        val f = motion.video(m)?.first ?: return@LaunchedEffect
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val s = LiveSession(f)
                try {
                    val n = 36
                    (0 until n).mapNotNull { i -> s.frame((s.durationUs * i / n), 640)?.asImageBitmap() }
                } finally { s.release() }
            }.getOrDefault(emptyList())
        }
        EffectCache.bounceId = m.id; EffectCache.bounce = list
        frames = list
    }
    LaunchedEffect(active, frames) {
        if (!active || frames.size < 2) return@LaunchedEffect
        var dir = 1; var last = 0L
        while (isActive) {
            withFrameNanos { t ->
                if (t - last >= 40_000_000L) {
                    last = t
                    var ni = index + dir
                    if (ni >= frames.size) { dir = -1; ni = frames.size - 2 } else if (ni < 0) { dir = 1; ni = 1 }
                    index = ni
                }
            }
        }
    }
    if (active && frames.isNotEmpty()) FitImage(frames[index.coerceIn(0, frames.size - 1)], m.aspect)
}

/** Long Exposure: every frame of the motion blended into one still. */
@Composable
fun LongExposureLayer(m: Media, motion: MotionStore, active: Boolean) {
    var img by remember(m.id, m.dateModified) { mutableStateOf(EffectCache.longExposure.get(m.id)) }
    LaunchedEffect(m.id, active) {
        if (!active || img != null) return@LaunchedEffect
        val f = motion.video(m)?.first ?: return@LaunchedEffect
        val out = withContext(Dispatchers.Default) { runCatching { longExposure(f) }.getOrNull() } ?: return@LaunchedEffect
        val ib = out.asImageBitmap()
        EffectCache.longExposure.put(m.id, ib)
        img = ib
    }
    val a by animateFloatAsState(if (active && img != null) 1f else 0f, tween(260), label = "lx")
    img?.let { if (a > .01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }) { FitImage(it, m.aspect) } }
}

private fun longExposure(f: File): Bitmap? {
    val s = LiveSession(f)
    try {
        val n = 28
        var sums: IntArray? = null
        var w = 0; var h = 0; var count = 0
        for (i in 0 until n) {
            val b = s.frame(s.durationUs * i / n, 1600) ?: continue
            val sw = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            if (sums == null) { w = sw.width; h = sw.height; sums = IntArray(w * h * 3) }
            if (sw.width != w || sw.height != h) continue
            val px = IntArray(w * h)
            sw.getPixels(px, 0, w, 0, 0, w, h)
            val acc = sums
            for (k in px.indices) {
                val c = px[k]
                acc[k * 3] += (c shr 16) and 255; acc[k * 3 + 1] += (c shr 8) and 255; acc[k * 3 + 2] += c and 255
            }
            count++
        }
        val acc = sums ?: return null
        if (count == 0) return null
        val out = IntArray(w * h)
        for (k in out.indices) out[k] = (0xFF shl 24) or ((acc[k * 3] / count) shl 16) or ((acc[k * 3 + 1] / count) shl 8) or (acc[k * 3 + 2] / count)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    } finally {
        s.release()
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun FitImage(img: ImageBitmap, aspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val iw = img.width.toFloat(); val ih = img.height.toFloat()
        val s = min(size.width / iw, size.height / ih)
        val w = iw * s; val h = ih * s
        drawImage(
            img, IntOffset.Zero, IntSize(img.width, img.height),
            IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
            IntSize(max(1, w.roundToInt()), max(1, h.roundToInt())),
        )
    }
}
