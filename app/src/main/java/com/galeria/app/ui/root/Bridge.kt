package com.galeria.app.ui.root

import android.content.Context
import android.content.IntentSender
import android.content.res.Resources
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.Overlays
import com.galeria.app.ui.components.SheetAction
import com.galeria.app.ui.components.SheetSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Things only the Activity can do. */
interface Bridge {
    fun launch(sender: IntentSender?, onResult: (Boolean) -> Unit)
    fun share(items: List<Media>)
    fun authenticate(title: String, subtitle: String, onOk: () -> Unit)
    fun setWallpaper(m: Media)
    fun copy(m: Media)
    fun openMaps(lat: Double, lon: Double)
    fun requestPermissions()
    fun openAppSettings()
    fun requestManageMedia()
    fun openDefaultApps()
    fun setLanguage(tag: String?)
    fun currentLanguage(): String
    fun finishPick(items: List<Media>)
    fun cancelPick()
    fun canManageMedia(): Boolean
    fun setSystemBars(darkContent: Boolean)
    fun hasLocationAccess(): Boolean
}

val LocalBridge = staticCompositionLocalOf<Bridge> { error("Bridge not provided") }

/** The glass surfaces behind menus follow the top-most screen (library, album page, viewer). */
@Stable
class BackdropStack {
    val list = mutableStateListOf<Backdrop>()
    val top: Backdrop? get() = list.lastOrNull()
}

val LocalBackdrops = staticCompositionLocalOf { BackdropStack() }

/** Library operations that go through the system (trash, favourite, delete, hide). */
class Actions(
    private val vm: AppViewModel,
    private val bridge: Bridge,
    private val overlays: Overlays,
    private val res: Resources,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    private fun s(id: Int) = res.getString(id)

    fun confirmDelete(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        val one = items.size == 1
        val title = when {
            one && items[0].isVideo -> s(R.string.delete_video)
            one -> s(R.string.delete_photo)
            else -> res.getQuantityString(R.plurals.delete_items, items.size, items.size)
        }
        overlays.sheet(SheetSpec(
            if (one) s(R.string.delete_msg_one) else s(R.string.delete_msg_many),
            listOf(SheetAction(title, destructive = true) { trash(items, after) }),
        ))
    }

    fun trash(items: List<Media>, after: () -> Unit = {}) {
        bridge.launch(vm.repo.trashRequest(items, true)) { ok -> if (ok) { vm.refresh(); after() } }
    }

    fun setFavourite(items: List<Media>, favourite: Boolean, after: () -> Unit = {}) {
        bridge.launch(vm.repo.favoriteRequest(items, favourite)) { ok -> if (ok) { vm.refresh(); after() } }
    }

    fun recover(items: List<Media>) {
        bridge.launch(vm.repo.trashRequest(items, false)) { ok -> if (ok) { vm.refresh(); overlays.toast(s(R.string.recovered)) } }
    }

    fun deleteForever(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        val hiddenOnes = items.filter { it.isHidden }
        val normal = items.filter { !it.isHidden }
        if (hiddenOnes.isNotEmpty()) scope.launch { vm.repo.deleteHidden(hiddenOnes); after() }
        if (normal.isNotEmpty()) bridge.launch(vm.repo.deleteRequest(normal)) { ok -> if (ok) { vm.refresh(); after() } }
    }

    fun confirmDeleteForever(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        overlays.sheet(SheetSpec(null, listOf(SheetAction(s(R.string.delete_permanently), destructive = true) { deleteForever(items, after) })))
    }

    fun hide(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        scope.launch {
            val copied = vm.repo.copyToHidden(items)
            if (copied.isEmpty()) {
                overlays.toast(s(R.string.error_generic)); return@launch
            }
            bridge.launch(vm.repo.deleteRequest(copied)) { ok ->
                if (ok) {
                    overlays.toast(s(R.string.moved_to_hidden)); vm.refresh(); after()
                } else scope.launch { vm.repo.rollbackHidden(copied) }
            }
        }
    }

    fun unhide(items: List<Media>, after: () -> Unit = {}) {
        scope.launch {
            val n = vm.repo.unhide(items)
            if (n > 0) overlays.toast(s(R.string.moved_to_library)) else overlays.toast(s(R.string.error_generic))
            after()
        }
    }

    fun duplicate(m: Media) {
        scope.launch {
            if (vm.repo.duplicate(m)) overlays.toast(s(R.string.duplicated)) else overlays.toast(s(R.string.error_generic))
        }
    }

    fun deletedItemSheet(m: Media) {
        overlays.sheet(SheetSpec(
            s(R.string.deleted_item_msg),
            listOf(
                SheetAction(s(R.string.recover)) { recover(listOf(m)) },
                SheetAction(s(R.string.delete_permanently), destructive = true) { deleteForever(listOf(m)) },
            ),
        ))
    }

    fun captions() = context.getSharedPreferences("captions", Context.MODE_PRIVATE)
}
