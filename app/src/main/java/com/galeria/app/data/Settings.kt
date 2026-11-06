package com.galeria.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("photos_settings")

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val glass: GlassMode = GlassMode.Clear,
    val lockAlbums: Boolean = true,
    val showHidden: Boolean = true,
    val gridLevel: Int = 1,
    val aspectGrid: Boolean = false,
    val sort: SortMode = SortMode.Captured,
    val askedManageMedia: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val viewed: List<Long> = emptyList(),
    val edited: Set<Long> = emptySet(),
    /** Newest photos at the top (scroll down for older). Off = the iPhone order, newest at the bottom. */
    val newestFirst: Boolean = true,
    val hiddenAlbums: Set<Long> = emptySet(),
    val pinned: List<String> = DefaultPinned,
)

val DefaultPinned = listOf("fav", "saved", "videos", "shots")

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val glass = stringPreferencesKey("glass")
        val lock = booleanPreferencesKey("lock_albums")
        val showHidden = booleanPreferencesKey("show_hidden")
        val grid = intPreferencesKey("grid_level")
        val aspect = booleanPreferencesKey("aspect_grid")
        val sort = stringPreferencesKey("sort")
        val asked = booleanPreferencesKey("asked_manage_media")
        val searches = stringPreferencesKey("recent_searches")
        val viewed = stringPreferencesKey("viewed")
        val edited = stringSetPreferencesKey("edited")
        val newestFirst = booleanPreferencesKey("newest_first")
        val hiddenAlbums = stringSetPreferencesKey("hidden_albums")
        val pinned = stringPreferencesKey("pinned")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(this[K.theme] ?: "System") }.getOrDefault(ThemeMode.System),
        glass = runCatching { GlassMode.valueOf(this[K.glass] ?: "Clear") }.getOrDefault(GlassMode.Clear),
        lockAlbums = this[K.lock] ?: true,
        showHidden = this[K.showHidden] ?: true,
        gridLevel = this[K.grid] ?: 1,
        aspectGrid = this[K.aspect] ?: false,
        sort = runCatching { SortMode.valueOf(this[K.sort] ?: "Captured") }.getOrDefault(SortMode.Captured),
        askedManageMedia = this[K.asked] ?: false,
        recentSearches = (this[K.searches] ?: "").split('\u0001').filter { it.isNotBlank() },
        viewed = (this[K.viewed] ?: "").split(',').mapNotNull { it.toLongOrNull() },
        edited = (this[K.edited] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet(),
        newestFirst = this[K.newestFirst] ?: true,
        hiddenAlbums = (this[K.hiddenAlbums] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet(),
        pinned = this[K.pinned]?.split(',')?.filter { it.isNotBlank() } ?: DefaultPinned,
    )

    suspend fun setTheme(v: ThemeMode) = context.store.edit { it[K.theme] = v.name }
    suspend fun setGlass(v: GlassMode) = context.store.edit { it[K.glass] = v.name }
    suspend fun setLock(v: Boolean) = context.store.edit { it[K.lock] = v }
    suspend fun setShowHidden(v: Boolean) = context.store.edit { it[K.showHidden] = v }
    suspend fun setGrid(level: Int, aspect: Boolean) = context.store.edit { it[K.grid] = level; it[K.aspect] = aspect }
    suspend fun setSort(v: SortMode) = context.store.edit { it[K.sort] = v.name }
    suspend fun setAsked() = context.store.edit { it[K.asked] = true }

    suspend fun setEdited(id: Long, on: Boolean) = context.store.edit { p ->
        val cur = (p[K.edited] ?: emptySet()).toMutableSet()
        if (on) cur += id.toString() else cur -= id.toString()
        p[K.edited] = cur
    }

    suspend fun setNewestFirst(v: Boolean) = context.store.edit { it[K.newestFirst] = v }
    suspend fun setAlbumHidden(id: Long, hidden: Boolean) = context.store.edit { p ->
        val cur = (p[K.hiddenAlbums] ?: emptySet()).toMutableSet()
        if (hidden) cur += id.toString() else cur -= id.toString()
        p[K.hiddenAlbums] = cur
    }
    suspend fun setPinned(keys: List<String>) = context.store.edit { it[K.pinned] = keys.joinToString(",") }

    suspend fun addSearch(q: String) = context.store.edit { p ->
        val cur = (p[K.searches] ?: "").split('\u0001').filter { it.isNotBlank() && !it.equals(q, true) }
        p[K.searches] = (listOf(q) + cur).take(8).joinToString("\u0001")
    }
    suspend fun clearSearches() = context.store.edit { it.remove(K.searches) }

    suspend fun addViewed(id: Long) = context.store.edit { p ->
        val cur = (p[K.viewed] ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { it != id }
        p[K.viewed] = (listOf(id) + cur).take(60).joinToString(",")
    }
}
