package com.galeria.app.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galeria.app.R
import com.galeria.app.data.AppSettings
import com.galeria.app.data.LibFilter
import com.galeria.app.data.Media
import com.galeria.app.data.SortMode
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.rememberBackdrop
import com.galeria.app.glass.scrollEdge
import com.galeria.app.ui.collections.CollectionsScreen
import com.galeria.app.ui.collections.PageScreen
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassTextButton
import com.galeria.app.ui.components.LocalOverlays
import com.galeria.app.ui.components.MenuItem
import com.galeria.app.ui.components.MenuSpec
import com.galeria.app.ui.components.OverlayHost
import com.galeria.app.ui.components.Overlays
import com.galeria.app.ui.components.SheetAction
import com.galeria.app.ui.components.SheetSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.onRootRect
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.components.rememberFmt
import com.galeria.app.ui.library.Dock
import com.galeria.app.ui.library.DockMode
import com.galeria.app.ui.library.EmptyState
import com.galeria.app.ui.library.FastScroller
import com.galeria.app.ui.library.GridFooter
import com.galeria.app.ui.library.GridHandle
import com.galeria.app.ui.library.GridLevels
import com.galeria.app.ui.library.LibraryViews
import com.galeria.app.ui.library.PhotoGrid
import com.galeria.app.ui.library.SelectionBar
import com.galeria.app.ui.library.TopBar
import com.galeria.app.ui.library.monthGroups
import com.galeria.app.ui.library.yearGroups
import com.galeria.app.ui.search.SearchScreen
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.PhotosTheme
import com.galeria.app.ui.theme.Type
import com.galeria.app.ui.theme.isDark
import com.galeria.app.ui.viewer.Viewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PhotosRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dark = isDark(settings.theme)
    val bridge = LocalBridge.current
    val viewerOpen = vm.viewer != null
    LaunchedEffect(dark, viewerOpen) { if (!viewerOpen) bridge.setSystemBars(dark) }
    PhotosTheme(dark, settings.glass) {
        val overlays = remember { Overlays() }
        val backdrops = remember { BackdropStack() }
        CompositionLocalProvider(LocalOverlays provides overlays, LocalBackdrops provides backdrops) {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
                if (!vm.permissionGranted) PermissionScreen()
                else MainShell(vm, settings)
                OverlayHost(backdrops.top, overlays)
                // Composed only while something is open, so it is registered last and always wins:
                // the phone's Back closes the menu / sheet first, never the screen under it.
                if (overlays.anyOpen) BackHandler {
                    when {
                        overlays.prompt != null -> overlays.closePrompt()
                        overlays.sheet != null -> overlays.closeSheet()
                        else -> overlays.closeMenus()
                    }
                }
            }
        }
    }
}

