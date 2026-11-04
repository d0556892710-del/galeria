package com.galeria.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galeria.app.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo        = MediaRepository(app)
    private val settingsRepo = SettingsRepository(app)

    // ── All media ────────────────────────────────────────────────
    private val _allMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val libraryMedia: StateFlow<List<MediaItem>> = _allMedia.asStateFlow()

    // ── Settings ─────────────────────────────────────────────────
    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ── Grouped by day for Library ───────────────────────────────
    val mediaByDay: StateFlow<List<Pair<String, List<MediaItem>>>> = _allMedia.map { items ->
        val fmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        items
            .filter { !it.isHidden }
            .groupBy { item -> fmt.format(Date(item.dateTaken)) }
            .entries.map { (k, v) -> k to v }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Filtered views ───────────────────────────────────────────
    val hiddenMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favoriteMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isFavorite && !m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val videosMedia: StateFlow<List<MediaItem>> = _allMedia.map { it.filter { m -> m.isVideo && !m.isHidden } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val screenshotsMedia: StateFlow<List<MediaItem>> = _allMedia.map { items ->
        items.filter { m ->
            !m.isHidden && (m.name.contains("screenshot", ignoreCase = true) ||
                            m.path.contains("Screenshots", ignoreCase = true) ||
                            m.path.contains("screen_record", ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentlyAddedMedia: StateFlow<List<MediaItem>> = _allMedia.map { items ->
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        items.filter { !it.isHidden && it.dateTaken >= cutoff }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadedMedia: StateFlow<List<MediaItem>> = _allMedia.map { items ->
        items.filter { m ->
            !m.isHidden && (m.path.contains("/Download", ignoreCase = true) ||
                            m.path.contains("/Downloads", ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val whatsappMedia: StateFlow<List<MediaItem>> = _allMedia.map { items ->
        items.filter { m ->
            !m.isHidden && m.path.contains("WhatsApp", ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Recently deleted (simulated via DataStore) ───────────────
    private val _deletedMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val deletedMedia: StateFlow<List<MediaItem>> = _deletedMedia.asStateFlow()

    // ── Search ───────────────────────────────────────────────────
    private val _query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<MediaItem>> = combine(_allMedia, _query) { items, q ->
        if (q.isBlank()) emptyList()
        else items.filter { m ->
            !m.isHidden && (
                m.name.contains(q, ignoreCase = true) ||
                m.path.contains(q, ignoreCase = true)
            )
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

    /** Move item to soft-deleted bin (30-day buffer) */
    fun softDelete(id: Long) = viewModelScope.launch {
        val item = _allMedia.value.firstOrNull { it.id == id } ?: return@launch
        _deletedMedia.value = _deletedMedia.value + item
        _allMedia.value = _allMedia.value.filter { it.id != id }
        clearSelection()
    }

    /** Restore items from soft-deleted bin */
    fun restoreDeleted(ids: Set<Long>) = viewModelScope.launch {
        val toRestore = _deletedMedia.value.filter { it.id in ids }
        _deletedMedia.value = _deletedMedia.value.filter { it.id !in ids }
        _allMedia.value = (_allMedia.value + toRestore).sortedByDescending { it.dateTaken }
    }

    /** Permanently delete items from bin */
    fun permanentDelete(ids: Set<Long>) = viewModelScope.launch {
        _deletedMedia.value = _deletedMedia.value.filter { it.id !in ids }
    }

    // Settings updates
    fun updateSettings(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { settingsRepo.update(block) }
    }

    fun setGridColumns(cols: Int) = updateSettings { it[SettingsKeys.GRID_COLUMNS] = cols }
    fun setGroupByDate(v: Boolean) = updateSettings { it[SettingsKeys.GROUP_BY_DATE] = v }
    fun setAnimations(v: Boolean) = updateSettings { it[SettingsKeys.ANIMATIONS_ENABLED] = v }
    fun setGlassOpacity(v: Float) = updateSettings { it[SettingsKeys.GLASS_OPACITY] = v }
    fun setAppLock(v: Boolean) = updateSettings { it[SettingsKeys.APP_LOCK_ENABLED] = v }
    fun setHiddenAlbumLock(v: Boolean) = updateSettings { it[SettingsKeys.HIDDEN_ALBUM_LOCK] = v }
    fun setAutoLockMins(v: Int) = updateSettings { it[SettingsKeys.AUTO_LOCK_MINS] = v }
    fun setShowHiddenAlbum(v: Boolean) = updateSettings { it[SettingsKeys.SHOW_HIDDEN_ALBUM] = v }
    fun setDeletedProtection(v: Boolean) = updateSettings { it[SettingsKeys.DELETED_PROTECTION] = v }
    fun setShowFileInfo(v: Boolean) = updateSettings { it[SettingsKeys.SHOW_FILE_INFO] = v }
    fun setSortOrder(v: String) = updateSettings { it[SettingsKeys.SORT_ORDER] = v }

    fun itemById(id: Long): MediaItem? = _allMedia.value.firstOrNull { it.id == id }

    fun visibleLibrary(): List<MediaItem> = _allMedia.value.filter { !it.isHidden }
}
