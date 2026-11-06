package com.galeria.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.galeria.app.data.ThumbLoader
import com.galeria.app.ui.components.ThumbImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.data.Thumb
import com.galeria.app.data.thumbBucket
import com.galeria.app.ui.components.Fmt
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.duration
import com.galeria.app.ui.components.plural
import com.galeria.app.ui.components.rememberFmt
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

/** Zoom levels of All: columns per row. 3 is the default, like iOS. */
val GridLevels = listOf(1, 3, 5, 9, 15)

fun Media.thumbModel(px: Int): Any = if (hiddenFile != null) uri else Thumb(uri, thumbBucket(px), dateModified)

/** Keeps the grid position and answers "where is this photo on screen?" for the viewer animation. */
@Stable
class GridHandle(val state: LazyGridState) {
    var origin by mutableStateOf(Offset.Zero)
    var items: List<Media> = emptyList()
    var headerCount: Int = 0
    fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }.let { if (it < 0) -1 else it + headerCount }
    fun rectOf(id: Long): Rect? {
        val idx = indexOf(id)
        if (idx < 0) return null
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx } ?: return null
        val x = origin.x + info.offset.x
        val y = origin.y + info.offset.y
        return Rect(x, y, x + info.size.width, y + info.size.height)
    }
}

@Composable
fun PhotoGrid(
    items: List<Media>,
    handle: GridHandle,
    columns: Int,
    aspect: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    selecting: Boolean,
    selected: Set<Long>,
    hiddenId: Long?,
    onOpen: (Media, Rect?) -> Unit,
    onToggle: (Media) -> Unit,
    onLongPress: ((Media) -> Unit)? = null,
    onPinch: ((Int, Offset) -> Unit)? = null,
    daysLeft: ((Media) -> Int)? = null,
    footer: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val fmt = rememberFmt()
    val density = LocalDensity.current
    handle.items = items
    handle.headerCount = if (header != null) 1 else 0
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var pinchOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    val pinchCb by rememberUpdatedState(onPinch)
    val gap = when {
        columns >= 9 -> 1.dp
        columns >= 5 -> 1.5.dp
        else -> 2.dp
    }
    val monthLabels = columns >= 5
    val monthStart = remember(items, fmt, monthLabels) {
        if (!monthLabels) BooleanArray(0) else {
            var last = Int.MIN_VALUE
            BooleanArray(items.size) { i -> val k = fmt.monthKey(items[i].dateTaken); val start = k != last; last = k; start }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { handle.origin = it.positionInRoot() }
            .pointerInput(Unit) {
                // Two-finger pinch changes the zoom level; one finger keeps scrolling the grid.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var fired = false
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val down = ev.changes.count { it.pressed }
                        if (down >= 2 && pinchCb != null) {
                            zoom *= ev.calculateZoom()
                            val c = ev.calculateCentroid(useCurrent = true)
                            if (c != Offset.Unspecified && size.width > 0) {
                                pinchOrigin = TransformOrigin(c.x / size.width, c.y / size.height)
                            }
                            ev.changes.forEach { it.consume() }
                            if (!fired) {
                                pinchScale = zoom.coerceIn(.86f, 1.16f)
                                if (zoom > 1.3f) {
                                    fired = true; pinchScale = 1f; pinchCb?.invoke(-1, c)
                                } else if (zoom < .76f) {
                                    fired = true; pinchScale = 1f; pinchCb?.invoke(1, c)
                                }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    pinchScale = 1f
                }
            }
    ) {
        val cellPx = with(density) { (maxWidth / columns).toPx() }.toInt().coerceAtLeast(48)
        val bucket = thumbBucket(cellPx)
        // Warm the cache a few rows ahead (and a couple behind) so new rows appear already filled.
        LaunchedEffect(handle.state, items, bucket, columns) {
            val jobs = HashMap<Long, Job>()
            snapshotFlow {
                val v = handle.state.layoutInfo.visibleItemsInfo
                (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0)
            }.distinctUntilChanged().collect { (first, last) ->
                val hc = handle.headerCount
                val from = (first - hc - columns * 2).coerceAtLeast(0)
                val to = (last - hc + 1 + columns * 4).coerceAtMost(items.size)
                val want = HashSet<Long>(to - from + 1)
                for (i in from until to) want += items[i].id
                val it = jobs.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.value.isCompleted) it.remove() else if (e.key !in want) { e.value.cancel(); it.remove() }
                }
                for (i in from until to) {
                    val m = items[i]
                    if (m.id !in jobs && !ThumbLoader.peek(m, bucket).second) jobs[m.id] = launch { ThumbLoader.load(m, bucket) }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = handle.state,
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pinchScale; scaleY = pinchScale; transformOrigin = pinchOrigin
                },
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = 3) { header() }
            itemsIndexed(items, key = { _, m -> m.id }, contentType = { _, _ -> 1 }) { i, m ->
                val label = monthLabels && monthStart.getOrElse(i) { false }
                GridCell(
                    m = m,
                    px = cellPx,
                    columns = columns,
                    aspect = aspect,
                    fmt = fmt,
                    monthLabel = label,
                    selecting = selecting,
                    isSelected = m.id in selected,
                    invisible = m.id == hiddenId,
                    days = daysLeft?.invoke(m),
                    onClick = {
                        if (selecting) onToggle(m) else onOpen(m, handle.rectOf(m.id))
                    },
                    onLongPress = onLongPress?.let { cb -> { cb(m) } },
                )
            }
            if (footer != null) item(span = { GridItemSpan(maxLineSpan) }, contentType = 2) { footer() }
        }
    }
}