@Composable
private fun MainShell(vm: AppViewModel, settings: AppSettings) {
    val p = LocalPalette.current
    val bridge = LocalBridge.current
    val overlays = LocalOverlays.current
    val backdrops = LocalBackdrops.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val fmt = rememberFmt()
    val scope = rememberCoroutineScope()
    val actions = remember(vm, bridge, overlays, ctx) { Actions(vm, bridge, overlays, ctx.resources, scope, ctx) }
    val more = rememberMoreActions(vm, actions)
    val backdrop = rememberBackdrop()
    DisposableEffect(backdrop) {
        backdrops.list.add(0, backdrop)
        onDispose { backdrops.list.remove(backdrop) }
    }

    val allItems by vm.libraryView.collectAsStateWithLifecycle()
    val lib by vm.visible.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val effects by vm.effects.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val picker = vm.picker
    val items = remember(allItems, picker) {
        when {
            picker?.imageOnly == true -> allItems.filter { !it.isVideo }
            picker?.videoOnly == true -> allItems.filter { it.isVideo }
            else -> allItems
        }
    }

    val gridState = rememberLazyGridState()
    val handle = remember { GridHandle(gridState) }
    val searchHandle = remember { GridHandle(LazyGridState()) }
    val pageHandles = remember { HashMap<Page, GridHandle>() }
    val monthsState = rememberLazyListState()
    val yearsState = rememberLazyListState()
    val colState = rememberLazyListState()
    val months = remember(items, fmt) { monthGroups(items, fmt) }
    val years = remember(items, fmt, settings.newestFirst) { yearGroups(items, fmt, settings.newestFirst) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topPad = statusTop + 86.dp
    val bottomPad = navBottom + 100.dp
    val topPx = with(density) { topPad.toPx() }
    val columns = GridLevels[settings.gridLevel.coerceIn(0, GridLevels.lastIndex)]

    // Open at the newest photo: the top when newest-first, the bottom in the iPhone order.
    val nf = settings.newestFirst
    val newestIndex = if (nf) 0 else items.size
    fun atNewest() = if (nf) !gridState.canScrollBackward else !gridState.canScrollForward
    var didInitialScroll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty() && !didInitialScroll) {
            gridState.requestScrollToItem(newestIndex)
            didInitialScroll = true
        }
    }
    var lastOrder by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settings.sort, vm.filter, nf) {
        val now = "${settings.sort}/${vm.filter}/$nf"
        if (lastOrder.isNotEmpty() && lastOrder != now) gridState.requestScrollToItem(if (nf) 0 else items.size)
        lastOrder = now
    }

    // Keep the place when the zoom level changes.
    var levelAnchor by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(columns) {
        val a = levelAnchor ?: return@LaunchedEffect
        levelAnchor = null
        if (a.second) gridState.requestScrollToItem(newestIndex) else gridState.requestScrollToItem(a.first.coerceIn(0, items.size))
    }
    fun changeLevel(d: Int) {
        val nl = (settings.gridLevel + d).coerceIn(0, GridLevels.lastIndex)
        if (nl == settings.gridLevel) return
        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index < items.size && it.offset.y - gridState.layoutInfo.viewportStartOffset + it.size.height > topPx }?.index ?: gridState.firstVisibleItemIndex
        levelAnchor = first to atNewest()
        vm.setGrid(nl, settings.aspectGrid)
    }

    // Years / Months / All keep the same moment in view when switching.
    var zoomAnchor by remember { mutableStateOf<Int?>(null) }
    var zoomAnchorSet by remember { mutableStateOf(false) }
    fun anchorKey(): Int? = when (vm.zoom) {
        Zoom.All -> if (atNewest()) null else gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index < items.size && it.offset.y - gridState.layoutInfo.viewportStartOffset + it.size.height > topPx }?.let { fmt.monthKey(items[it.index].dateTaken) }
        Zoom.Months -> if (if (nf) !monthsState.canScrollBackward else !monthsState.canScrollForward) null else months.getOrNull(monthsState.firstVisibleItemIndex)?.key
        Zoom.Years -> if (if (nf) !yearsState.canScrollBackward else !yearsState.canScrollForward) null else years.getOrNull(yearsState.firstVisibleItemIndex)?.year?.times(12)
    }
    fun zoomTo(z: Zoom, key: Int? = anchorKey()) {
        if (z == vm.zoom) return
        vm.endSelect()
        zoomAnchor = key; zoomAnchorSet = true
        vm.zoom = z
        if (z != Zoom.All) vm.dockMin = true
    }
    LaunchedEffect(vm.zoom) {
        if (!zoomAnchorSet) return@LaunchedEffect
        zoomAnchorSet = false
        val key = zoomAnchor
        when (vm.zoom) {
            Zoom.All -> {
                val i = if (key == null) newestIndex
                else items.indexOfFirst { if (nf) fmt.monthKey(it.dateTaken) <= key else fmt.monthKey(it.dateTaken) >= key }.let { if (it < 0) newestIndex else it }
                gridState.requestScrollToItem(i)
            }
            Zoom.Months -> {
                val newest = if (nf) 0 else months.lastIndex
                val i = if (key == null) newest else months.indexOfFirst { if (nf) it.key <= key else it.key >= key }.let { if (it < 0) newest else it }
                monthsState.requestScrollToItem(i.coerceAtLeast(0))
            }
            Zoom.Years -> {
                val newest = if (nf) 0 else years.lastIndex
                val i = if (key == null) newest else years.indexOfFirst { if (nf) it.year <= key / 12 else it.year >= key / 12 }.let { if (it < 0) newest else it }
                yearsState.requestScrollToItem(i.coerceAtLeast(0))
            }
        }
    }

    // Scrolling into older photos minimises the tab bar to Years / Months / All; scrolling back restores it.
    val nested = remember(vm) {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (vm.tab != Tab.Library || vm.searching || vm.selecting || vm.zoom != Zoom.All || vm.picker != null) return Offset.Zero
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                // The direction that shrinks the tab bar to Years · Months · All is a setting.
                acc = if ((dy > 0) == (acc > 0)) acc + dy else dy
                val down = vm.settings.value.shrinkOnScrollDown
                val shrink = if (down) acc > 60f else acc < -60f
                val grow = if (down) acc < -60f else acc > 60f
                if (shrink && !vm.dockMin) vm.dockMin = true
                else if (grow && vm.dockMin) vm.dockMin = false
                return Offset.Zero
            }
        }
    }
    // Glass follows scrolling even when the content is drawn in its own layers.
    LaunchedEffect(gridState) {
        androidx.compose.runtime.snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.collect { backdrop.invalidate() }
    }

    // Show in All Photos: scroll Library to that photo.
    LaunchedEffect(vm.reveal, items) {
        val id = vm.reveal ?: return@LaunchedEffect
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { gridState.requestScrollToItem(i); vm.reveal = null }
    }

    // A crash last time: offer the report so it can be sent and fixed.
    LaunchedEffect(Unit) {
        val f = java.io.File(ctx.filesDir, com.galeria.app.CRASH_FILE)
        val report = withContext(Dispatchers.IO) { runCatching { if (f.exists()) f.readText().also { f.delete() } else null }.getOrNull() }
        if (!report.isNullOrBlank()) {
            delay(600)
            overlays.sheet(SheetSpec(
                ctx.getString(R.string.crash_title) + "\n\n" + ctx.getString(R.string.crash_body),
                listOf(
                    SheetAction(ctx.getString(R.string.crash_copy)) { bridge.copyText(report); overlays.toast(ctx.getString(R.string.copied)) },
                    SheetAction(ctx.getString(R.string.crash_share)) { bridge.shareText(report) },
                ),
            ))
        }
    }

    // One-time offer to skip the system pop-up for every delete / favourite.
    LaunchedEffect(loaded) {
        if (loaded && !settings.askedManageMedia && !bridge.canManageMedia() && picker == null) {
            delay(900)
            vm.markAskedManage()
            overlays.sheet(SheetSpec(
                ctx.getString(R.string.manage_title) + "\n\n" + ctx.getString(R.string.manage_body),
                listOf(SheetAction(ctx.getString(R.string.continue_btn)) { bridge.requestManageMedia() }),
            ))
        }
    }

    BackHandler(enabled = vm.searching && vm.viewer == null && vm.pages.isEmpty()) { vm.searching = false }
    BackHandler(enabled = vm.selecting && vm.picker == null && vm.viewer == null && vm.pages.isEmpty()) { vm.endSelect() }
    BackHandler(enabled = vm.zoom != Zoom.All && vm.tab == Tab.Library && !vm.searching && vm.viewer == null && vm.pages.isEmpty()) {
        zoomTo(if (vm.zoom == Zoom.Years) Zoom.Months else Zoom.All)
    }
    BackHandler(enabled = vm.tab == Tab.Collections && !vm.searching && vm.viewer == null && vm.pages.isEmpty()) {
        vm.tab = Tab.Library; vm.dockMin = false
    }
    BackHandler(enabled = vm.picker != null && vm.viewer == null && vm.pages.isEmpty()) { bridge.cancelPick() }

    val itemsLabel = ctx.resources.getQuantityString(R.plurals.items_count, items.size, items.size)
    val subtitleState = remember(items, fmt, loaded, itemsLabel, months, topPx, settings.newestFirst) {
        androidx.compose.runtime.derivedStateOf<String?> {
            when {
                vm.searching || vm.tab != Tab.Library -> null
                vm.zoom == Zoom.All -> when {
                    items.isEmpty() -> if (loaded) itemsLabel else null
                    (if (settings.newestFirst) !gridState.canScrollBackward else !gridState.canScrollForward) -> itemsLabel
                    else -> gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index < items.size && it.offset.y - gridState.layoutInfo.viewportStartOffset + it.size.height > topPx * .72f }
                        ?.let { fmt.medium(items[it.index].dateTaken) }
                }
                vm.zoom == Zoom.Months -> months.getOrNull(monthsState.firstVisibleItemIndex)?.let { (it.key / 12).toString() }
                else -> null
            }
        }
    }

    val selectedItems = if (vm.selecting) items.filter { it.id in vm.selected } else emptyList()

    Box(Modifier.fillMaxSize()) {
        // ------------------------------ content (glass backdrop) ------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .background(p.background)
                .nestedScroll(nested)
                .backdropSource(backdrop)
        ) {
            val screen = when {
                vm.searching -> 2
                vm.tab == Tab.Collections && picker == null -> 1
                else -> 0
            }
            Crossfade(screen, animationSpec = tween(180), label = "tab") { s ->
                when (s) {
                    0 -> LibraryViews(
                        zoom = vm.zoom,
                        items = items,
                        grid = {
                            if (items.isEmpty() && loaded) {
                                val f = vm.filter
                                if (f == LibFilter.All) EmptyState(stringResource(R.string.empty_album), null)
                                else EmptyState(stringResource(R.string.no_fmt, filterName(f)), filterEmpty(f))
                            } else PhotoGrid(
                                items = items,
                                handle = handle,
                                columns = columns,
                                onSelectionChange = { vm.selected = it },
                                monthLabels = settings.sort == SortMode.Captured,
                                aspect = settings.aspectGrid,
                                topPadding = topPad,
                                bottomPadding = bottomPad,
                                selecting = vm.selecting,
                                selected = vm.selected,
                                hiddenId = vm.viewingId,
                                onOpen = { m, r ->
                                    if (picker != null && !picker.multiple) bridge.finishPick(listOf(m))
                                    else vm.openViewer(items, items.indexOf(m), r, ViewerCtx.Library)
                                },
                                onToggle = { m -> vm.toggleSelect(m.id) },
                                onLongPress = { m -> if (!vm.selecting) { vm.selecting = true; vm.selected = setOf(m.id) } },
                                onPinch = { d, _ -> changeLevel(d) },
                                footer = { GridFooter(items) },
                                header = if (vm.limitedAccess) ({ LimitedBanner { bridge.requestPermissions() } }) else null,
                            )
                        },
                        monthsState = monthsState,
                        yearsState = yearsState,
                        topPadding = topPad,
                        bottomPadding = bottomPad,
                        onYear = { y -> zoomTo(Zoom.Months, y * 12) },
                        onMonth = { k -> zoomTo(Zoom.All, k) },
                        newestFirst = settings.newestFirst,
                    )
                    1 -> CollectionsScreen(
                        state = colState,
                        library = lib,
                        albums = albums,
                        tags = tags,
                        effects = effects,
                        settings = settings,
                        onAlbumMenu = { a, r -> overlays.menu(r, albumMenu(vm, ctx, overlays, a, settings)) },
                        vm = vm,
                        collapsed = vm.collapsed,
                        onToggle = { id -> vm.collapsed = if (id in vm.collapsed) vm.collapsed - id else vm.collapsed + id },
                        onOpen = { vm.push(it) },
                        topPadding = topPad,
                        bottomPadding = bottomPad,
                    )
                    else -> {
                        val viewed = remember(lib, settings.viewed) { val m = lib.associateBy { it.id }; settings.viewed.distinct().mapNotNull { m[it] } }
                        SearchScreen(
                            library = lib,
                            albums = albums,
                            query = vm.query,
                            recent = settings.recentSearches,
                            viewed = viewed,
                            handle = searchHandle,
                            topPadding = topPad,
                            bottomPadding = bottomPad,
                            onQuery = { vm.query = it },
                            onClearRecent = { vm.clearSearches() },
                            onOpen = { list, m, r -> vm.addSearch(vm.query); vm.openViewer(list, list.indexOf(m), r, ViewerCtx.Search) },
                        )
                    }
                }
            }
        }

        // ------------------------------ scroll edge effects ------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop + 116.dp)
                .scrollEdge(backdrop, true, p.background.copy(alpha = if (p.dark) .5f else .7f), with(density) { 14.dp.toPx() })
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBottom + 92.dp)
                .scrollEdge(backdrop, false, p.background.copy(alpha = if (p.dark) .35f else .38f), with(density) { 5.dp.toPx() })
        )

        // ------------------------------ header ------------------------------
        var filterRect by remember { mutableStateOf(Rect.Zero) }
        var moreRect by remember { mutableStateOf(Rect.Zero) }
        when {
            vm.searching -> TopBar(stringResource(R.string.search), null)
            vm.tab == Tab.Collections && picker == null -> TopBar(stringResource(R.string.tab_collections), null) {
                GlassIconButton(backdrop, Sym.ellipsis, stringResource(R.string.more), {
                    overlays.menu(moreRect, MenuSpec(groups = listOf(listOf(MenuItem(ctx.getString(R.string.settings), Sym.gear) { vm.push(Page.Settings) }))))
                }, Modifier.onRootRect { moreRect = it })
                GlassIconButton(backdrop, Sym.person, stringResource(R.string.settings), { vm.push(Page.Settings) }, iconSize = 24.dp, iconTint = p.secondaryLabel)
            }
            else -> TopBar(if (picker != null) stringResource(R.string.choose_items) else stringResource(R.string.tab_library), { subtitleState.value }) {
                if (picker != null) {
                    GlassTextButton(backdrop, stringResource(R.string.cancel), { bridge.cancelPick() })
                    if (picker.multiple) GlassTextButton(backdrop, stringResource(R.string.add), { bridge.finishPick(selectedItems) }, color = p.blue)
                } else {
                    if (!vm.selecting && vm.zoom == Zoom.All) {
                        val active = vm.filter != LibFilter.All
                        GlassIconButton(
                            backdrop, Sym.filter, stringResource(R.string.filter),
                            { overlays.menu(filterRect, filterMenu(vm, settings, ctx, ::changeLevel)) },
                            Modifier.onRootRect { filterRect = it },
                            tint = if (active) p.blue.copy(alpha = .92f) else null,
                            iconTint = if (active) Color.White else null,
                        )
                    }
                    if (vm.zoom == Zoom.All) GlassTextButton(backdrop, stringResource(if (vm.selecting) R.string.cancel else R.string.select), {
                        if (vm.selecting) vm.endSelect() else vm.selecting = true
                    })
                }
            }
        }

        // ------------------------------ fast scroller ------------------------------
        if (!vm.searching && vm.tab == Tab.Library && vm.zoom == Zoom.All && items.isNotEmpty()) {
            FastScroller(handle, items, columns, backdrop, topPad, bottomPad, onLeft = settings.scrollerOnLeft)
        }

        // ------------------------------ tab bar / selection bar ------------------------------
        val showDock = !vm.selecting && picker == null
        AnimatedVisibility(
            visible = showDock,
            enter = slideInVertically(spring(dampingRatio = .82f, stiffness = 420f)) { it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it * 2 } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Dock(
                backdrop = backdrop,
                mode = when {
                    vm.searching -> DockMode.Search
                    vm.tab == Tab.Library && (vm.dockMin || vm.zoom != Zoom.All) -> DockMode.Compact
                    else -> DockMode.Expanded
                },
                tab = vm.tab,
                zoom = vm.zoom,
                query = vm.query,
                onQuery = { vm.query = it },
                onSubmit = { vm.addSearch(vm.query) },
                onTab = { t ->
                    if (t == vm.tab) {
                        if (t == Tab.Library) {
                            if (vm.zoom != Zoom.All) zoomTo(Zoom.All, null) else scope.launchScrollToBottom(gridState, newestIndex)
                        }
                    } else {
                        vm.endSelect(); vm.tab = t; vm.dockMin = false
                    }
                },
                onExpand = { vm.dockMin = false; if (vm.zoom != Zoom.All) zoomTo(Zoom.All) },
                onZoom = { z -> zoomTo(z) },
                onSearchOpen = { vm.endSelect(); vm.searching = true },
                onSearchClose = { vm.searching = false },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(bottom = 10.dp),
            )
        }
        AnimatedVisibility(
            visible = vm.selecting && picker == null,
            enter = slideInVertically(spring(dampingRatio = .82f, stiffness = 420f)) { it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it * 2 } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                backdrop, selectedItems,
                onShare = { bridge.share(selectedItems) },
                onDelete = { actions.confirmDelete(selectedItems) { vm.endSelect() } },
                onMore = { r -> overlays.menu(r, more.selectionMenu(selectedItems, null) { vm.endSelect() }) },
            )
        }

        // ------------------------------ pushed pages ------------------------------
        vm.pages.forEachIndexed { i, page ->
            key(i, page) {
                val h = pageHandles.getOrPut(page) { GridHandle(LazyGridState()) }
                PushedPage(onPopped = { vm.pop() }) { back ->
                    PageScreen(vm, page, actions, h, onBack = back)
                }
            }
        }

        // ------------------------------ viewer ------------------------------
        vm.viewer?.let { vs ->
            key(vs.key) {
                Viewer(
                    vm = vm,
                    vs = vs,
                    actions = actions,
                    rectOf = { m ->
                        when (vs.ctx) {
                            ViewerCtx.Library -> {
                                if (vm.tab != Tab.Library || vm.zoom != Zoom.All || vm.searching) null
                                else {
                                    if (handle.rectOf(m.id) == null) {
                                        val idx = handle.indexOf(m.id)
                                        if (idx >= 0) {
                                            gridState.scrollToItem(idx, -(gridState.layoutInfo.viewportSize.height / 3))
                                            delay(32)
                                        }
                                    }
                                    handle.rectOf(m.id)
                                }
                            }
                            ViewerCtx.Page, ViewerCtx.Hidden -> vm.pages.lastOrNull()?.let { pageHandles[it]?.rectOf(m.id) }
                            ViewerCtx.Search -> searchHandle.rectOf(m.id)
                            ViewerCtx.External -> null
                        }
                    },
                    onClosed = { vm.viewer = null },
                )
            }
        }

        // ------------------------------ editor ------------------------------
        vm.editing?.let { m ->
            key(m.id) {
                val close = {
                    vm.editing = null
                    bridge.setSystemBars(true)
                }
                if (m.isVideo) com.galeria.app.ui.editor.VideoEditor(vm, m, onClose = close)
                else com.galeria.app.ui.editor.Editor(vm, m, onClose = close)
            }
        }
    }
}

