package com.galeria.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "galeria_prefs")

private val KEY_HIDDEN    = stringSetPreferencesKey("hidden_ids")
private val KEY_FAVORITES = stringSetPreferencesKey("favorite_ids")

class MediaRepository(private val context: Context) {

    // ── Stored sets ─────────────────────────────────────────────
    val hiddenIds: Flow<Set<String>>    = context.dataStore.data.map { it[KEY_HIDDEN]    ?: emptySet() }
    val favoriteIds: Flow<Set<String>>  = context.dataStore.data.map { it[KEY_FAVORITES] ?: emptySet() }

    suspend fun setHidden(id: Long, hidden: Boolean) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_HIDDEN]?.toMutableSet() ?: mutableSetOf()
            if (hidden) cur.add(id.toString()) else cur.remove(id.toString())
            prefs[KEY_HIDDEN] = cur
        }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_FAVORITES]?.toMutableSet() ?: mutableSetOf()
            if (fav) cur.add(id.toString()) else cur.remove(id.toString())
            prefs[KEY_FAVORITES] = cur
        }
    }

    // ── MediaStore query ─────────────────────────────────────────
    suspend fun loadAll(
        hiddenSet: Set<String>,
        favoriteSet: Set<String>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        items += queryImages(hiddenSet, favoriteSet)
        items += queryVideos(hiddenSet, favoriteSet)
        items.sortedByDescending { it.dateTaken }
    }

    private fun queryImages(
        hiddenSet: Set<String>,
        favoriteSet: Set<String>
    ): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol    = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol  = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol  = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val wCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val pathCol  = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val mimeCol  = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id    = cursor.getLong(idCol)
                val mime  = cursor.getString(mimeCol) ?: ""
                val type  = if (mime.equals("image/gif", ignoreCase = true)) MediaType.GIF else MediaType.IMAGE
                val uri   = ContentUris.withAppendedId(collection, id)
                list += MediaItem(
                    id         = id,
                    uri        = uri,
                    name       = cursor.getString(nameCol) ?: "",
                    dateTaken  = cursor.getLong(dateCol),
                    type       = type,
                    width      = cursor.getInt(wCol),
                    height     = cursor.getInt(hCol),
                    path       = cursor.getString(pathCol) ?: "",
                    isHidden   = id.toString() in hiddenSet,
                    isFavorite = id.toString() in favoriteSet
                )
            }
        }
        return list
    }

    private fun queryVideos(
        hiddenSet: Set<String>,
        favoriteSet: Set<String>
    ): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val durCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val wCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id  = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                list += MediaItem(
                    id         = id,
                    uri        = uri,
                    name       = cursor.getString(nameCol) ?: "",
                    dateTaken  = cursor.getLong(dateCol),
                    durationMs = cursor.getLong(durCol),
                    type       = MediaType.VIDEO,
                    width      = cursor.getInt(wCol),
                    height     = cursor.getInt(hCol),
                    path       = cursor.getString(pathCol) ?: "",
                    isHidden   = id.toString() in hiddenSet,
                    isFavorite = id.toString() in favoriteSet
                )
            }
        }
        return list
    }
}
