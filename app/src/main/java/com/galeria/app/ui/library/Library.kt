package com.galeria.app.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.Fmt
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.plural
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.components.rememberFmt
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import com.galeria.app.ui.root.Zoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Large title + subtitle on the leading side, glass buttons on the trailing side. */
@Composable
fun TopBar(title: String, subtitle: String?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) =
    TopBar(title, { subtitle }, modifier, actions)

@Composable
fun TopBar(title: String, subtitle: () -> String?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            Text(title, style = Type.largeTitle, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SubtitleText(subtitle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
private fun SubtitleText(subtitle: () -> String?) {
    val s = subtitle()
    if (!s.isNullOrEmpty()) Text(s, style = Type.subheadSemibold, color = LocalPalette.current.label.copy(alpha = .82f), maxLines = 1)
}

data class MonthGroup(val key: Int, val items: List<Media>, val cover: Media)
data class YearGroup(val year: Int, val items: List<Media>, val cover: Media)

fun coverOf(list: List<Media>): Media = list.lastOrNull { it.isFavorite } ?: list[list.size / 2]


/**
 * One card per month. Grouped by month (not by runs), so a month can never appear twice even when
 * photos are sorted by date added, or arrive slightly out of order — a repeated month would be a
 * duplicate list key, which crashes Compose.
 */
fun monthGroups(items: List<Media>, fmt: Fmt): List<MonthGroup> {
    if (items.isEmpty()) return emptyList()
    val newestFirst = items.first().dateTaken > items.last().dateTaken
    val map = LinkedHashMap<Int, ArrayList<Media>>()
    for (m in items) map.getOrPut(fmt.monthKey(m.dateTaken)) { ArrayList() }.add(m)
    val keys = map.keys.sorted().let { if (newestFirst) it.asReversed() else it }
    return keys.map { k -> val l = map.getValue(k); MonthGroup(k, l, coverOf(l)) }
}

fun yearGroups(items: List<Media>, fmt: Fmt, newestFirst: Boolean = false): List<YearGroup> =
    items.groupBy { fmt.yearOf(it.dateTaken) }.let { if (newestFirst) it.toSortedMap(compareByDescending<Int> { y -> y }) else it.toSortedMap() }
        .map { (y, l) -> YearGroup(y, l, coverOf(l)) }

/** Years / Months / All, cross-fading and zooming between each other like iOS. */
@Composable
fun LibraryViews(
    zoom: Zoom,
    items: List<Media>,
    grid: @Composable () -> Unit,
    monthsState: LazyListState,
    yearsState: LazyListState,
    topPadding: Dp,
    bottomPadding: Dp,
    onYear: (Int) -> Unit,
    onMonth: (Int) -> Unit,
    newestFirst: Boolean = false,
) {
    val fmt = rememberFmt()
    val months = remember(items, fmt) { monthGroups(items, fmt) }
    val years = remember(items, fmt, newestFirst) { yearGroups(items, fmt, newestFirst) }
    AnimatedContent(
        targetState = zoom,
        transitionSpec = {
            val inward = targetState.ordinal > initialState.ordinal
            (fadeIn(tween(240)) + scaleIn(tween(320), initialScale = if (inward) .92f else 1.06f)) togetherWith
                (fadeOut(tween(200)) + scaleOut(tween(320), targetScale = if (inward) 1.06f else .94f))
        },
        label = "zoom",
    ) { z ->
        when (z) {
            Zoom.All -> grid()
            Zoom.Months -> LazyColumn(
                state = monthsState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(months, key = { it.key }) { g -> MonthCard(g, fmt) { onMonth(g.key) } }
            }
            Zoom.Years -> LazyColumn(
                state = yearsState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(years, key = { it.year }) { g -> YearCard(g) { onYear(g.year) } }
            }
        }
    }
}

private val shadowText = Shadow(Color.Black.copy(alpha = .3f), androidx.compose.ui.geometry.Offset(0f, 1f), 8f)

@Composable
private fun MonthCard(g: MonthGroup, fmt: Fmt, onClick: () -> Unit) {
    val p = LocalPalette.current
    val (month, year) = fmt.yearMonthText(g.key)
    val first = fmt.dayNumber(g.items.first().dateTaken)
    val last = fmt.dayNumber(g.items.last().dateTaken)
    val range = if (first == last) "${fmt.monthShort(g.key % 12 + 1)} $first" else "${fmt.monthShort(g.key % 12 + 1)} $first – $last"
    Box(
        Modifier
            .fillMaxWidth()
            .height(232.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(p.placeholder)
            .pressable(onClick = onClick)
    ) {
        com.galeria.app.ui.components.ThumbImage(g.cover, 768, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(cardShade))
        Column(Modifier.padding(start = 18.dp, top = 14.dp)) {
            Text(month, style = Type.title1.copy(fontSize = Type.title1.fontSize * .93f, shadow = shadowText), color = Color.White)
            Text(year, style = Type.headline.copy(shadow = shadowText), color = Color.White.copy(alpha = .92f))
        }
        Text(
            "$range · ${plural(R.plurals.items_count, g.items.size)}",
            style = Type.footnoteSemibold.copy(shadow = shadowText), color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun YearCard(g: YearGroup, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.02f)
            .clip(RoundedCornerShape(28.dp))
            .background(p.placeholder)
            .pressable(onClick = onClick)
    ) {
        com.galeria.app.ui.components.ThumbImage(g.cover, 768, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(cardShade))
        Text(
            g.year.toString(), style = Type.largeTitle.copy(shadow = shadowText), color = Color.White,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        )
    }
}

private val cardShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = .36f), .36f to Color.Transparent, .62f to Color.Transparent, 1f to Color.Black.copy(alpha = .34f),
)

/** The ↑↓ handle on the side: appears while scrolling, drag it to fly through the library. */
@Composable
fun FastScroller(
    handle: GridHandle,
    items: List<Media>,
    columns: Int,
    backdrop: Backdrop?,
    topPadding: Dp,
    bottomPadding: Dp,
    onLeft: Boolean = false,
) {
    val state = handle.state
    if (items.size < columns * 24) return
    val fmt = rememberFmt()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else {
            delay(1400); visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 150 else 350), label = "fs")
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val thumbH = 58.dp
    val totalRows = (items.size + columns - 1) / columns

    /** Row height and how many rows fit, from the current layout. */
    fun metrics(): Pair<Float, Float> {
        val li = state.layoutInfo
        val cell = li.visibleItemsInfo.firstOrNull { it.index >= handle.headerCount }
        val rowH = (cell?.size?.height ?: 1).coerceAtLeast(1).toFloat()
        val viewport = (li.viewportEndOffset - li.viewportStartOffset).toFloat()
        return rowH to (viewport / rowH)
    }

    // Exact position including the part of a row that is scrolled off, so the thumb glides.
    // Read in the layout phase only, so scrolling never recomposes this.
    fun scrollFraction(): Float {
        val (rowH, visibleRows) = metrics()
        val idx = state.firstVisibleItemIndex - handle.headerCount
        val pos = if (idx < 0) 0f else idx / columns + state.firstVisibleItemScrollOffset / rowH
        val range = (totalRows - visibleRows).coerceAtLeast(1f)
        return (pos / range).coerceIn(0f, 1f)
    }

    fun scrollTo(f: Float) {
        val (rowH, visibleRows) = metrics()
        val row = f * (totalRows - visibleRows).coerceAtLeast(0f)
        val whole = row.toInt().coerceIn(0, (totalRows - 1).coerceAtLeast(0))
        val index = (whole * columns + handle.headerCount).coerceIn(0, items.size - 1 + handle.headerCount)
        // One request per frame at most; Compose applies it in the next layout pass.
        state.requestScrollToItem(index, ((row - whole) * rowH).roundToInt())
    }

    val side = if (onLeft) androidx.compose.ui.AbsoluteAlignment.TopLeft else androidx.compose.ui.AbsoluteAlignment.TopRight
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding)) {
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val bubbleText by remember(items, fmt) {
            derivedStateOf {
                if (!dragging || items.isEmpty()) null
                else fmt.monthYearShort(items[((items.size - 1) * dragFraction).roundToInt().coerceIn(0, items.size - 1)].dateTaken)
            }
        }
        val bubble = bubbleText
        if (bubble != null) {
            GlassSurface(
                backdrop,
                Modifier
                    .align(side)
                    .offset { IntOffset(0, (dragFraction * trackPx).roundToInt() + 9.dp.roundToPx()) }
                    .then(if (onLeft) Modifier.absolutePadding(left = 58.dp) else Modifier.absolutePadding(right = 58.dp))
                    .heightIn(min = 40.dp),
                RoundedCornerShape(20.dp),
            ) {
                Text(bubble, style = Type.headline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        val shown = alpha > .05f
        GlassSurface(
            backdrop,
            Modifier
                .align(side)
                .offset { IntOffset(0, ((if (dragging) dragFraction else scrollFraction()) * trackPx).roundToInt()) }
                .then(if (onLeft) Modifier.absolutePadding(left = 6.dp) else Modifier.absolutePadding(right = 6.dp))
                .size(40.dp, thumbH)
                .graphicsLayer {
                    this.alpha = alpha
                    val s = if (dragging) 1.08f else 1f
                    scaleX = s; scaleY = s
                }
                .then(
                    if (shown) Modifier.pointerInput(items.size, columns) {
                        awaitEachGesture {
                            // Grab immediately (no slop), follow the finger 1:1.
                            val down = awaitFirstDown()
                            down.consume()
                            dragging = true
                            dragFraction = scrollFraction()
                            var last = down.position.y
                            do {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val dy = c.position.y - last
                                last = c.position.y
                                if (dy != 0f) {
                                    dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                                    scrollTo(dragFraction)
                                }
                                c.consume()
                            } while (c.pressed)
                            dragging = false
                        }
                    } else Modifier
                ),
            RoundedCornerShape(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Icon(Sym.chevronUp, stringResource(R.string.fast_scroll), Modifier.size(15.dp))
                Icon(Sym.chevronDown, null, Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Type.title2, color = p.label, textAlign = TextAlign.Center)
        if (message != null) Text(
            message, style = Type.subhead, color = p.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
