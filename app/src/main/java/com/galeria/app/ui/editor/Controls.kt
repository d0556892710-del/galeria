package com.galeria.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val EditYellow = Color(0xFFFFD60A)
val EditGrey = Color(0xFF8E8E93)

/**
 * The iOS slider: a strip of ticks that slides under a fixed centre line. Snaps to 0 with a tick.
 */
@Composable
fun Ruler(
    value: Float,
    min: Float,
    max: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unitsPerTick: Float = .04f,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacing = with(density) { 8.dp.toPx() }
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onValue)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(min, max, unitsPerTick) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f; onStart() },
                        onDragEnd = { onEnd() },
                        onDragCancel = { onEnd() },
                    ) { ch, dx ->
                        ch.consume()
                        val old = cur
                        var nv = (old - dx / spacing * unitsPerTick).coerceIn(min, max)
                        // Sticky zero: crossing it stops there for a moment, like iOS.
                        if (old != 0f && (old > 0f) != (nv > 0f) && min < 0f) { nv = 0f; acc = 0f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        else if (old == 0f && min < 0f) {
                            acc += dx
                            if (abs(acc) < spacing * .9f) return@detectHorizontalDragGestures
                            nv = (old - acc / spacing * unitsPerTick).coerceIn(min, max); acc = 0f
                        }
                        if ((nv == min || nv == max) && nv != old) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        cb(nv)
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 4.dp.toPx()
            val first = (min / unitsPerTick).roundToInt()
            val last = (max / unitsPerTick).roundToInt()
            val sw = 1.3.dp.toPx()
            for (i in first..last) {
                val v = i * unitsPerTick
                val x = cx + (v - value) / unitsPerTick * spacing
                if (x < -4f || x > size.width + 4f) continue
                val major = i % 5 == 0
                val fade = 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f) * .9f
                val h = if (major) 18.dp.toPx() else 11.dp.toPx()
                drawLine(Color.White.copy(alpha = (if (major) .62f else .34f) * fade), Offset(x, cy - h / 2f), Offset(x, cy + h / 2f), sw, StrokeCap.Round)
            }
            if (min < 0f) {
                val zx = cx + (0f - value) / unitsPerTick * spacing
                if (zx in 0f..size.width) drawCircle(Color.White.copy(alpha = .85f), 2.4.dp.toPx(), Offset(zx, cy - 16.dp.toPx()))
            }
            drawLine(if (abs(value) > 1e-4f) EditYellow else Color.White, Offset(cx, cy - 15.dp.toPx()), Offset(cx, cy + 15.dp.toPx()), 2.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** A row that keeps its selected item in the middle and selects whatever is scrolled there. */
@Composable
fun <T> SnapRow(
    items: List<T>,
    selected: Int,
    onSelect: (Int) -> Unit,
    state: LazyListState,
    itemWidth: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (Int, T, Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sel by rememberUpdatedState(selected)
    val cb by rememberUpdatedState(onSelect)
    val centre by remember(state) {
        derivedStateOf {
            val li = state.layoutInfo
            val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2
            li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { centre }.distinctUntilChanged().collect { i ->
            if (state.isScrollInProgress && i != sel) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); cb(i) }
        }
    }
    LaunchedEffect(Unit) { state.scrollToItem(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val side = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)
            LazyRow(
                state = state,
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items) { i, t ->
                    Box(Modifier.size(itemWidth, itemWidth + 8.dp).pressable {
                        cb(i); scope.launch { state.animateScrollToItem(i) }
                    }, contentAlignment = Alignment.Center) { content(i, t, i == selected) }
                }
            }
        }
    }
}

