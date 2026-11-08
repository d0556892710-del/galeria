package com.galeria.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An album you made, like on iPhone: it lists photos, it does not move files. */
data class UserAlbum(val id: Long, val name: String, val items: List<Long>, val created: Long)

class AlbumStore(context: Context) {
    private val file = File(context.filesDir, "albums.json")
    private val _albums = MutableStateFlow(load())
    val albums: StateFlow<List<UserAlbum>> = _albums.asStateFlow()

    @Synchronized
    fun create(name: String, ids: List<Long> = emptyList()): UserAlbum {
        val now = System.currentTimeMillis()
        // Negative ids never collide with folder albums' ids in lists.
        var id = -now
        while (_albums.value.any { it.id == id }) id--
        val a = UserAlbum(id, name.trim().ifBlank { "Album" }, ids.distinct(), now)
        save(listOf(a) + _albums.value)
        return a
    }

    @Synchronized
    fun add(albumId: Long, ids: List<Long>) = update(albumId) { it.copy(items = (it.items + ids).distinct()) }

    @Synchronized
    fun remove(albumId: Long, ids: Collection<Long>) = update(albumId) { a -> a.copy(items = a.items.filter { it !in ids }) }

    @Synchronized
    fun rename(albumId: Long, name: String) = update(albumId) { it.copy(name = name.trim().ifBlank { it.name }) }

    @Synchronized
    fun delete(albumId: Long) = save(_albums.value.filter { it.id != albumId })

    /** Photos removed from the device leave every album. */
    @Synchronized
    fun prune(existing: Set<Long>) {
        val cur = _albums.value
        val next = cur.map { a -> a.copy(items = a.items.filter { it in existing }) }
        if (next != cur) save(next)
    }

    private fun update(id: Long, f: (UserAlbum) -> UserAlbum) = save(_albums.value.map { if (it.id == id) f(it) else it })

    private fun save(list: List<UserAlbum>) {
        _albums.value = list
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("created", a.created).put("items", JSONArray(a.items)))
            }
            val tmp = File(file.parentFile, "albums.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    private fun load(): List<UserAlbum> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("items")
            UserAlbum(o.getLong("id"), o.getString("name"), (0 until items.length()).map { items.getLong(it) }.distinct(), o.optLong("created"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
