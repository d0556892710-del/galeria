package com.galeria.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.galeria.app.data.Media
import com.galeria.app.data.ThumbLoader
import com.galeria.app.data.thumbBucket
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo thumbnail. Draws straight from the memory cache in the first frame when it can,
 * and only redraws (never recomposes) when the picture arrives.
 */
@Composable
fun ThumbImage(m: Media, px: Int, modifier: Modifier = Modifier, fit: Boolean = false) {
    val bucket = thumbBucket(px)
    val key = ThumbLoader.key(m, bucket)
    val first = remember(key) { ThumbLoader.peek(m, bucket) }
    var img by remember(key) { mutableStateOf(first.first) }
    if (!first.second) LaunchedEffect(key) { ThumbLoader.load(m, bucket)?.let { img = it } }
    Spacer(
        modifier.drawBehind {
            val i = img ?: return@drawBehind
            val iw = i.width.toFloat(); val ih = i.height.toFloat()
            if (iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) return@drawBehind
            if (fit) {
                val s = min(size.width / iw, size.height / ih)
                val w = iw * s; val h = ih * s
                drawImage(
                    i, IntOffset.Zero, IntSize(i.width, i.height),
                    IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
                    IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.Low,
                )
            } else {
                val s = max(size.width / iw, size.height / ih)
                val sw = size.width / s; val sh = size.height / s
                drawImage(
                    i,
                    IntOffset(((iw - sw) / 2f).roundToInt().coerceAtLeast(0), ((ih - sh) / 2f).roundToInt().coerceAtLeast(0)),
                    IntSize(sw.roundToInt().coerceAtMost(i.width), sh.roundToInt().coerceAtMost(i.height)),
                    IntOffset.Zero, IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low,
                )
            }
        }
    )
}
