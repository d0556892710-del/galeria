package com.galeria.app.ui.root

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.data.MetaClipboard
import com.galeria.app.data.Metadata
import com.galeria.app.ui.components.MenuItem
import com.galeria.app.ui.components.MenuSpec
import com.galeria.app.ui.components.Overlays
import com.galeria.app.ui.components.PromptSpec
import com.galeria.app.ui.components.SheetAction
import com.galeria.app.ui.components.SheetSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.editor.EditJobs
import com.galeria.app.ui.editor.EditParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import com.galeria.app.ui.components.LocalOverlays

@Composable
fun rememberMoreActions(vm: AppViewModel, actions: Actions): MoreActions {
    val bridge = LocalBridge.current
    val overlays = LocalOverlays.current
    val ctx = LocalContext.current
    // Long jobs (paste edits on many photos) outlive the screen that started them.
    return remember(vm, actions, bridge, overlays, ctx) { MoreActions(vm, bridge, overlays, actions, vm.viewModelScope, ctx) }
}

/**
 * The iPhone "…" actions shared by the viewer and multi-selection: albums, favourites,
 * date & time, location, copy / paste edits.
 */
class MoreActions(
    private val vm: AppViewModel,
    private val bridge: Bridge,
    private val overlays: Overlays,
    private val actions: Actions,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    private fun s(id: Int) = context.getString(id)
    private val repo get() = vm.repo

    // ------------------------------------------------------------------ albums

    fun newAlbum(ids: List<Long> = emptyList(), after: () -> Unit = {}) {
        overlays.prompt(PromptSpec(
            title = s(R.string.new_album), message = s(R.string.new_album_msg), placeholder = s(R.string.album_name), confirm = s(R.string.save),
        ) { name ->
            val a = vm.createAlbum(name, ids)
            if (ids.isNotEmpty()) overlays.toast(context.getString(R.string.added_to, a.name))
            after()
        })
    }

    fun addToAlbum(items: List<Media>, after: () -> Unit = {}) {
        val ids = items.filter { it.hiddenFile == null }.map { it.id }
        if (ids.isEmpty()) return
        val mine = repo.albums.albums.value
        val list = buildList {
            add(SheetAction(s(R.string.new_album) + "…") { newAlbum(ids, after) })
            mine.forEach { a ->
                add(SheetAction(a.name) {
                    vm.addToAlbum(a.id, ids)
                    overlays.toast(context.getString(R.string.added_to, a.name))
                    after()
                })
            }
        }
        overlays.sheet(SheetSpec(s(R.string.add_to_album), list))
    }

    fun renameAlbum(a: Album) {
        overlays.prompt(PromptSpec(title = s(R.string.rename_album), initial = a.name, placeholder = s(R.string.album_name), confirm = s(R.string.save)) { name ->
            vm.renameAlbum(a.id, name)
        })
    }

    fun deleteAlbum(a: Album, after: () -> Unit = {}) {
        overlays.sheet(SheetSpec(s(R.string.delete_album_msg), listOf(SheetAction(s(R.string.delete_album), destructive = true) {
            vm.deleteAlbum(a.id)
            vm.setPinned(vm.settings.value.pinned - "album:${a.id}")
            after()
        })))
    }

    // ------------------------------------------------------------------ date & time

    fun adjustDate(items: List<Media>, after: () -> Unit = {}) {
        val first = items.minByOrNull { it.dateTaken } ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = first.dateTaken }
        runCatching {
            DatePickerDialog(context, { _, y, mo, d ->
                cal.set(y, mo, d)
                TimePickerDialog(context, { _, h, mi ->
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi)
                    val delta = cal.timeInMillis - first.dateTaken
                    applyDate(items, delta, after)
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
    }

    private fun applyDate(items: List<Media>, delta: Long, after: () -> Unit) {
        val targets = items.filter { it.hiddenFile == null }
        if (targets.isEmpty() || delta == 0L) return
        bridge.launch(repo.writeRequest(targets)) { ok ->
            if (!ok) return@launch
            scope.launch {
                var n = 0
                for (m in targets) if (Metadata.setDate(context, m, m.dateTaken + delta)) n++
                repo.refresh(400)
                overlays.toast(s(if (n > 0) R.string.date_updated else R.string.cant_change))
                after()
            }
        }
    }

    // ------------------------------------------------------------------ location

    /** "Use as Wallpaper": one tap sets it, nothing to adjust. */
    fun wallpaperMenu(m: Media): MenuSpec {
        fun set(which: Int) = scope.launch {
            val ok = com.galeria.app.data.Wallpaper.set(context, m, which)
            overlays.toast(s(if (ok) R.string.wallpaper_set else R.string.wallpaper_failed))
        }
        return MenuSpec(title = s(R.string.use_as_wallpaper), groups = listOf(listOf(
            MenuItem(s(R.string.wall_home), Sym.wallpaper) { set(com.galeria.app.data.Wallpaper.HOME) },
            MenuItem(s(R.string.wall_lock), Sym.lock) { set(com.galeria.app.data.Wallpaper.LOCK) },
            MenuItem(s(R.string.wall_both), Sym.wallpaper) { set(com.galeria.app.data.Wallpaper.BOTH) },
        )))
    }

    fun locationMenu(items: List<Media>, after: () -> Unit = {}): MenuSpec {
        val single = items.singleOrNull()
        val group = buildList {
            if (single != null) add(MenuItem(s(R.string.copy_location), Sym.docOnDoc) {
                scope.launch {
                    val ll = Metadata.location(context, single)
                    MetaClipboard.location = ll
                    overlays.toast(s(if (ll != null) R.string.copied else R.string.no_location))
                }
            })
            if (MetaClipboard.location != null) add(MenuItem(s(R.string.paste_location), Sym.map) { setLocation(items, MetaClipboard.location, after) })
            add(MenuItem(s(R.string.remove_location), Sym.xmark, destructive = true) { setLocation(items, null, after) })
        }
        return MenuSpec(title = s(R.string.adjust_location), groups = listOf(group))
    }

    private fun setLocation(items: List<Media>, ll: DoubleArray?, after: () -> Unit) {
        val targets = items.filter { Metadata.writable(it) }
        if (targets.isEmpty()) { overlays.toast(s(R.string.cant_change)); return }
        bridge.launch(repo.writeRequest(targets)) { ok ->
            if (!ok) return@launch
            scope.launch {
                var n = 0
                for (m in targets) if (Metadata.setLocation(context, m, ll)) n++
                repo.refresh(400)
                overlays.toast(s(if (n > 0) R.string.location_updated else R.string.cant_change))
                after()
            }
        }
    }

    // ------------------------------------------------------------------ copy / paste edits

    fun canCopyEdits(m: Media) = repo.edits.hasBackup(m) && repo.edits.params(m.id) != null

    fun copyEdits(m: Media) {
        MetaClipboard.editsJson = repo.edits.params(m.id)
        overlays.toast(s(R.string.edits_copied))
    }

    fun pasteEdits(items: List<Media>, after: () -> Unit = {}) {
        val clip = EditParams.fromJson(MetaClipboard.editsJson) ?: return
        val targets = items.filter { EditJobs.canEdit(it) }
        if (targets.isEmpty()) { overlays.toast(s(R.string.cant_change)); return }
        bridge.launch(repo.writeRequest(targets)) { ok ->
            if (!ok) return@launch
            scope.launch {
                overlays.toast(s(R.string.pasting_edits))
                var n = 0
                for (m in targets) {
                    val r = EditJobs.paste(repo, m, clip) ?: continue
                    if (repo.edits.write(m, r.first)) {
                        repo.edits.setParams(m.id, r.second.toJson()); vm.setEdited(m.id, true); n++
                    }
                }
                repo.refresh(400)
                overlays.toast(s(if (n > 0) R.string.edits_pasted else R.string.edit_failed))
                after()
            }
        }
    }

    fun revert(m: Media) {
        overlays.sheet(SheetSpec(null, listOf(SheetAction(s(R.string.revert_to_original), destructive = true) {
            bridge.launch(repo.writeRequest(listOf(m))) { ok ->
                if (!ok) return@launch
                scope.launch {
                    if (repo.edits.revert(m)) { vm.setEdited(m.id, false); repo.refresh(300) }
                    else overlays.toast(s(R.string.edit_failed))
                }
            }
        })))
    }

    // ------------------------------------------------------------------ menus

    /** The "…" of a multi-selection. */
    fun selectionMenu(items: List<Media>, userAlbum: Long?, after: () -> Unit): MenuSpec {
        val allFav = items.isNotEmpty() && items.all { it.isFavorite }
        val g1 = buildList {
            add(MenuItem(s(R.string.add_to_album), Sym.photo) { addToAlbum(items, after) })
            if (userAlbum != null) add(MenuItem(s(R.string.remove_from_album), Sym.minus, destructive = true) {
                vm.removeFromAlbum(userAlbum, items.map { it.id }); after()
            })
        }
        val g2 = buildList {
            add(MenuItem(s(if (allFav) R.string.unfavourite else R.string.favourite), if (allFav) Sym.heart else Sym.heartFill) {
                actions.setFavourite(items, !allFav, after)
            })
            add(MenuItem(s(R.string.duplicate), Sym.duplicate) { items.forEach { actions.duplicate(it) }; after() })
            add(MenuItem(s(R.string.hide), Sym.eyeSlash) { actions.hide(items, after) })
        }
        val g3 = buildList {
            items.singleOrNull()?.takeIf { canCopyEdits(it) }?.let { m -> add(MenuItem(s(R.string.copy_edits), Sym.sliders) { copyEdits(m) }) }
            if (MetaClipboard.editsJson != null) add(MenuItem(s(R.string.paste_edits), Sym.sliders) { pasteEdits(items, after) })
            add(MenuItem(s(R.string.adjust_date), Sym.clock) { adjustDate(items, after) })
            add(MenuItem(s(R.string.adjust_location), Sym.map, submenu = { locationMenu(items, after) }))
        }
        return MenuSpec(groups = listOf(g1, g2, g3))
    }
}