/** Long-press menu of an album: pin; rename / delete for your own albums; hide for folders. */
fun albumMenu(vm: AppViewModel, ctx: android.content.Context, overlays: com.galeria.app.ui.components.Overlays, a: Album, s: AppSettings): MenuSpec {
    val key = "album:${a.id}"
    val pinned = key in s.pinned
    val pin = MenuItem(ctx.getString(if (pinned) R.string.unpin else R.string.pin), Sym.pin) {
        vm.setPinned(if (pinned) s.pinned - key else s.pinned + key)
    }
    if (a.user) return MenuSpec(title = a.name, groups = listOf(
        listOf(pin, MenuItem(ctx.getString(R.string.rename), Sym.sliders) {
            overlays.prompt(com.galeria.app.ui.components.PromptSpec(
                title = ctx.getString(R.string.rename_album), initial = a.name, placeholder = ctx.getString(R.string.album_name), confirm = ctx.getString(R.string.save),
            ) { name -> vm.renameAlbum(a.id, name) })
        }),
        listOf(MenuItem(ctx.getString(R.string.delete_album), Sym.trash, destructive = true) {
            overlays.sheet(SheetSpec(ctx.getString(R.string.delete_album_msg), listOf(SheetAction(ctx.getString(R.string.delete_album), destructive = true) {
                vm.deleteAlbum(a.id)
                if (pinned) vm.setPinned(s.pinned - key)
            })))
        }),
    ))
    return MenuSpec(title = a.name, groups = listOf(
        listOf(pin),
        listOf(MenuItem(ctx.getString(R.string.hide_album), Sym.eyeSlash, destructive = true) {
            overlays.sheet(SheetSpec(ctx.getString(R.string.hide_album_msg), listOf(SheetAction(ctx.getString(R.string.hide_album), destructive = true) {
                vm.setAlbumHidden(a.id, true)
                if (pinned) vm.setPinned(s.pinned - key)
                overlays.toast(ctx.getString(R.string.album_hidden))
            })))
        }),
    ))
}