/** Round adjustment button: icon, a yellow ring showing how far it is set, and the value when selected. */
@Composable
fun ToolCircle(tool: Tool, value: Float, selected: Boolean, size: Dp = 48.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f - 2.dp.toPx()
            drawCircle(Color.White.copy(alpha = if (selected) .9f else .28f), r, style = Stroke(if (selected) 1.6.dp.toPx() else 1.2.dp.toPx()))
            if (abs(value) > .004f) {
                val sweep = 360f * abs(value) * (if (value < 0) -1 else 1)
                drawArc(
                    EditYellow, -90f, sweep, false,
                    topLeft = Offset(this.size.width / 2f - r, this.size.height / 2f - r), size = Size(r * 2, r * 2),
                    style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        if (selected && abs(value) > .004f) {
            Text((value * 100).roundToInt().toString(), style = Type.subheadSemibold.copy(fontSize = 15.sp), color = Color.White)
        } else {
            Canvas(Modifier.size(size * .46f)) { drawToolIcon(tool, if (selected) Color.White else Color.White.copy(alpha = .82f)) }
        }
    }
}

fun DrawScope.drawToolIcon(t: Tool, c: Color) {
    val w = size.width; val h = size.height
    val cx = w / 2f; val cy = h / 2f; val r = w * .42f
    val st = Stroke(w * .085f, cap = StrokeCap.Round)
    when (t) {
        Tool.Auto -> {
            drawLine(c, Offset(w * .14f, h * .88f), Offset(w * .66f, h * .36f), w * .1f, StrokeCap.Round)
            star(Offset(w * .78f, h * .22f), w * .2f, c)
            star(Offset(w * .36f, h * .18f), w * .1f, c)
            star(Offset(w * .86f, h * .62f), w * .1f, c)
        }
        Tool.Exposure -> {
            drawCircle(c, r, style = st)
            drawLine(c, Offset(cx - r * .7f, cy + r * .7f), Offset(cx + r * .7f, cy - r * .7f), st.width)
            drawLine(c, Offset(cx - r * .62f, cy - r * .28f), Offset(cx - r * .12f, cy - r * .28f), st.width)
            drawLine(c, Offset(cx - r * .37f, cy - r * .53f), Offset(cx - r * .37f, cy - r * .03f), st.width)
            drawLine(c, Offset(cx + r * .12f, cy + r * .34f), Offset(cx + r * .62f, cy + r * .34f), st.width)
        }
        Tool.Brilliance -> {
            drawCircle(c, r, style = st)
            star(Offset(cx, cy), r * .75f, c)
        }
        Tool.Highlights -> {
            drawCircle(c, r, style = st)
            clipRect(top = 0f, bottom = cy) {
                for (k in 0..3) { val y = cy - r + r * .25f * k + r * .12f; drawLine(c, Offset(0f, y), Offset(w, y), st.width * .8f) }
            }
        }
        Tool.Shadows -> {
            drawCircle(c, r, style = st)
            clipRect(top = cy, bottom = h) { drawCircle(c, r) }
        }
        Tool.Contrast -> {
            drawCircle(c, r, style = st)
            clipRect(left = 0f, right = cx) { drawCircle(c, r) }
        }
        Tool.Brightness -> {
            drawCircle(c, r * .45f, style = st)
            for (k in 0 until 8) {
                val a = k * PI / 4
                drawLine(c, Offset(cx + cos(a).toFloat() * r * .72f, cy + sin(a).toFloat() * r * .72f), Offset(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r), st.width)
            }
        }
        Tool.BlackPoint -> drawCircle(c, r * .88f)
        Tool.Saturation -> {
            val p = drop(cx, cy, r)
            drawPath(p, c, style = st)
            clipRect(left = 0f, right = cx) { drawPath(p, c) }
        }
        Tool.Vibrance -> {
            val p = tri(w, h)
            drawPath(p, c, style = st)
            clipRect(left = 0f, right = cx) { drawPath(p, c) }
        }
        Tool.Warmth -> {
            drawRoundRect(c, Offset(cx - w * .12f, h * .06f), Size(w * .24f, h * .62f), CornerRadius(w * .12f), style = st)
            drawCircle(c, w * .2f, Offset(cx, h * .76f))
            drawLine(c, Offset(cx, h * .35f), Offset(cx, h * .7f), st.width)
        }
        Tool.Tint -> {
            drawLine(c, Offset(w * .18f, h * .82f), Offset(w * .6f, h * .4f), st.width * 1.2f)
            drawRoundRect(c, Offset(w * .52f, h * .1f), Size(w * .36f, h * .28f), CornerRadius(w * .12f))
        }
        Tool.Sharpness -> drawPath(tri(w, h), c)
        Tool.Definition -> {
            drawPath(tri(w, h), c, style = st)
            val inner = Path().apply { moveTo(cx, h * .42f); lineTo(w * .68f, h * .76f); lineTo(w * .32f, h * .76f); close() }
            drawPath(inner, c)
        }
        Tool.Noise -> {
            for (k in 0 until 12) {
                val a = k * PI / 6
                drawCircle(c, w * .045f, Offset(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r))
            }
            drawCircle(c, w * .045f, Offset(cx, cy))
            drawCircle(c, w * .045f, Offset(cx - r * .45f, cy)); drawCircle(c, w * .045f, Offset(cx + r * .45f, cy))
        }
        Tool.Vignette -> {
            drawRoundRect(c, Offset(w * .06f, h * .14f), Size(w * .88f, h * .72f), CornerRadius(w * .14f), style = st)
            drawCircle(c, r * .5f, style = Stroke(st.width))
        }
    }
}

private fun DrawScope.star(o: Offset, s: Float, c: Color) {
    val p = Path().apply {
        moveTo(o.x, o.y - s); quadraticTo(o.x, o.y, o.x + s, o.y); quadraticTo(o.x, o.y, o.x, o.y + s)
        quadraticTo(o.x, o.y, o.x - s, o.y); quadraticTo(o.x, o.y, o.x, o.y - s); close()
    }
    drawPath(p, c)
}

private fun drop(cx: Float, cy: Float, r: Float) = Path().apply {
    moveTo(cx, cy - r)
    cubicTo(cx + r * .2f, cy - r * .55f, cx + r * .82f, cy - r * .05f, cx + r * .82f, cy + r * .32f)
    cubicTo(cx + r * .82f, cy + r * .8f, cx + r * .4f, cy + r, cx, cy + r)
    cubicTo(cx - r * .4f, cy + r, cx - r * .82f, cy + r * .8f, cx - r * .82f, cy + r * .32f)
    cubicTo(cx - r * .82f, cy - r * .05f, cx - r * .2f, cy - r * .55f, cx, cy - r)
    close()
}

private fun tri(w: Float, h: Float) = Path().apply { moveTo(w / 2f, h * .1f); lineTo(w * .94f, h * .88f); lineTo(w * .06f, h * .88f); close() }

/** Live · Adjust · Filters · Crop in a Liquid Glass capsule. */
@Composable
fun ModeTabs(backdrop: Backdrop, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(backdrop, modifier.height(46.dp), RoundedCornerShape(23.dp)) {
        Row(Modifier.fillMaxHeight().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(19.dp))
                        .background(if (on) Color.White.copy(alpha = .2f) else Color.Transparent)
                        .pressable { onSelect(i) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(l, style = Type.subheadSemibold.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold), color = if (on) Color.White else Color.White.copy(alpha = .62f))
                }
            }
        }
    }
}

/** Small capsule label like "EXPOSURE". */
@Composable
fun ToolLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.caption1Semibold.copy(fontSize = 12.sp, letterSpacing = 1.sp), color = EditGrey, modifier = modifier)
}