@Composable
private fun GridCell(
    m: Media,
    px: Int,
    columns: Int,
    aspect: Boolean,
    fmt: Fmt,
    monthLabel: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    invisible: Boolean,
    days: Int?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ratio = if (columns == 1) m.aspect.coerceIn(.75f, 1.6f) else 1f
    val small = columns >= 9
    val kind = if (m.isVideo) R.string.video else if (m.isScreenshot) R.string.screenshot else R.string.photo
    val kindText = androidx.compose.ui.res.stringResource(kind)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .then(if (monthLabel) Modifier.zIndex(2f) else Modifier)
            .combinedClickable(interaction, indication = null, onLongClick = onLongPress, onClick = onClick)
            .semantics { contentDescription = kindText + ", " + fmt.medium(m.dateTaken); if (selecting) selected = isSelected }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (aspect && columns > 1) p.background else p.placeholder)
                .graphicsLayer { alpha = if (invisible) 0f else if (pressed) .82f else 1f }
        ) {
            ThumbImage(m, px, Modifier.fillMaxSize(), fit = aspect && columns > 1)
            if (selecting && isSelected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .2f)))
        }
        val shadow = BadgeStyle
        if (!small && m.isVideo && m.duration > 0 && days == null) {
            Text(duration(m.duration), style = shadow, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 4.dp))
        }
        if (!small && m.isFavorite && days == null) {
            Icon(Sym.heartFill, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp).size(13.dp))
        }
        if (days != null) {
            Text(
                plural(R.plurals.days_left, days), style = Type.footnoteSemibold.copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 5f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
            )
        }
        if (selecting && isSelected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .background(p.blue, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.checkmark, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
        }
        if (monthLabel) {
            Text(
                fmt.monthYearShort(m.dateTaken),
                style = Type.caption1Semibold.copy(fontSize = 12.5.sp),
                color = p.label,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .offset(x = 5.dp, y = 5.dp)
                    .wrapContentWidthUnbounded()
                    .shadow(3.dp, RoundedCornerShape(12.dp))
                    .background(if (p.dark) Color(0xE6262628) else Color(0xE6FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

private val BadgeStyle = Type.caption1Semibold.copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 4f))

/** Lets a label overflow the (tiny) cell it is attached to. */
private fun Modifier.wrapContentWidthUnbounded(): Modifier = this.wrapContentWidth(Alignment.Start, unbounded = true)

/** "N Photos, M Videos" under the last row. */
@Composable
fun GridFooter(items: List<Media>, extra: String? = null) {
    val v = items.count { it.isVideo }
    val text = com.galeria.app.ui.components.countLine(items.size - v, v) + (extra?.let { " · $it" } ?: "")
    Text(
        text,
        style = Type.footnoteSemibold,
        color = LocalPalette.current.secondaryLabel,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
    )
}