private fun kotlinx.coroutines.CoroutineScope.launchScrollToBottom(state: LazyGridState, index: Int) {
    launch { state.animateScrollToItem(index) }
}

@Composable
private fun filterName(f: LibFilter): String = stringResource(
    when (f) {
        LibFilter.All -> R.string.f_all
        LibFilter.Favourites -> R.string.f_fav
        LibFilter.Edited -> R.string.f_edited
        LibFilter.Photos -> R.string.f_photos
        LibFilter.Videos -> R.string.f_videos
        LibFilter.Screenshots -> R.string.f_screenshots
    }
)

@Composable
private fun filterEmpty(f: LibFilter): String = stringResource(
    when (f) {
        LibFilter.Favourites -> R.string.empty_fav
        LibFilter.Edited -> R.string.empty_edited
        else -> R.string.empty_filter
    }
)

private fun filterMenu(vm: AppViewModel, s: AppSettings, ctx: android.content.Context, changeLevel: (Int) -> Unit): MenuSpec {
    fun t(id: Int) = ctx.getString(id)
    val filters = listOf(
        Triple(LibFilter.Favourites, R.string.f_fav, Sym.heart),
        Triple(LibFilter.Edited, R.string.f_edited, Sym.sliders),
        Triple(LibFilter.Photos, R.string.f_photos, Sym.photo),
        Triple(LibFilter.Videos, R.string.f_videos, Sym.video),
        Triple(LibFilter.Screenshots, R.string.f_screenshots, Sym.viewfinder),
    )
    return MenuSpec(
        groups = listOf(
            listOf(
                MenuItem(t(R.string.sort_added), checked = s.sort == SortMode.Added) { vm.setSort(SortMode.Added) },
                MenuItem(t(R.string.sort_captured), checked = s.sort == SortMode.Captured) { vm.setSort(SortMode.Captured) },
            ),
            listOf(
                MenuItem(t(R.string.filter), submenu = {
                    MenuSpec(
                        t(R.string.filter),
                        listOf(
                            listOf(MenuItem(t(R.string.f_all), Sym.grid, checked = vm.filter == LibFilter.All) { vm.setLibFilter(LibFilter.All) }),
                            filters.map { (f, id, icon) -> MenuItem(t(id), icon, checked = vm.filter == f) { vm.setLibFilter(f) } },
                        ),
                    )
                }),
                MenuItem(t(R.string.view_options), submenu = {
                    MenuSpec(
                        t(R.string.view_options),
                        listOf(
                            listOf(
                                MenuItem(t(R.string.zoom_in), Sym.zoomIn, enabled = s.gridLevel > 0) { changeLevel(-1) },
                                MenuItem(t(R.string.zoom_out), Sym.zoomOut, enabled = s.gridLevel < GridLevels.lastIndex) { changeLevel(1) },
                            ),
                            listOf(MenuItem(t(R.string.aspect_grid), Sym.aspect, checked = s.aspectGrid) { vm.setGrid(s.gridLevel, !s.aspectGrid) }),
                        ),
                    )
                }),
            ),
        ),
    )
}

