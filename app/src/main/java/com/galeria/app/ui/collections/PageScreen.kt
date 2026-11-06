package com.galeria.app.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.rememberBackdrop
import com.galeria.app.glass.scrollEdge
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassTextButton
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.plural
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.library.EmptyState
import com.galeria.app.ui.library.GridFooter
import com.galeria.app.ui.library.GridHandle
import com.galeria.app.ui.library.PhotoGrid
import com.galeria.app.ui.library.SelectionBar
import com.galeria.app.ui.root.Actions
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.LocalBackdrops
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.root.Page
import com.galeria.app.ui.root.ViewerCtx
import com.galeria.app.ui.settings.SettingsScreen
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

@Composable
fun PageScreen(vm: AppViewModel, page: Page, actions: Actions, handle: GridHandle, onBack: () -> Unit) {
    val p = LocalPalette.current
    val bridge = LocalBridge.current
    val backdrops = LocalBackdrops.current
    val backdrop = rememberBackdrop()
    DisposableEffect(backdrop) {
        backdrops.list.add(backdrop)
        onDispose { backdrops.list.remove(backdrop) }
    }
    val lib by (if (page is Page.Bucket && page.fromHidden) vm.library else vm.visible).collectAsState()
    val settings by vm.settings.collectAsState()
    val trashed by vm.trashed.collectAsState()
    val hidden by vm.hidden.collectAsState()
    val tags by vm.tags.collectAsState()
    val effects by vm.effects.collectAsState()
    val hiddenAlbums by vm.hiddenAlbums.collectAsState()
    val items = remember(page, lib, settings, trashed, hidden, tags, effects) { vm.pageItems(page, lib, settings, trashed, hidden, tags, effects) }
    val locked = page.locked && settings.lockAlbums && page !in vm.unlocked
    var selecting by remember(page) { mutableStateOf(false) }
    var selected by remember(page) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(items) { selected = selected.filter { id -> items.any { it.id == id } }.toSet() }
    BackHandler(enabled = vm.viewer == null) { if (selecting) { selecting = false; selected = emptySet() } else onBack() }

    val title = pageTitle(page)
    val unlockTitle = stringResource(if (page == Page.Hidden) R.string.unlock_hidden else R.string.unlock_deleted)
    val unlockSub = stringResource(R.string.unlock_subtitle)
    fun unlock() = bridge.authenticate(unlockTitle, unlockSub) { vm.unlocked = vm.unlocked + page }
    LaunchedEffect(page, locked) { if (locked) unlock() }

    if (page == Page.Settings) {
        SettingsScreen(vm, backdrop, onBack)
        return
    }
    if (page.isList) {
        SectionListPage(vm, page, backdrop, onBack)
        return
    }
    val overlays = com.galeria.app.ui.components.LocalOverlays.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            when {
                locked -> LockedView { unlock() }
                else -> PhotoGrid(
                    items = items,
                    handle = handle,
                    columns = 3,
                    aspect = false,
                    topPadding = top + 58.dp,
                    bottomPadding = bottom + if (selecting || page == Page.Deleted) 96.dp else 24.dp,
                    selecting = selecting,
                    selected = selected,
                    hiddenId = vm.viewingId,
                    onOpen = { m, r ->
                        if (page == Page.Deleted) deletedSheet(actions, m)
                        else vm.openViewer(items, items.indexOf(m), r, if (page == Page.Hidden) ViewerCtx.Hidden else ViewerCtx.Page)
                    },
                    onToggle = { m -> selected = if (m.id in selected) selected - m.id else selected + m.id },
                    onLongPress = { m -> selecting = true; selected = selected + m.id },
                    daysLeft = if (page == Page.Deleted) ({ m -> daysLeft(m) }) else null,
                    header = {
                        Column {
                            PageHeader(title, page, items)
                            if (page == Page.Hidden && hiddenAlbums.isNotEmpty()) HiddenAlbumsRow(hiddenAlbums, onOpen = { a ->
                                vm.unlocked = vm.unlocked + Page.Hidden
                                vm.push(Page.Bucket(a.id, a.name, fromHidden = true))
                            }, onMenu = { a, r ->
                                overlays.menu(r, com.galeria.app.ui.components.MenuSpec(title = a.name, groups = listOf(listOf(
                                    com.galeria.app.ui.components.MenuItem(ctx.getString(R.string.unhide_album), Sym.eye) {
                                        vm.setAlbumHidden(a.id, false); overlays.toast(ctx.getString(R.string.album_unhidden))
                                    }
                                ))))
                            })
                        }
                    },
                    footer = if (items.isNotEmpty()) ({ GridFooter(items) }) else null,
                )
            }
            if (!locked && items.isEmpty()) {
                val (t, s) = emptyText(page)
                EmptyState(t, s, Modifier.padding(top = top + 140.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(top + 70.dp).scrollEdge(backdrop, true, p.background.copy(alpha = if (p.dark) .55f else .7f), 24f))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(backdrop, Sym.chevronBack, stringResource(R.string.back), onBack)
            Spacer(Modifier.weight(1f))
            if (!locked && items.isNotEmpty()) {
                GlassTextButton(backdrop, stringResource(if (selecting) R.string.cancel else R.string.select), {
                    selecting = !selecting; selected = emptySet()
                })
            }
        }
        if (selecting || (page == Page.Deleted && !locked && items.isNotEmpty())) {
            val chosen = items.filter { it.id in selected }
            Box(Modifier.align(Alignment.BottomCenter)) {
                when (page) {
                    Page.Deleted -> DeletedBar(chosen, items, actions) { selecting = false; selected = emptySet() }
                    Page.Hidden -> SelectionBar(
                        backdrop, chosen,
                        onShare = { bridge.share(chosen) },
                        onDelete = { actions.confirmDeleteForever(chosen) { selecting = false; selected = emptySet() } },
                        extraLabel = stringResource(R.string.unhide),
                        onExtra = { actions.unhide(chosen) { selecting = false; selected = emptySet() } },
                    )
                    else -> SelectionBar(
                        backdrop, chosen,
                        onShare = { bridge.share(chosen) },
                        onDelete = { actions.confirmDelete(chosen) { selecting = false; selected = emptySet() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, page: Page, items: List<Media>) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp)) {
        Text(title, style = Type.largeTitle, color = p.label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page == Page.Deleted) Icon(Sym.trash, null, Modifier.size(15.dp).padding(end = 2.dp), tint = p.label.copy(alpha = .8f))
            Text(plural(R.plurals.items_count, items.size), style = Type.subheadSemibold, color = p.label.copy(alpha = .8f))
        }
    }
}

@Composable
private fun LockedView(onUnlock: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Sym.lock, null, Modifier.size(48.dp), tint = p.tertiaryLabel)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.locked_title), style = Type.title3, color = p.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.view_album), style = Type.body, color = p.blue, modifier = Modifier.pressable(onClick = onUnlock).padding(10.dp))
    }
}

