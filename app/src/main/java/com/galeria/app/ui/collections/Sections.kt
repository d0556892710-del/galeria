package com.galeria.app.ui.collections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galeria.app.R
import com.galeria.app.data.AppSettings
import com.galeria.app.data.LiveEffect
import com.galeria.app.data.Media
import com.galeria.app.data.MediaTags
import com.galeria.app.glass.Backdrop
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.scrollEdge
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.LocalOverlays
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.ThumbImage
import com.galeria.app.ui.components.onRootRect
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.root.Album
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.Page
import com.galeria.app.ui.root.albumMenu
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

/** One collection shown in a list, tile or pinned card. */
data class Entry(val key: String, val icon: ImageVector, val title: String, val page: Page, val items: List<Media>) {
    val count: Int get() = items.size
    val cover: Media? get() = items.lastOrNull { it.isFavorite } ?: items.maxByOrNull { it.dateTaken }
}

/** iOS order of Media Types; empty kinds are left out (Videos and Screenshots always show). */
@Composable
fun mediaTypeEntries(lib: List<Media>, tags: MediaTags, effects: Map<Long, LiveEffect>): List<Entry> {
    val ctx = LocalContext.current
    return remember(lib, tags, effects) {
        fun e(key: String, icon: ImageVector, title: Int, page: Page, items: List<Media>, always: Boolean = false) =
            if (always || items.isNotEmpty()) Entry(key, icon, ctx.getString(title), page, items) else null
        listOfNotNull(
            e("videos", Sym.video, R.string.videos, Page.Videos, lib.filter { it.isVideo }, always = true),
            e("selfies", Sym.selfie, R.string.mt_selfies, Page.Selfies, lib.filter { it.isSelfie }),
            e("live", Sym.live, R.string.mt_live, Page.LivePhotos, lib.filter { it.id in tags.live }),
            e("portrait", Sym.portrait, R.string.mt_portrait, Page.Portrait, lib.filter { it.id in tags.portrait }),
            e("longexp", Sym.longExposure, R.string.mt_long, Page.LongExposure, lib.filter { effects[it.id] == LiveEffect.LongExposure }),
            e("panos", Sym.panorama, R.string.panoramas, Page.Panoramas, lib.filter { it.isPanorama }),
            e("timelapse", Sym.timelapse, R.string.mt_timelapse, Page.TimeLapse, lib.filter { it.isTimeLapse }),
            e("slomo", Sym.slowmo, R.string.mt_slomo, Page.SlowMo, lib.filter { it.isSlowMo }),
            e("bursts", Sym.burst, R.string.mt_bursts, Page.Bursts, lib.filter { it.isBurst }),
            e("shots", Sym.viewfinder, R.string.screenshots, Page.Screenshots, lib.filter { it.isScreenshot }, always = true),
            e("recs", Sym.record, R.string.screen_recordings, Page.ScreenRecordings, lib.filter { it.isScreenRecording }),
            e("animated", Sym.animated, R.string.mt_animated, Page.Animated, lib.filter { it.isAnimated }),
            e("raw", Sym.raw, R.string.mt_raw, Page.Raw, lib.filter { it.isRaw }),
        )
    }
}

@Composable
fun utilityEntries(vm: AppViewModel, lib: List<Media>, s: AppSettings): List<Entry> {
    val ctx = LocalContext.current
    val trashed by vm.trashed.collectAsState()
    val hidden by vm.hidden.collectAsState()
    return remember(lib, s, trashed, hidden) {
        fun e(key: String, icon: ImageVector, title: Int, page: Page) =
            Entry(key, icon, ctx.getString(title), page, vm.pageItems(page, lib, s, trashed, hidden))
        buildList {
            add(e("fav", Sym.heart, R.string.favourites, Page.Favourites))
            add(e("dupes", Sym.duplicate, R.string.duplicates, Page.Duplicates))
            if (s.showHidden) add(e("hidden", Sym.eyeSlash, R.string.hidden, Page.Hidden))
            add(e("saved", Sym.arrowDownCircle, R.string.recently_saved, Page.RecentlySaved))
            add(e("deleted", Sym.trash, R.string.recently_deleted, Page.Deleted))
            add(e("viewed", Sym.eye, R.string.recently_viewed, Page.RecentlyViewed))
            if (s.edited.isNotEmpty()) add(e("edited", Sym.edited, R.string.recently_edited, Page.RecentlyEdited))
        }
    }
}