/** A screen pushed from the side, like a navigation push on iOS. */
@Composable
private fun PushedPage(onPopped: () -> Unit, content: @Composable (back: () -> Unit) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(state.isIdle, state.currentState) {
        if (state.isIdle && !state.currentState && !state.targetState) onPopped()
    }
    AnimatedVisibility(
        visibleState = state,
        enter = slideInHorizontally(spring(dampingRatio = .9f, stiffness = 380f)) { if (rtl) -it else it },
        exit = slideOutHorizontally(tween(260)) { if (rtl) -it else it },
    ) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
            content { state.targetState = false }
        }
    }
}

@Composable
private fun LimitedBanner(onManage: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(p.fill, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.limited_access), style = Type.footnote, color = p.label, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.manage), style = Type.footnoteSemibold, color = p.blue, modifier = Modifier.pressable(onClick = onManage).padding(start = 10.dp))
    }
}

@Composable
private fun PermissionScreen() {
    val p = LocalPalette.current
    val bridge = LocalBridge.current
    var asked by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(rememberVectorPainter(Sym.flower), null, Modifier.size(96.dp))
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.perm_title), style = Type.title1, color = p.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(if (asked) R.string.perm_denied else R.string.perm_body), style = Type.body, color = p.secondaryLabel, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(p.blue, RoundedCornerShape(26.dp))
                .pressable { asked = true; bridge.requestPermissions() },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.perm_allow), style = Type.headline, color = Color.White) }
        if (asked) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.perm_settings), style = Type.body, color = p.blue, modifier = Modifier.pressable { bridge.openAppSettings() }.padding(8.dp))
        }
    }
}
