package com.galeria.app.ui.viewer

import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.data.Thumb
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.rememberBackdrop
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.LocalOverlays
import com.galeria.app.ui.components.MenuItem
import com.galeria.app.ui.components.MenuSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.duration
import com.galeria.app.ui.components.fileSize
import com.galeria.app.ui.components.onRootRect
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.components.rememberFmt
import com.galeria.app.ui.library.thumbModel
import com.galeria.app.ui.root.Actions
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.LocalBackdrops
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.root.ViewerCtx
import com.galeria.app.ui.root.ViewerState
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = scale > 1.01f
}

private fun fitRect(m: Media, box: Size): Rect {
    val a = m.aspect.takeIf { it > 0f } ?: 1f
    val w: Float; val h: Float
    if (box.width / box.height > a) {
        h = box.height; w = h * a
    } else {
        w = box.width; h = w / a
    }
    return Rect((box.width - w) / 2f, (box.height - h) / 2f, (box.width + w) / 2f, (box.height + h) / 2f)
}

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)

@Composable
fun Viewer(
    vm: AppViewModel,
    vs: ViewerState,
    actions: Actions,
    rectOf: suspend (Media) -> Rect?,
    onClosed: () -> Unit,
) {
    val p = LocalPalette.current
    val bridge = LocalBridge.current
    val overlays = LocalOverlays.current
    val backdrops = LocalBackdrops.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val lib by vm.library.collectAsState()
    val hiddenList by vm.hidden.collectAsState()

    val items = remember(vs.key, lib, hiddenList) {
        when (vs.ctx) {
            ViewerCtx.External -> vs.list.distinctBy { it.id }
            ViewerCtx.Hidden -> vs.list.mapNotNull { h -> hiddenList.firstOrNull { it.hiddenFile == h.hiddenFile } }.distinctBy { it.id }
            else -> {
                val byId = lib.associateBy { it.id }
                vs.list.mapNotNull { byId[it.id] }.distinctBy { it.id }
            }
        }
    }
    val pager = rememberPagerState(initialPage = vs.start.coerceIn(0, max(0, vs.list.size - 1))) { items.size }
    val cur = items.getOrNull(pager.currentPage.coerceAtMost(items.size - 1).coerceAtLeast(0))
    var chrome by remember { mutableStateOf(true) }
    var infoOpen by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    val zooms = remember(vs.key) { HashMap<Long, ZoomState>() }
    fun zoomOf(m: Media) = zooms.getOrPut(m.id) { ZoomState() }
    val dismiss = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val transition = remember { Animatable(if (vs.origin != null) 0f else 1f) }
    var from by remember { mutableStateOf<Rect?>(vs.origin) }
    var to by remember { mutableStateOf<Rect?>(null) }
    var closing by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(Size.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = rememberBackdrop()
    var moreRect by remember { mutableStateOf(Rect.Zero) }
    val video = rememberVideoController()

    DisposableEffect(backdrop) {
        backdrops.list.add(backdrop)
        onDispose { backdrops.list.remove(backdrop) }
    }
    LaunchedEffect(cur?.id) { vm.viewingId = cur?.id; cur?.let { vm.addViewed(it) } }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClosed() }
    val darkBars = p.dark || !chrome
    LaunchedEffect(darkBars) { bridge.setSystemBars(darkBars) }
    DisposableEffect(Unit) { onDispose { vm.viewingId = null; overlays.closeMenus(); bridge.setSystemBars(p.dark) } }

    // Opening: grow from the tapped cell to the full photo.
    LaunchedEffect(boxSize, vs.key) {
        if (boxSize == Size.Zero || cur == null || vs.origin == null || transition.value >= 1f) return@LaunchedEffect
        val local = vs.origin.translate(-boxOrigin)
        from = local
        to = fitRect(cur, boxSize)
        transition.animateTo(1f, spring(dampingRatio = .86f, stiffness = 360f))
    }

    fun close() {
        if (closing) return
        closing = true
        infoOpen = false
        val m = cur
        scope.launch {
            if (m == null || boxSize == Size.Zero) {
                onClosed(); return@launch
            }
            val zs = zoomOf(m)
            val fit = fitRect(m, boxSize)
            val s = zs.scale * (1f - min(.35f, abs(dismiss.value.y) / (boxSize.height * 1.6f)))
            val c = fit.center + zs.offset + dismiss.value
            val now = Rect(c.x - fit.width * s / 2f, c.y - fit.height * s / 2f, c.x + fit.width * s / 2f, c.y + fit.height * s / 2f)
            val target = rectOf(m)?.translate(-boxOrigin)
            if (target == null) {
                transition.animateTo(0f, tween(200)); onClosed(); return@launch
            }
            from = target; to = now
            transition.snapTo(1f)
            dismiss.snapTo(Offset.Zero)
            transition.animateTo(0f, spring(dampingRatio = .9f, stiffness = 420f))
            onClosed()
        }
    }

    BackHandler(enabled = !closing) {
        when {
            infoOpen -> infoOpen = false
            cur != null && zoomOf(cur).zoomed -> scope.launch { zoomOf(cur).let { it.scale = 1f; it.offset = Offset.Zero } }
            else -> close()
        }
    }

    // Slideshow: advance every 3 s until the end or a tap.
    LaunchedEffect(slideshow) {
        if (!slideshow) return@LaunchedEffect
        chrome = false
        while (slideshow && pager.currentPage < items.size - 1) {
            delay(3000)
            if (slideshow) pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(700))
        }
        slideshow = false
    }

    // Animation values are read only in layout / draw lambdas, so the pager never recomposes per frame.
    val opened by remember { androidx.compose.runtime.derivedStateOf { transition.value >= 1f } }
    fun dismissFade() = 1f - min(1f, abs(dismiss.value.y) / max(1f, boxSize.height * .45f))
    val showPager = !closing && opened
    val chromeAlphaState = animateFloatAsState(if (chrome && !closing && opened && !slideshow) 1f else 0f, tween(200), label = "chrome")
    val chromeShown by remember { androidx.compose.runtime.derivedStateOf { chromeAlphaState.value > .02f } }
    val tags by vm.repo.motion.tags.collectAsState()
    val effects by vm.repo.edits.effects.collectAsState()
    var livePressed by remember { mutableStateOf(false) }
    val ctxRes = LocalContext.current.resources
    val more = com.galeria.app.ui.root.rememberMoreActions(vm, actions)
    val appCtx = LocalContext.current
    var liveTextOn by remember { mutableStateOf(false) }
    // Live Text: read the photo on screen once it settles.
    val textBoxes by androidx.compose.runtime.produceState<List<TextBox>?>(null, cur?.id, cur?.dateModified, showPager) {
        value = null
        val m = cur
        if (m != null && !m.isVideo && showPager) {
            kotlinx.coroutines.delay(450)
            value = runCatching { LiveTextEngine.read(appCtx, m) }.getOrNull()
        }
    }
    LaunchedEffect(cur?.id) { liveTextOn = false }
    val bg = if (!chrome || p.dark) Color.Black else Color.White

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { boxOrigin = it.positionInRoot(); boxSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = transition.value * dismissFade() }.background(bg))

        // ---- pages (also the backdrop for the glass bars) ----
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (showPager) 1f else 0f }.backdropSource(backdrop)) {
            HorizontalPager(
                state = pager,
                key = { items.getOrNull(it)?.id?.toString() ?: "page$it" },
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                userScrollEnabled = cur?.let { !zoomOf(it).zoomed } ?: true,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val m = items.getOrNull(page) ?: return@HorizontalPager
                val zs = zoomOf(m)
                val isCur = page == pager.currentPage
                val live = !m.isVideo && m.id in tags.live
                MediaPage(
                    m = m,
                    zs = zs,
                    active = isCur && showPager,
                    chrome = chrome,
                    dismissOffset = { if (isCur) dismiss.value else Offset.Zero },
                    live = live,
                    effect = effects[m.id] ?: com.galeria.app.data.LiveEffect.Live,
                    livePressed = livePressed && isCur,
                    onLivePress = { livePressed = it },
                    motion = vm.repo.motion,
                    editParams = if (live) remember(m.id, m.dateModified) { com.galeria.app.ui.editor.EditParams.fromJson(vm.repo.edits.params(m.id)) } else null,
                    textBoxes = if (isCur && liveTextOn) textBoxes else null,
                    onCopyText = { t -> bridge.copyText(t); overlays.toast(appCtx.getString(R.string.copied)) },
                    boxSize = boxSize,
                    onTap = { if (slideshow) slideshow = false else chrome = !chrome },
                    onDismissDrag = { d -> scope.launch { dismiss.snapTo(dismiss.value + d) } },
                    video = video,
                    onDismissEnd = { fling ->
                        if (dismiss.value.y > with(density) { 110.dp.toPx() } || (fling && dismiss.value.y > 30f)) close()
                        else scope.launch { dismiss.animateTo(Offset.Zero, spring(dampingRatio = .75f, stiffness = 420f)) }
                    },
                )
            }
        }

        // ---- open / close flight ----
        val f = from; val tt = to
        if (!showPager && f != null && tt != null && cur != null) {
            // Positioned in absolute pixels (not mirrored in Hebrew), animated in the layout phase only.
            com.galeria.app.ui.components.ThumbImage(
                cur, 512,
                Modifier.fillMaxSize().layout { measurable, constraints ->
                    val r = lerpRect(f, tt, transition.value)
                    val pl = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(max(1, r.width.roundToInt()), max(1, r.height.roundToInt())))
                    layout(constraints.maxWidth, constraints.maxHeight) { pl.place(r.left.roundToInt(), r.top.roundToInt()) }
                },
            )
        }

        // ---- top bar ----
        if (cur != null) Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .graphicsLayer { alpha = chromeAlphaState.value * dismissFade() }
        ) {
            if (chromeShown) {
                GlassIconButton(backdrop, Sym.chevronBack, stringResource(R.string.back), { close() }, Modifier.align(Alignment.CenterStart))
                GlassSurface(backdrop, Modifier.align(Alignment.Center).heightIn(min = 44.dp).widthIn(max = 240.dp), RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val title = when {
                            vs.ctx == ViewerCtx.External && cur.id < 0 -> cur.displayName
                            fmt.isToday(cur.dateTaken) -> stringResource(R.string.today)
                            fmt.isYesterday(cur.dateTaken) -> stringResource(R.string.yesterday)
                            else -> fmt.long(cur.dateTaken)
                        }
                        Text(title, style = Type.subheadSemibold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!(vs.ctx == ViewerCtx.External && cur.id < 0)) {
                            Text(fmt.time(cur.dateTaken), style = Type.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = LocalContentColor.current.copy(alpha = .6f))
                        }
                    }
                }
                GlassIconButton(
                    backdrop, Sym.ellipsis, stringResource(R.string.more),
                    { overlays.menu(moreRect, moreMenu(cur, vs.ctx, actions, bridge, overlays, vm, more) { slideshow = true }) },
                    Modifier.align(Alignment.CenterEnd).onRootRect { moreRect = it },
                )
            }
        }

        // ---- Live Text ----
        if (cur != null && !cur.isVideo && !textBoxes.isNullOrEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 16.dp, bottom = 132.dp)
                    .graphicsLayer { alpha = chromeAlphaState.value * dismissFade() }
            ) {
                if (chromeShown) androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    if (liveTextOn) {
                        com.galeria.app.ui.components.GlassTextButton(backdrop, stringResource(R.string.copy_all), {
                            bridge.copyText(textBoxes.orEmpty().joinToString("\n") { it.text }); overlays.toast(appCtx.getString(R.string.copied))
                        })
                        Spacer(Modifier.width(10.dp))
                    }
                    com.galeria.app.ui.components.GlassIconButton(
                        backdrop, Sym.liveText, stringResource(R.string.live_text), { liveTextOn = !liveTextOn },
                        tint = if (liveTextOn) Color.White.copy(alpha = .92f) else null, iconTint = if (liveTextOn) Color.Black else null,
                    )
                }
            }
        }

        // ---- LIVE badge ----
        if (cur != null && !cur.isVideo && cur.id in tags.live) {
            val eff = effects[cur.id] ?: com.galeria.app.data.LiveEffect.Live
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, top = 58.dp)
                    .graphicsLayer { alpha = chromeAlphaState.value * dismissFade() }
            ) {
                if (chromeShown) LiveBadge(backdrop, eff, onMenu = { r ->
                    overlays.menu(r, liveMenu(eff, ctxRes) { e -> vm.repo.edits.setEffect(cur.id, e) })
                })
            }
        }

        // ---- bottom: scrubber strip / video bar + toolbar ----
        if (cur != null) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 10.dp)
                .graphicsLayer { alpha = chromeAlphaState.value * dismissFade(); translationY = if (infoOpen) 400f else 0f }
        ) {
            if (chromeShown) {
                if (cur.isVideo) VideoBar(backdrop, video)
                else if (items.size > 1) Strip(items, pager.currentPage) { i -> scope.launch { pager.scrollToPage(i) } }
                Spacer(Modifier.height(12.dp))
                Toolbar(
                    backdrop = backdrop,
                    m = cur,
                    ctx = vs.ctx,
                    onShare = { bridge.share(listOf(cur)) },
                    onFav = { actions.setFavourite(listOf(cur), !cur.isFavorite) },
                    onInfo = { infoOpen = true },
                    onEdit = { _ -> infoOpen = false; video.stop(); vm.editing = cur },
                    onDelete = {
                        when (vs.ctx) {
                            ViewerCtx.Hidden -> actions.confirmDeleteForever(listOf(cur))
                            else -> actions.confirmDelete(listOf(cur))
                        }
                    },
                )
            }
        }

        // ---- info sheet ----
        if (infoOpen) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { infoOpen = false })
        AnimatedVisibility(
            visible = infoOpen && cur != null,
            enter = slideInVertically(spring(dampingRatio = .85f, stiffness = 380f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            cur?.let { InfoSheet(backdrop, it, actions) { infoOpen = false } }
        }
    }
}