@Composable
private fun DeletedBar(chosen: List<Media>, all: List<Media>, actions: Actions, done: () -> Unit) {
    val p = LocalPalette.current
    val target = if (chosen.isEmpty()) all else chosen
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.delete_permanently), style = Type.body, color = p.red,
            modifier = Modifier.pressable { actions.confirmDeleteForever(target, done) }.padding(8.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.recover), style = Type.body, color = p.blue,
            modifier = Modifier.pressable { actions.recover(target); done() }.padding(8.dp),
        )
    }
}

private fun deletedSheet(actions: Actions, m: Media) = actions.deletedItemSheet(m)

fun daysLeft(m: Media): Int {
    if (m.expiresAt <= 0L) return 30
    val d = ((m.expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    return d.coerceIn(0, 30)
}

@Composable
fun pageTitle(page: Page): String = when (page) {
    is Page.Bucket -> page.name
    Page.Favourites -> stringResource(R.string.favourites)
    Page.Videos -> stringResource(R.string.videos)
    Page.Screenshots -> stringResource(R.string.screenshots)
    Page.ScreenRecordings -> stringResource(R.string.screen_recordings)
    Page.Panoramas -> stringResource(R.string.panoramas)
    Page.RecentlySaved -> stringResource(R.string.recently_saved)
    Page.RecentlyViewed -> stringResource(R.string.recently_viewed)
    Page.Duplicates -> stringResource(R.string.duplicates)
    Page.Hidden -> stringResource(R.string.hidden)
    Page.Deleted -> stringResource(R.string.recently_deleted)
    Page.Settings -> stringResource(R.string.settings)
    Page.AllAlbums -> stringResource(R.string.albums)
    Page.MediaTypesList -> stringResource(R.string.media_types)
    Page.UtilitiesList -> stringResource(R.string.utilities)
    Page.PinnedList -> stringResource(R.string.pinned)
    Page.LivePhotos -> stringResource(R.string.mt_live)
    Page.Portrait -> stringResource(R.string.mt_portrait)
    Page.SlowMo -> stringResource(R.string.mt_slomo)
    Page.TimeLapse -> stringResource(R.string.mt_timelapse)
    Page.Bursts -> stringResource(R.string.mt_bursts)
    Page.Raw -> stringResource(R.string.mt_raw)
    Page.Animated -> stringResource(R.string.mt_animated)
    Page.Selfies -> stringResource(R.string.mt_selfies)
    Page.LongExposure -> stringResource(R.string.mt_long)
    Page.RecentlyEdited -> stringResource(R.string.recently_edited)
}

@Composable
private fun emptyText(page: Page): Pair<String, String?> = when (page) {
    Page.Hidden -> stringResource(R.string.empty_hidden) to stringResource(R.string.empty_hidden_sub)
    Page.Deleted -> stringResource(R.string.empty_deleted) to stringResource(R.string.empty_deleted_sub)
    Page.Duplicates -> stringResource(R.string.empty_dupes) to stringResource(R.string.empty_dupes_sub)
    Page.RecentlyViewed -> stringResource(R.string.empty_viewed) to stringResource(R.string.empty_viewed_sub)
    Page.Favourites -> stringResource(R.string.no_fmt, stringResource(R.string.favourites)) to stringResource(R.string.empty_fav)
    else -> stringResource(R.string.empty_album) to null
}


@Composable
private fun HiddenAlbumsRow(albums: List<com.galeria.app.ui.root.Album>, onOpen: (com.galeria.app.ui.root.Album) -> Unit, onMenu: (com.galeria.app.ui.root.Album, androidx.compose.ui.geometry.Rect) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.hidden_albums), style = Type.title3, color = p.label, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums.size) { i ->
                val a = albums[i]
                Box(Modifier.width(132.dp)) { AlbumCard(a, onOpen = { onOpen(a) }, onMenu = { r -> onMenu(a, r) }) }
            }
        }
    }
}
