package com.galeria.app.ui.root

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galeria.app.PhotosApplication
import com.galeria.app.data.AppSettings
import com.galeria.app.data.GlassMode
import com.galeria.app.data.LibFilter
import com.galeria.app.data.LiveEffect
import com.galeria.app.data.MediaTags
import com.galeria.app.data.Media
import com.galeria.app.data.SortMode
import com.galeria.app.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Tab { Library, Collections }
enum class Zoom { Years, Months, All }

data class Album(val id: Long, val name: String, val count: Int, val cover: Media?, val latest: Long, val user: Boolean = false) {
    /** Unique across folder albums and your own albums. */
    val key: String get() = (if (user) "u" else "b") + id
    val page: Page get() = if (user) Page.UserAlbum(id, name) else Page.Bucket(id, name)
}

/** A screen pushed on top of the tabs (album, utility, media type). */
sealed class Page(val locked: Boolean = false) {
    data class Bucket(val id: Long, val name: String, val fromHidden: Boolean = false) : Page()
    data class UserAlbum(val id: Long, val name: String) : Page()
    data object Favourites : Page()
    data object Videos : Page()
    data object Screenshots : Page()
    data object ScreenRecordings : Page()
    data object Panoramas : Page()
    data object RecentlySaved : Page()
    data object RecentlyViewed : Page()
    data object Duplicates : Page()
    data object Hidden : Page(locked = true)
    data object Deleted : Page(locked = true)
    data object Settings : Page()
    // Collections section pages
    data object AllAlbums : Page()
    data object MediaTypesList : Page()
    data object UtilitiesList : Page()
    data object PinnedList : Page()
    // More media types and utilities
    data object LivePhotos : Page()
    data object Portrait : Page()
    data object SlowMo : Page()
    data object TimeLapse : Page()
    data object Bursts : Page()
    data object Raw : Page()
    data object Animated : Page()
    data object Selfies : Page()
    data object LongExposure : Page()
    data object RecentlyEdited : Page()

    /** Pages that show a list of collections rather than a photo grid. */
    val isList: Boolean get() = this == AllAlbums || this == MediaTypesList || this == UtilitiesList || this == PinnedList
}

enum class ViewerCtx { Library, Page, Search, External, Hidden }

data class ViewerState(
    val list: List<Media>,
    val start: Int,
    val origin: Rect?,
    val ctx: ViewerCtx,
    val key: Long = System.nanoTime(),
)