private fun moreMenu(
    m: Media, ctx: ViewerCtx, actions: Actions, bridge: com.galeria.app.ui.root.Bridge,
    overlays: com.galeria.app.ui.components.Overlays, vm: AppViewModel, more: com.galeria.app.ui.root.MoreActions, startSlideshow: () -> Unit,
): MenuSpec {
    val res = vm.getApplication<android.app.Application>().resources
    fun s(id: Int) = res.getString(id)
    val first = buildList {
        add(MenuItem(s(R.string.copy), Sym.docOnDoc) { bridge.copy(m); overlays.toast(s(R.string.copied)) })
        if (ctx != ViewerCtx.Hidden && ctx != ViewerCtx.External) add(MenuItem(s(R.string.duplicate), Sym.duplicate) { actions.duplicate(m) })
        if (ctx == ViewerCtx.Hidden) add(MenuItem(s(R.string.unhide), Sym.eye) { actions.unhide(listOf(m)) })
        else if (ctx != ViewerCtx.External) add(MenuItem(s(R.string.hide), Sym.eyeSlash) { actions.hide(listOf(m)) })
    }
    val library = ctx != ViewerCtx.Hidden && ctx != ViewerCtx.External
    val albums = buildList {
        if (library) add(MenuItem(s(R.string.add_to_album), Sym.photo) { more.addToAlbum(listOf(m)) })
        if (ctx == ViewerCtx.Page || ctx == ViewerCtx.Search) add(MenuItem(s(R.string.show_in_all), Sym.grid) {
            vm.viewer = null; vm.pages = emptyList(); vm.searching = false
            vm.tab = com.galeria.app.ui.root.Tab.Library; vm.zoom = com.galeria.app.ui.root.Zoom.All
            vm.reveal = m.id
        })
    }
    val second = buildList {
        add(MenuItem(s(R.string.slideshow), Sym.playRectangle) { startSlideshow() })
        if (!m.isVideo) add(MenuItem(s(R.string.use_as_wallpaper), Sym.wallpaper, submenu = { more.wallpaperMenu(m) }))
    }
    val info = buildList {
        if (library) {
            add(MenuItem(s(R.string.adjust_date), Sym.clock) { more.adjustDate(listOf(m)) })
            add(MenuItem(s(R.string.adjust_location), Sym.map, submenu = { more.locationMenu(listOf(m)) }))
        }
        if (library && !m.isVideo) {
            if (more.canCopyEdits(m)) add(MenuItem(s(R.string.copy_edits), Sym.sliders) { more.copyEdits(m) })
            if (com.galeria.app.data.MetaClipboard.editsJson != null) add(MenuItem(s(R.string.paste_edits), Sym.sliders) { more.pasteEdits(listOf(m)) })
            if (vm.repo.edits.hasBackup(m)) add(MenuItem(s(R.string.revert_to_original), Sym.arrowDownCircle, destructive = true) { more.revert(m) })
        }
    }
    return MenuSpec(groups = listOf(first, albums, second, info).filter { it.isNotEmpty() })
}

