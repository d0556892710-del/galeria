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

data class Album(val id: Long, val name: String, val count: Int, val cover: Media, val latest: Long)

/** A screen pushed on top of the tabs (album, utility, media type). */
sealed class Page(val locked: Boolean = false) {
    data class Bucket(val id: Long, val name: String) : Page()
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
    /** Ids hidden from the grid while the viewer animates over them. */
    var viewingId by mutableStateOf<Long?>(null)

    private val filterState = kotlinx.coroutines.flow.MutableStateFlow(LibFilter.All)

    /** The library as shown in All: filtered and sorted. */
    val libraryView: StateFlow<List<Media>> = combine(repo.library, settings, filterState) { lib, s, f ->
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
        if (s.sort == SortMode.Added) list.sortedWith(compareBy<Media> { it.dateAdded }.thenBy { it.id }) else list
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = repo.library.map { lib ->
        lib.groupBy { it.bucketId }.map { (id, items) ->
            val latest = items.maxBy { it.dateTaken }
            Album(id, latest.bucketName.ifBlank { "—" }, items.size, latest, latest.dateTaken)
        }.sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.latest })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    fun pageItems(p: Page, lib: List<Media>, s: AppSettings, trashed: List<Media>, hidden: List<Media>): List<Media> = when (p) {
        is Page.Bucket -> lib.filter { it.bucketId == p.id }
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
            s.viewed.mapNotNull { byId[it] }
        }
        Page.Duplicates -> duplicates(lib)
        Page.Hidden -> hidden
        Page.Deleted -> trashed.sortedBy { it.expiresAt }
        Page.Settings -> emptyList()
    }

    fun duplicates(lib: List<Media>): List<Media> =
        lib.filter { it.size > 0 }.groupBy { Triple(it.size, it.width, it.height) }.values.filter { it.size > 1 }.flatten()
            .sortedBy { it.dateTaken }

    override fun onCleared() {
        super.onCleared()
    }
}