/** Everything that can be pinned, by key. */
@Composable
fun pinnableEntries(vm: AppViewModel, lib: List<Media>, albums: List<Album>, tags: MediaTags, effects: Map<Long, LiveEffect>, s: AppSettings): Map<String, Entry> {
    val types = mediaTypeEntries(lib, tags, effects)
    val utils = utilityEntries(vm, lib, s)
    return remember(types, utils, albums, lib) {
        val m = LinkedHashMap<String, Entry>()
        utils.filter { it.key != "hidden" && it.key != "deleted" }.forEach { m[it.key] = it }
        types.forEach { m[it.key] = it }
        albums.forEach { a ->
            val ids = if (a.user) vm.repo.albums.albums.value.firstOrNull { it.id == a.id }?.items?.toHashSet() ?: emptySet() else null
            m["album:${a.id}"] = Entry("album:${a.id}", Sym.folder, a.name, a.page, lib.filter { if (ids != null) it.id in ids else it.bucketId == a.id })
        }
        m
    }
}

/** The pages behind the section titles of Collections. */
@Composable
fun SectionListPage(vm: AppViewModel, page: Page, backdrop: Backdrop, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val overlays = LocalOverlays.current
    val lib by vm.visible.collectAsState()
    val albums by vm.albums.collectAsState()
    val settings by vm.settings.collectAsState()
    val tags by vm.tags.collectAsState()
    val effects by vm.effects.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(
        when (page) {
            Page.AllAlbums -> R.string.albums
            Page.MediaTypesList -> R.string.media_types
            Page.UtilitiesList -> R.string.utilities
            Page.MemoriesList -> R.string.memories
            Page.PeopleList -> R.string.people
            else -> R.string.pinned
        }
    )
    Box(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            when (page) {
                Page.AllAlbums -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 58.dp, bottom = bottom + 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LargeTitle(title) }
                    items(albums, key = { it.key }) { a ->
                        AlbumCard(a, onOpen = { vm.push(a.page) }, onMenu = { r -> overlays.menu(r, albumMenu(vm, ctx, overlays, a, settings)) })
                    }
                }
                Page.MemoriesList -> {
                    val memories by vm.memories.collectAsState()
                    LazyColumn(contentPadding = PaddingValues(top = top + 58.dp, bottom = bottom + 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                        item { LargeTitle(title, Modifier.padding(horizontal = 20.dp)) }
                        items(memories, key = { it.id }) { mem ->
                            Box(Modifier.padding(horizontal = 16.dp)) { MemoryCard(mem, wide = true) { vm.push(Page.Memory(mem.id, mem.title)) } }
                        }
                    }
                }
                Page.PeopleList -> {
                    val people by vm.people.collectAsState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 58.dp, bottom = bottom + 30.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { LargeTitle(title) }
                        items(people, key = { it.id }) { pp ->
                            Box(contentAlignment = Alignment.Center) { PersonBubble(pp, 96.dp) { vm.push(Page.Person(pp.id, pp.name)) } }
                        }
                    }
                }
                Page.PinnedList -> {
                    val all = pinnableEntries(vm, lib, albums, tags, effects, settings)
                    val pinned = settings.pinned.mapNotNull { all[it] }
                    val others = all.values.filter { it.key !in settings.pinned }
                    LazyColumn(contentPadding = PaddingValues(top = top + 58.dp, bottom = bottom + 30.dp), modifier = Modifier.fillMaxSize()) {
                        item { LargeTitle(title, Modifier.padding(horizontal = 20.dp)) }
                        item { Group(pinned) { e -> PinRow(e, true, { vm.push(e.page) }) { vm.setPinned(settings.pinned - e.key) } } }
                        if (others.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.add_to_pinned)) }
                            item { Group(others) { e -> PinRow(e, false, { vm.push(e.page) }) { vm.setPinned(settings.pinned + e.key) } } }
                        }
                    }
                }
                else -> {
                    val entries = if (page == Page.MediaTypesList) mediaTypeEntries(lib, tags, effects) else utilityEntries(vm, lib, settings)
                    LazyColumn(contentPadding = PaddingValues(top = top + 58.dp, bottom = bottom + 30.dp), modifier = Modifier.fillMaxSize()) {
                        item { LargeTitle(title, Modifier.padding(horizontal = 20.dp)) }
                        item {
                            Group(entries) { e ->
                                val locked = settings.lockAlbums && (e.page == Page.Hidden || e.page == Page.Deleted)
                                ListRow(e, locked) { vm.push(e.page) }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(top + 70.dp).scrollEdge(backdrop, true, p.background.copy(alpha = if (p.dark) .55f else .7f), 24f))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(backdrop, Sym.chevronBack, stringResource(R.string.back), onBack)
            if (page == Page.AllAlbums) {
                Spacer(Modifier.weight(1f))
                GlassIconButton(backdrop, Sym.plus, stringResource(R.string.new_album), {
                    overlays.prompt(com.galeria.app.ui.components.PromptSpec(
                        title = ctx.getString(R.string.new_album), message = ctx.getString(R.string.new_album_msg),
                        placeholder = ctx.getString(R.string.album_name), confirm = ctx.getString(R.string.save),
                    ) { name -> vm.createAlbum(name) })
                })
            }
        }
    }
}