@Composable
private fun MediaPage(
    m: Media,
    zs: ZoomState,
    active: Boolean,
    chrome: Boolean,
    dismissOffset: () -> Offset,
    live: Boolean,
    effect: com.galeria.app.data.LiveEffect,
    livePressed: Boolean,
    onLivePress: (Boolean) -> Unit,
    motion: com.galeria.app.data.MotionStore,
    editParams: com.galeria.app.ui.editor.EditParams?,
    textBoxes: List<TextBox>?,
    onCopyText: (String) -> Unit,
    boxSize: Size,
    onTap: () -> Unit,
    onDismissDrag: (Offset) -> Unit,
    onDismissEnd: (Boolean) -> Unit,
    video: VideoController,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fit = if (boxSize != Size.Zero) fitRect(m, boxSize) else Rect.Zero
    val canPlayLive by androidx.compose.runtime.rememberUpdatedState(live && effect != com.galeria.app.data.LiveEffect.Off)
    val livePressCb by androidx.compose.runtime.rememberUpdatedState(onLivePress)
    fun clamp() {
        val maxX = max(0f, (fit.width * zs.scale - boxSize.width) / 2f)
        val maxY = max(0f, (fit.height * zs.scale - boxSize.height) / 2f)
        zs.offset = Offset(zs.offset.x.coerceIn(-maxX, maxX), zs.offset.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(m.id) {
                detectTapGestures(
                    onPress = {
                        if (canPlayLive) {
                            val job = scope.launch { delay(200); livePressCb(true) }
                            tryAwaitRelease()
                            job.cancel()
                            livePressCb(false)
                        }
                    },
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        scope.launch {
                            val startS = zs.scale; val startO = zs.offset
                            val targetS = if (zs.zoomed) 1f else 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val targetO = if (targetS == 1f) Offset.Zero else (center - pos) * (targetS - 1f)
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = spring(dampingRatio = .85f, stiffness = 380f)) { v, _ ->
                                zs.scale = startS + (targetS - startS) * v
                                zs.offset = startO + (targetO - startO) * v
                                clamp()
                            }
                        }
                    },
                )
            }
            .pointerInput(m.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 undecided, 1 zoom/pan, 2 dismiss, 3 let the pager have it
                    var total = Offset.Zero
                    var lastTime = 0L
                    var vy = 0f
                    val slop = viewConfiguration.touchSlop
                    do {
                        val ev = awaitPointerEvent()
                        val down = ev.changes.count { it.pressed }
                        val z = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        val c = ev.calculateCentroid(useCurrent = true)
                        if (mode == 0) {
                            total += pan
                            mode = when {
                                down >= 2 -> 1
                                zs.zoomed && total.getDistance() > slop -> 1
                                !zs.zoomed && total.y > slop && abs(total.y) > abs(total.x) * 1.25f -> 2
                                !zs.zoomed && abs(total.x) > slop -> 3
                                !zs.zoomed && total.y < -slop -> 3
                                else -> 0
                            }
                        }
                        when (mode) {
                            1 -> {
                                val ns = (zs.scale * z).coerceIn(.8f, 6f)
                                if (c != Offset.Unspecified) {
                                    val rel = c - Offset(size.width / 2f, size.height / 2f)
                                    zs.offset = (zs.offset - rel) * (ns / zs.scale) + rel + pan
                                }
                                zs.scale = ns
                                if (ns >= 1f) clamp()
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            2 -> {
                                onDismissDrag(pan)
                                val now = ev.changes.firstOrNull()?.uptimeMillis ?: 0L
                                if (lastTime > 0 && now > lastTime) vy = pan.y / (now - lastTime)
                                lastTime = now
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    when (mode) {
                        1 -> if (zs.scale < 1f) scope.launch {
                            val s0 = zs.scale; val o0 = zs.offset
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = spring(dampingRatio = .8f, stiffness = 420f)) { v, _ ->
                                zs.scale = s0 + (1f - s0) * v; zs.offset = o0 * (1f - v)
                            }
                        }
                        2 -> onDismissEnd(vy > .6f)
                    }
                }
            }
            .graphicsLayer {
                val d = dismissOffset()
                val dScale = 1f - min(.35f, abs(d.y) / max(1f, boxSize.height * 1.6f))
                scaleX = zs.scale * dScale; scaleY = zs.scale * dScale
                translationX = zs.offset.x + d.x
                translationY = zs.offset.y + d.y
            },
        contentAlignment = Alignment.Center,
    ) {
        if (m.isVideo) VideoSurface(m, video, active)
        else {
            val req = remember(m.uri, m.dateModified) {
                ImageRequest.Builder(ctx)
                    .data(m.uri)
                    .size(2560)
                    .memoryCacheKey("${m.uri}@${m.dateModified}")
                    .crossfade(false)
                    .build()
            }
            // The grid thumbnail shows instantly; the full picture replaces it when decoded.
            com.galeria.app.ui.components.ThumbImage(m, 512, Modifier.fillMaxSize(), fit = true)
            AsyncImage(req, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (live) {
                when (effect) {
                    com.galeria.app.data.LiveEffect.LongExposure -> LongExposureLayer(m, motion, active)
                    com.galeria.app.data.LiveEffect.Bounce -> BounceLayer(m, motion, active)
                    else -> LiveLayer(m, video, motion, playing = livePressed || (effect == com.galeria.app.data.LiveEffect.Loop && active), loop = effect == com.galeria.app.data.LiveEffect.Loop, edit = editParams)
                }
            }
            if (textBoxes != null && fit != Rect.Zero) LiveTextOverlay(textBoxes, fit, onCopyText)
        }
    }
}

@Composable
private fun Toolbar(
    backdrop: com.galeria.app.glass.Backdrop,
    m: Media,
    ctx: ViewerCtx,
    onShare: () -> Unit,
    onFav: () -> Unit,
    onInfo: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    val editMsg = stringResource(R.string.edit_next)
    val canEdit = ctx != ViewerCtx.External && ctx != ViewerCtx.Hidden
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(backdrop, Sym.share, stringResource(R.string.share), onShare, size = 50.dp, iconSize = 23.dp)
        Spacer(Modifier.weight(1f))
        GlassSurface(backdrop, Modifier.height(50.dp), RoundedCornerShape(25.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canEdit) {
                    val favLabel = stringResource(if (m.isFavorite) R.string.unfavourite else R.string.favourite)
                    PillIcon(if (m.isFavorite) Sym.heartFill else Sym.heart, favLabel, if (m.isFavorite) p.red else null, onFav)
                }
                PillIcon(Sym.info, stringResource(R.string.info), null, onInfo)
                if (canEdit) PillIcon(Sym.sliders, stringResource(R.string.edit), null) { onEdit(editMsg) }
            }
        }
        Spacer(Modifier.weight(1f))
        GlassIconButton(
            backdrop, Sym.trash, stringResource(R.string.delete), onDelete, size = 50.dp, iconSize = 23.dp,
            enabled = ctx != ViewerCtx.External || m.id > 0,
        )
    }
}

