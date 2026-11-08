package com.galeria.app.ui.editor

import com.galeria.app.data.Media
import com.galeria.app.data.MediaRepository
import com.galeria.app.data.MotionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Edits applied without opening the editor (Paste Edits on many photos at once). */
object EditJobs {
    fun canEdit(m: Media) = !m.isVideo && m.hiddenFile == null && (MotionStore.isJpeg(m) || m.mime.equals("image/png", true))

    /** Adjustments and filter of [clip], keeping each photo's own crop, Live choices and Auto amount for it. */
    suspend fun paste(repo: MediaRepository, m: Media, clip: EditParams): Pair<ByteArray, EditParams>? = runCatching {
        val edits = repo.edits
        if (!edits.ensureBackup(m)) return null
        val own = if (edits.hasBackup(m)) EditParams.fromJson(edits.params(m.id)) else null
        val small = edits.decodePreview(m, 256) ?: return null
        val auto = withContext(Dispatchers.Default) { EditRender.autoVector(small) }
        val base = own ?: EditParams()
        val pp = base.copy(values = clip.values, autoVec = auto, filter = clip.filter, filterAmount = clip.filterAmount)
        val src = edits.decodeFull(m) ?: return null
        val out = withContext(Dispatchers.Default) { EditRender.render(src, pp) }
        val png = m.mime.equals("image/png", true)
        var bytes = edits.encode(m, out, png)
        if (!png && m.id in repo.motion.tags.value.live && !pp.liveOff) {
            val v = withContext(Dispatchers.IO) { edits.originalVideo(m, repo.motion) }
            val key = withContext(Dispatchers.IO) { runCatching { repo.motion.info(m)?.keyUs }.getOrNull() } ?: -1L
            if (v != null) bytes = MotionStore.buildMotionJpeg(bytes, v, if (pp.keyUs >= 0) pp.keyUs else key)
        }
        bytes to pp
    }.getOrNull()
}
