package com.galeria.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galeria.app.data.MediaItem
import com.galeria.app.data.MediaRepository
import com.galeria.app.data.MediaType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)

    // ── All media (not hidden) ───────────────────────────────────
    private val _allMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val libraryMedia: StateFlow<List<MediaItem>> = _allMedia.asStateFlow()

    // ── Grouped by month header for Library ─────────────────────
    val mediaByMonth: StateFlow<Map<String, List<MediaItem>>> = _allMedia.map { items ->
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        items
            .filter { !it.isHidden }
            .groupBy { item -> fmt.format(Date(item.dateTaken)) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // ── Filtered views ───────────────────────────────────────────
    val hiddenMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favoriteMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isFavorite && !m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val videosMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isVideo && !m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val screenshotsMedia: StateFlow<List<MediaItem>> = _allMedia.map { items ->
        items.filter { m -> !m.isHidden && (m.name.contains("screenshot", ignoreCase = true) || m.path.contains("Screenshots")) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Search ───────────────────────────────────────────────────
    private val _query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<MediaItem>> = combine(_allMedia, _query) { items, q ->
        if (q.isBlank()) emptyList()
        else items.filter { m ->
            !m.isHidden && (m.name.contains(q, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Select mode ──────────────────────────────────────────────
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selected.asStateFlow()
    val isSelectMode: StateFlow<Boolean> = _selected.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // ── Load ─────────────────────────────────────────────────────
    fun load() {
        viewModelScope.launch {
            combine(repo.hiddenIds, repo.favoriteIds) { h, f -> h to f }
                .collect { (hidden, favs) ->
                    _allMedia.value = repo.loadAll(hidden, favs)
                }
        }
    }

    // ── Actions ──────────────────────────────────────────────────
    fun setQuery(q: String) { _query.value = q }

    fun toggleSelect(id: Long) {
        _selected.value = _selected.value.toMutableSet().also {
            if (id in it) it.remove(id) else it.add(id)
        }
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun toggleFavorite(id: Long) = viewModelScope.launch {
        val item = _allMedia.value.firstOrNull { it.id == id } ?: return@launch
        repo.setFavorite(id, !item.isFavorite)
    }

    fun hideItems(ids: Set<Long>) = viewModelScope.launch {
        ids.forEach { repo.setHidden(it, true) }
        clearSelection()
    }

    fun unhideItems(ids: Set<Long>) = viewModelScope.launch {
        ids.forEach { repo.setHidden(it, false) }
        clearSelection()
    }

    // Helper to find item by id
    fun itemById(id: Long): MediaItem? = _allMedia.value.firstOrNull { it.id == id }
}