@Composable
private fun PillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color?, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(54.dp, 50.dp)
            .pressable {
                scope.launch { scale.snapTo(1.25f); scale.animateTo(1f, spring(dampingRatio = .45f, stiffness = 500f)) }
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(23.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }, tint = tint ?: LocalContentColor.current)
    }
}


/** iOS scrubber: tiny thumbnails, the current one wider. Drag it to move through the photos. */
@Composable
private fun Strip(items: List<Media>, current: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val dragged by state.interactionSource.collectIsDraggedAsState()
    BoxWithConstraints(Modifier.fillMaxWidth().height(42.dp)) {
        val half = maxWidth / 2
        LaunchedEffect(current) {
            if (!dragged) state.animateScrollToItem(current)
        }
        LaunchedEffect(state) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect {
                if (state.isScrollInProgress && dragged) {
                    val info = state.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val hit = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }
                    if (hit != null && hit.index != current) onSelect(hit.index)
                }
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = half - 20.dp, end = half - 20.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                val w by animateDpAsState(if (i == current) 40.dp else 24.dp, spring(stiffness = 600f), label = "w")
                Box(
                    Modifier
                        .padding(horizontal = if (i == current) 4.dp else 0.dp)
                        .size(w, if (i == current) 42.dp else 38.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(LocalPalette.current.placeholder)
                        .pressable { onSelect(i) }
                ) {
                    com.galeria.app.ui.components.ThumbImage(m, 96, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun InfoSheet(backdrop: com.galeria.app.glass.Backdrop, m: Media, actions: Actions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val bridge = LocalBridge.current
    val fmt = rememberFmt()
    var exif by remember(m.id) { mutableStateOf<ExifInfo?>(null) }
    LaunchedEffect(m.id) {
        exif = withContext(Dispatchers.IO) { readExif(ctx, m, bridge.hasLocationAccess()) }
    }
    val prefs = remember { actions.captions() }
    var caption by remember(m.id) { mutableStateOf(prefs.getString(m.id.toString(), "") ?: "") }
    GlassSurface(
        backdrop,
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(8.dp)
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null) {},
        RoundedCornerShape(36.dp),
        tint = if (p.dark) Color(0xFF1C1C1E).copy(alpha = .86f) else Color(0xFFF9F9FB).copy(alpha = .88f),
        elevation = 28.dp,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp, 5.dp)
                    .background(p.tertiaryLabel, RoundedCornerShape(3.dp))
                    .pressable(onClick = onClose)
            )
            Spacer(Modifier.height(12.dp))
            if (m.id > 0) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).background(p.fill, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (caption.isEmpty()) Text(stringResource(R.string.add_caption), style = Type.body, color = p.secondaryLabel)
                    BasicTextField(
                        value = caption,
                        onValueChange = { caption = it; prefs.edit().putString(m.id.toString(), it).apply() },
                        textStyle = Type.body.copy(color = p.label),
                        cursorBrush = SolidColor(p.blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            InfoCard {
                Text(
                    "${fmt.weekday(m.dateTaken)} · ${fmt.medium(m.dateTaken)} · ${fmt.time(m.dateTaken)}",
                    style = Type.subheadSemibold, color = p.label,
                )
                Text(m.displayName, style = Type.footnote, color = p.secondaryLabel, modifier = Modifier.padding(top = 3.dp))
            }
            InfoCard {
                val e = exif
                val device = listOfNotNull(e?.make, e?.model).joinToString(" ").trim()
                val format = m.mime.substringAfter('/').uppercase().replace("JPEG", "JPEG").take(5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.ifBlank { stringResource(if (m.isVideo) R.string.video else R.string.photo) }, style = Type.subheadSemibold, color = p.label, modifier = Modifier.weight(1f))
                    if (format.isNotBlank()) Text(
                        format, style = Type.caption2, color = p.secondaryLabel,
                        modifier = Modifier.background(p.fill, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (!e?.lens.isNullOrBlank()) Text(e!!.lens!!, style = Type.footnote, color = p.secondaryLabel, modifier = Modifier.padding(top = 3.dp))
                val mp = if (m.displayWidth > 0) (m.displayWidth.toLong() * m.displayHeight / 1_000_000.0) else 0.0
                val spec = buildList {
                    if (mp >= 1) add("${mp.roundToInt()} MP")
                    if (m.displayWidth > 0) add("${m.displayWidth} × ${m.displayHeight}")
                    if (m.size > 0) add(fileSize(ctx, m.size))
                    if (m.isVideo && m.duration > 0) add(duration(m.duration))
                }.joinToString(" · ") { "\u2066$it\u2069" } // each piece kept whole in Hebrew too
                if (spec.isNotBlank()) Text(spec, style = Type.footnote, color = p.secondaryLabel, modifier = Modifier.padding(top = 3.dp))
                val shot = buildList {
                    e?.iso?.let { add("ISO $it") }
                    e?.focal?.let { add("${it.roundToInt()} mm") }
                    e?.ev?.let { add(if (it == 0.0) "0 ev" else "%+.1f ev".format(it)) }
                    e?.fNumber?.let { add("ƒ${"%.2f".format(it).trimEnd('0').trimEnd('.')}") }
                    e?.exposure?.let { add(if (it >= 1) "${it.roundToInt()} s" else "1/${(1 / it).roundToInt()} s") }
                }
                if (shot.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        shot.forEach { Text(it, style = Type.footnote, color = p.secondaryLabel) }
                    }
                }
            }
            val ll = exif?.latLong
            if (ll != null) InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.map, null, Modifier.size(20.dp), tint = p.secondaryLabel)
                    Text("%.5f, %.5f".format(ll[0], ll[1]), style = Type.subhead, color = p.label, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Text(
                        stringResource(R.string.open_in_maps), style = Type.subheadSemibold, color = p.blue,
                        modifier = Modifier.pressable { bridge.openMaps(ll[0], ll[1]) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .background(p.fill, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

data class ExifInfo(
    val make: String?, val model: String?, val lens: String?, val iso: Int?, val focal: Double?,
    val fNumber: Double?, val exposure: Double?, val ev: Double?, val latLong: DoubleArray?,
)

private fun readExif(ctx: android.content.Context, m: Media, location: Boolean): ExifInfo? {
    if (m.isVideo) return null
    return runCatching {
        val uri = if (location && m.uri.scheme == "content" && m.uri.authority == MediaStore.AUTHORITY) MediaStore.setRequireOriginal(m.uri) else m.uri
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val e = ExifInterface(s)
            fun d(tag: String) = e.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() && it > 0 }
            ExifInfo(
                make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.replaceFirstChar { it.uppercase() },
                model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim(),
                lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim(),
                iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                focal = d(ExifInterface.TAG_FOCAL_LENGTH),
                fNumber = d(ExifInterface.TAG_F_NUMBER),
                exposure = d(ExifInterface.TAG_EXPOSURE_TIME),
                ev = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN).takeIf { !it.isNaN() },
                latLong = e.latLong,
            )
        }
    }.getOrNull()
}