data class PickerState(val multiple: Boolean, val videoOnly: Boolean, val imageOnly: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val pa = app as PhotosApplication
    val repo = pa.repo
    private val store = pa.settings

    val settings: StateFlow<AppSettings> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val library: StateFlow<List<Media>> = repo.library
    val trashed: StateFlow<List<Media>> = repo.trashed
    val hidden: StateFlow<List<Media>> = repo.hidden.items
    val loaded: StateFlow<Boolean> = repo.loaded
    val tags: StateFlow<MediaTags> = repo.motion.tags
    val effects: StateFlow<Map<Long, LiveEffect>> = repo.edits.effects

    /** The library without albums the user hid. */
    val visible: StateFlow<List<Media>> = combine(repo.library, settings) { lib, s ->
        if (s.hiddenAlbums.isEmpty()) lib else lib.filter { it.bucketId !in s.hiddenAlbums }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- UI state (kept across language / theme changes) ----------
    var tab by mutableStateOf(Tab.Library)
    var zoom by mutableStateOf(Zoom.All)
    var dockMin by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var filter by mutableStateOf(LibFilter.All)
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf<Set<Long>>(emptySet())
    var viewer by mutableStateOf<ViewerState?>(null)
    var pages by mutableStateOf<List<Page>>(emptyList())
    var unlocked by mutableStateOf<Set<Page>>(emptySet())
    var picker by mutableStateOf<PickerState?>(null)
    var collapsed by mutableStateOf<Set<String>>(emptySet())
    var permissionGranted by mutableStateOf(false)
    var limitedAccess by mutableStateOf(false)
    /** The photo open in the editor, above the viewer. */
    var editing by mutableStateOf<Media?>(null)
    /** Ids hidden from the grid while the viewer animates over them. */
    var viewingId by mutableStateOf<Long?>(null)

    private val filterState = kotlinx.coroutines.flow.MutableStateFlow(LibFilter.All)

    /** The library as shown in All: filtered and sorted. */
    val libraryView: StateFlow<List<Media>> = combine(visible, settings, filterState) { lib, s, f ->
        val list = lib.filter { m ->
            when (f) {
                LibFilter.All -> true
                LibFilter.Favourites -> m.isFavorite
                LibFilter.Edited -> m.id in s.edited
                LibFilter.Photos -> !m.isVideo
                LibFilter.Videos -> m.isVideo
                LibFilter.Screenshots -> m.isScreenshot
            }
        }
        val sorted = if (s.sort == SortMode.Added) list.sortedWith(compareBy<Media> { it.dateAdded }.thenBy { it.id }) else list
        (if (s.newestFirst) sorted.asReversed() else sorted).distinctBy { it.id }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allAlbums: StateFlow<List<Album>> = repo.library.map { lib ->
        lib.groupBy { it.bucketId }.map { (id, items) ->
            val latest = items.maxBy { it.dateTaken }
            Album(id, latest.bucketName.ifBlank { "—" }, items.size, latest, latest.dateTaken)
        }.sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.latest })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Your own albums (newest first), then the device's folders, like iOS. */
    val albums: StateFlow<List<Album>> = combine(allAlbums, settings, repo.albums.albums, visible) { a, s, mine, lib ->
        val byId = lib.associateBy { it.id }
        val own = mine.map { ua ->
            val items = ua.items.mapNotNull { byId[it] }
            val latest = items.maxByOrNull { it.dateTaken }
            Album(ua.id, ua.name, items.size, latest, latest?.dateTaken ?: ua.created, user = true)
        }
        own + a.filter { it.id !in s.hiddenAlbums }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A photo to scroll to in Library (Show in All Photos). */
    var reveal by mutableStateOf<Long?>(null)

    fun createAlbum(name: String, ids: List<Long> = emptyList()) = repo.albums.create(name, ids)
    fun addToAlbum(albumId: Long, ids: List<Long>) = repo.albums.add(albumId, ids)
    fun removeFromAlbum(albumId: Long, ids: Collection<Long>) = repo.albums.remove(albumId, ids)
    fun renameAlbum(albumId: Long, name: String) = repo.albums.rename(albumId, name)
    fun deleteAlbum(albumId: Long) = repo.albums.delete(albumId)
    val hiddenAlbums: StateFlow<List<Album>> = combine(allAlbums, settings) { a, s -> a.filter { it.id in s.hiddenAlbums } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setLibFilter(f: LibFilter) {
        filter = f; filterState.value = f
    }

    fun start() = repo.start()
    fun refresh() = repo.refresh(0)

    // ---------- settings ----------
    fun setTheme(t: ThemeMode) = viewModelScope.launch { store.setTheme(t) }
    fun setGlass(g: GlassMode) = viewModelScope.launch { store.setGlass(g) }
    fun setLock(v: Boolean) = viewModelScope.launch { store.setLock(v) }
    fun setShowHidden(v: Boolean) = viewModelScope.launch { store.setShowHidden(v) }
    fun setGrid(level: Int, aspect: Boolean) = viewModelScope.launch { store.setGrid(level, aspect) }
    fun setSort(s: SortMode) = viewModelScope.launch { store.setSort(s) }
    fun markAskedManage() = viewModelScope.launch { store.setAsked() }
    fun addSearch(q: String) = viewModelScope.launch { if (q.isNotBlank()) store.addSearch(q.trim()) }
    fun clearSearches() = viewModelScope.launch { store.clearSearches() }
    fun setEdited(id: Long, on: Boolean) = viewModelScope.launch { store.setEdited(id, on) }
    fun setNewestFirst(v: Boolean) = viewModelScope.launch { store.setNewestFirst(v) }
    fun setShrinkDown(v: Boolean) = viewModelScope.launch { store.setShrinkDown(v) }
    fun setScrollerLeft(v: Boolean) = viewModelScope.launch { store.setScrollerLeft(v) }
    fun setAlbumHidden(id: Long, hidden: Boolean) = viewModelScope.launch { store.setAlbumHidden(id, hidden) }
    fun setPinned(keys: List<String>) = viewModelScope.launch { store.setPinned(keys) }
    fun addViewed(m: Media) = viewModelScope.launch { if (m.id > 0) store.addViewed(m.id) }

    // ---------- navigation ----------
    fun push(p: Page) {
        pages = pages + p
    }
    fun pop() {
        if (pages.isNotEmpty()) pages = pages.dropLast(1)
    }
    fun lockAll() {
        unlocked = emptySet()
    }

    fun toggleSelect(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }
    fun endSelect() {
        selecting = false; selected = emptySet()
    }

    fun openViewer(list: List<Media>, index: Int, origin: Rect?, ctx: ViewerCtx) {
        if (list.isEmpty() || index !in list.indices) return
        viewer = ViewerState(list, index, origin, ctx)
        addViewed(list[index])
    }

    fun openExternal(m: Media) {
        val lib = library.value
        val i = lib.indexOfFirst { it.id == m.id }
        viewer = if (i >= 0) ViewerState(lib, i, null, ViewerCtx.Library) else ViewerState(listOf(m), 0, null, ViewerCtx.External)
    }

    suspend fun describe(uri: Uri, mime: String?) = repo.describe(uri, mime)

    // ---------- derived collections ----------
    fun pageItems(
        p: Page, lib: List<Media>, s: AppSettings, trashed: List<Media>, hidden: List<Media>,
        tags: MediaTags = MediaTags(), effects: Map<Long, LiveEffect> = emptyMap(),
    ): List<Media> {
        val raw = rawItems(p, lib, s, trashed, hidden, tags, effects)
        val chronological = p != Page.RecentlyViewed && p != Page.Deleted && p != Page.Hidden
        // Unique ids always: a repeated id is a duplicate list key, which crashes the grid.
        return (if (chronological && s.newestFirst) raw.asReversed() else raw).distinctBy { it.id }
    }

    private fun rawItems(p: Page, lib: List<Media>, s: AppSettings, trashed: List<Media>, hidden: List<Media>, tags: MediaTags, effects: Map<Long, LiveEffect>): List<Media> = when (p) {
        is Page.Bucket -> lib.filter { it.bucketId == p.id }
        is Page.UserAlbum -> {
            val ids = repo.albums.albums.value.firstOrNull { it.id == p.id }?.items?.toHashSet() ?: emptySet()
            lib.filter { it.id in ids }
        }
        Page.LivePhotos -> lib.filter { it.id in tags.live }
        Page.Portrait -> lib.filter { it.id in tags.portrait }
        Page.SlowMo -> lib.filter { it.isSlowMo }
        Page.TimeLapse -> lib.filter { it.isTimeLapse }
        Page.Bursts -> lib.filter { it.isBurst }
        Page.Raw -> lib.filter { it.isRaw }
        Page.Animated -> lib.filter { it.isAnimated }
        Page.Selfies -> lib.filter { it.isSelfie }
        Page.LongExposure -> lib.filter { effects[it.id] == LiveEffect.LongExposure }
        Page.RecentlyEdited -> lib.filter { it.id in s.edited }.sortedBy { it.dateModified }
        Page.AllAlbums, Page.MediaTypesList, Page.UtilitiesList, Page.PinnedList -> emptyList()
        Page.Favourites -> lib.filter { it.isFavorite }
        Page.Videos -> lib.filter { it.isVideo }
        Page.Screenshots -> lib.filter { it.isScreenshot }
        Page.ScreenRecordings -> lib.filter { it.isScreenRecording }
        Page.Panoramas -> lib.filter { it.isPanorama }
        Page.RecentlySaved -> {
            val since = System.currentTimeMillis() - 30L * 86_400_000L
            lib.filter { it.dateAdded >= since && !it.bucketName.equals("Camera", true) }.sortedBy { it.dateAdded }
        }
        Page.RecentlyViewed -> {
            val byId = lib.associateBy { it.id }
            s.viewed.distinct().mapNotNull { byId[it] }.let { if (s.newestFirst) it else it.asReversed().toList() }
        }
        Page.Duplicates -> duplicates(lib)
        Page.Hidden -> hidden
        Page.Deleted -> trashed.sortedBy { it.expiresAt }
        Page.Settings -> emptyList()
    }

    /** In each group of identical photos, everything except the best copy (largest, favourite, oldest). */
    fun duplicateExtras(items: List<Media>): List<Media> =
        items.filter { it.size > 0 }.groupBy { Triple(it.size, it.width, it.height) }.values.filter { it.size > 1 }.flatMap { g ->
            val keep = g.maxWithOrNull(compareBy<Media> { it.width.toLong() * it.height }.thenBy { it.isFavorite }.thenByDescending { it.dateTaken })
            g.filter { it !== keep }
        }

    fun duplicates(lib: List<Media>): List<Media> =
        lib.filter { it.size > 0 }.groupBy { Triple(it.size, it.width, it.height) }.values.filter { it.size > 1 }.flatten()
            .sortedBy { it.dateTaken }

    override fun onCleared() {
        super.onCleared()
    }
}