@Composable
private fun LargeTitle(t: String, modifier: Modifier = Modifier) {
    Text(t, style = Type.largeTitle, color = LocalPalette.current.label, modifier = modifier.padding(bottom = 14.dp))
}

@Composable
private fun SectionHeader(t: String) {
    Text(t, style = Type.title3, color = LocalPalette.current.label, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp))
}

@Composable
private fun Group(entries: List<Entry>, row: @Composable (Entry) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(p.fill.copy(alpha = p.fill.alpha * .75f))
    ) {
        entries.forEachIndexed { i, e ->
            row(e)
            if (i < entries.lastIndex) Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(.5.dp).background(p.separator))
        }
    }
}

@Composable
private fun ListRow(e: Entry, locked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).pressable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(e.icon, null, Modifier.size(22.dp), tint = p.blue)
        Spacer(Modifier.width(16.dp))
        Text(e.title, style = Type.body, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (locked) Icon(Sym.lock, null, Modifier.size(14.dp), tint = p.secondaryLabel)
        else Text(e.count.toString(), style = Type.body, color = p.secondaryLabel)
        Spacer(Modifier.width(8.dp))
        Icon(Sym.chevronForward, null, Modifier.size(13.dp), tint = p.tertiaryLabel)
    }
}

@Composable
private fun PinRow(e: Entry, pinned: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).pressable(onClick = onOpen).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(if (pinned) p.red else p.green, CircleShape).pressable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { Icon(if (pinned) Sym.minus else Sym.plus, null, Modifier.size(14.dp), tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(p.fill)) {
            val c = e.cover
            if (c != null) ThumbImage(c, 192, Modifier.fillMaxSize()) else Icon(e.icon, null, Modifier.align(Alignment.Center).size(20.dp), tint = p.secondaryLabel)
        }
        Spacer(Modifier.width(12.dp))
        Text(e.title, style = Type.body, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(e.count.toString(), style = Type.body, color = p.secondaryLabel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(a: Album, onOpen: () -> Unit, onMenu: (Rect) -> Unit) {
    val p = LocalPalette.current
    var rect by remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier
            .onRootRect { rect = it }
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = { onMenu(rect) }, onClick = onOpen)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(p.fill)) {
            a.cover?.let { ThumbImage(it, 512, Modifier.fillMaxSize()) }
                ?: Icon(Sym.photo, null, Modifier.align(Alignment.Center).size(34.dp), tint = p.tertiaryLabel)
        }
        Spacer(Modifier.height(7.dp))
        Text(a.name, style = Type.subheadSemibold, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.count.toString(), style = Type.subhead, color = p.secondaryLabel)
    }
}


/** A Memory card: cover, title and dates over a soft shade (iPhone style). */
@Composable
fun MemoryCard(mem: com.galeria.app.data.Memory, wide: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val fmt = com.galeria.app.ui.components.rememberFmt()
    Box(
        Modifier
            .then(if (wide) Modifier.fillMaxWidth().aspectRatio(1.45f) else Modifier.size(232.dp, 160.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(p.fill)
            .pressable(onClick = onClick)
    ) {
        mem.cover?.let { ThumbImage(it, 768, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .55f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(mem.title, style = Type.headline, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val first = mem.items.minByOrNull { it.dateTaken }; val last = mem.items.maxByOrNull { it.dateTaken }
            if (mem.subtitle.isNotBlank()) Text(mem.subtitle, style = Type.footnote, color = Color.White.copy(alpha = .85f))
            else if (first != null && last != null) Text(
                if (fmt.yearOf(first.dateTaken) == fmt.yearOf(last.dateTaken)) fmt.monthYearShort(last.dateTaken) else "${fmt.yearOf(first.dateTaken)}–${fmt.yearOf(last.dateTaken)}",
                style = Type.footnote, color = Color.White.copy(alpha = .85f),
            )
        }
    }
}

/** A person: round face and name. */
@Composable
fun PersonBubble(pp: com.galeria.app.data.PersonInfo, size: androidx.compose.ui.unit.Dp = 84.dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(size + 6.dp).pressable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(p.fill)) {
            val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pp.avatar) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(pp.avatar)?.asImageBitmap() }.getOrNull()
                }
            }
            bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                ?: Icon(Sym.person, null, Modifier.align(Alignment.Center).size(size / 2), tint = p.tertiaryLabel)
        }
        Spacer(Modifier.height(6.dp))
        Text(pp.name.ifBlank { " " }, style = Type.footnote, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
