package com.galeria.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore(name = "luma_settings")

object SettingsKeys {
    val GRID_COLUMNS       = intPreferencesKey("grid_columns")
    val SHOW_FILE_INFO     = booleanPreferencesKey("show_file_info")
    val GROUP_BY_DATE      = booleanPreferencesKey("group_by_date")
    val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
    val GLASS_OPACITY      = floatPreferencesKey("glass_opacity")
    val APP_LOCK_ENABLED   = booleanPreferencesKey("app_lock")
    val HIDDEN_ALBUM_LOCK  = booleanPreferencesKey("hidden_album_lock")
    val AUTO_LOCK_MINS     = intPreferencesKey("auto_lock_mins")
    val SHOW_HIDDEN_ALBUM  = booleanPreferencesKey("show_hidden_album")
    val THUMBNAIL_QUALITY  = intPreferencesKey("thumbnail_quality")
    val SORT_ORDER         = stringPreferencesKey("sort_order")
    val DELETED_PROTECTION = booleanPreferencesKey("deleted_protection")
}

data class AppSettings(
    val gridColumns: Int = 3,
    val showFileInfo: Boolean = false,
    val groupByDate: Boolean = true,
    val animationsEnabled: Boolean = true,
    val glassOpacity: Float = 0.15f,
    val appLockEnabled: Boolean = false,
    val hiddenAlbumLock: Boolean = true,
    val autoLockMins: Int = 1,
    val showHiddenAlbum: Boolean = true,
    val thumbnailQuality: Int = 80,
    val sortOrder: String = "newest",
    val deletedProtection: Boolean = false
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            gridColumns      = prefs[SettingsKeys.GRID_COLUMNS]       ?: 3,
            showFileInfo     = prefs[SettingsKeys.SHOW_FILE_INFO]     ?: false,
            groupByDate      = prefs[SettingsKeys.GROUP_BY_DATE]      ?: true,
            animationsEnabled= prefs[SettingsKeys.ANIMATIONS_ENABLED] ?: true,
            glassOpacity     = prefs[SettingsKeys.GLASS_OPACITY]      ?: 0.15f,
            appLockEnabled   = prefs[SettingsKeys.APP_LOCK_ENABLED]   ?: false,
            hiddenAlbumLock  = prefs[SettingsKeys.HIDDEN_ALBUM_LOCK]  ?: true,
            autoLockMins     = prefs[SettingsKeys.AUTO_LOCK_MINS]     ?: 1,
            showHiddenAlbum  = prefs[SettingsKeys.SHOW_HIDDEN_ALBUM]  ?: true,
            thumbnailQuality = prefs[SettingsKeys.THUMBNAIL_QUALITY]  ?: 80,
            sortOrder        = prefs[SettingsKeys.SORT_ORDER]         ?: "newest",
            deletedProtection= prefs[SettingsKeys.DELETED_PROTECTION] ?: false
        )
    }

    suspend fun update(block: suspend (MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
