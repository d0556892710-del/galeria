package com.galeria.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The library engine. Reads the whole MediaStore in one pass on a background thread
 * (a cursor over ~20k rows takes ~100–200 ms), keeps it in memory sorted by capture date,
 * and refreshes automatically when anything changes on the device.
 */
private const val SNAP_MAGIC = 0x50484F31 // "PHO1"

class MediaRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Photos", "background task failed", e) })

    private val _library = MutableStateFlow<List<Media>>(emptyList())
    val library: StateFlow<List<Media>> = _library.asStateFlow()
    private val _trashed = MutableStateFlow<List<Media>>(emptyList())
    val trashed: StateFlow<List<Media>> = _trashed.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val hidden = HiddenStore(context)
    val motion = MotionStore(context)
    val edits = EditStore(context)
    val albums = AlbumStore(context)
    val faces = FaceStore(context)
    val smart = SmartIndex(context, faces)

    private var observer: ContentObserver? = null
    private var pending: Job? = null

    private val queried = java.util.concurrent.atomic.AtomicBoolean(false)
    private val publishLock = Any()
    private val snapshotFile get() = java.io.File(context.filesDir, "library_v1.bin")

    /** The last library seen, shown instantly at launch while the real query runs. */
    private fun loadSnapshot(): List<Media>? = runCatching {
        java.io.DataInputStream(java.io.BufferedInputStream(snapshotFile.inputStream(), 1 shl 16)).use { d ->
            if (d.readInt() != SNAP_MAGIC) return null
            val n = d.readInt()
            val out = ArrayList<Media>(n)
            repeat(n) {
                val id = d.readLong(); val video = d.readBoolean()
                out += Media(
                    id = id,
                    uri = android.net.Uri.parse(d.readUTF()),
                    isVideo = video, mime = d.readUTF(),
                    dateTaken = d.readLong(), dateAdded = d.readLong(), dateModified = d.readLong(),
                    width = d.readInt(), height = d.readInt(), orientation = d.readInt(),
                    duration = d.readLong(), size = d.readLong(), bucketId = d.readLong(),
                    bucketName = d.readUTF(), relativePath = d.readUTF(), displayName = d.readUTF(),
                    isFavorite = d.readBoolean(), expiresAt = d.readLong(), fps = d.readFloat(),
                )
            }
            out
        }
    }.getOrNull()

    private fun saveSnapshot(lib: List<Media>) = runCatching {
        val tmp = java.io.File(context.filesDir, "library_v1.tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(SNAP_MAGIC); o.writeInt(lib.size)
            for (m in lib) {
                o.writeLong(m.id); o.writeBoolean(m.isVideo); o.writeUTF(m.uri.toString()); o.writeUTF(m.mime)
                o.writeLong(m.dateTaken); o.writeLong(m.dateAdded); o.writeLong(m.dateModified)
                o.writeInt(m.width); o.writeInt(m.height); o.writeInt(m.orientation)
                o.writeLong(m.duration); o.writeLong(m.size); o.writeLong(m.bucketId)
                o.writeUTF(m.bucketName.take(2000)); o.writeUTF(m.relativePath.take(2000)); o.writeUTF(m.displayName.take(2000))
                o.writeBoolean(m.isFavorite); o.writeLong(m.expiresAt); o.writeFloat(m.fps)
            }
        }
        tmp.renameTo(snapshotFile)
    }

    fun start() {
        if (!queried.get()) scope.launch {
            val snap = loadSnapshot() ?: return@launch
            synchronized(publishLock) {
                if (!queried.get()) { _library.value = snap; _loaded.value = true }
            }
        }
        if (observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh(350)
            }
            runCatching {
                resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
                resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
            }
            observer = o
            scope.launch { hidden.load() }
        }
        refresh(0)
    }

    fun refresh(delayMs: Long = 0) {
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val result = runCatching { query(trashed = false) }
            val lib = result.getOrDefault(_library.value)
            synchronized(publishLock) {
                queried.set(true)
                _library.value = lib
                _loaded.value = true
            }
            motion.scan(lib)
            if (result.isSuccess && lib.isNotEmpty()) smart.scan(lib)
            if (result.isSuccess) {
                saveSnapshot(lib)
                // Photos deleted elsewhere leave your albums (never on an empty/failed read).
                if (lib.isNotEmpty()) albums.prune(lib.mapTo(HashSet()) { it.id })
            }
            _trashed.value = runCatching { query(trashed = true) }.getOrDefault(emptyList())
        }
    }

    private fun query(trashed: Boolean): List<Media> {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.CAPTURE_FRAMERATE,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<Media>(4096)
        resolver.query(files, proj, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iType = c.getColumnIndexOrThrow(proj[1])
            val iMime = c.getColumnIndexOrThrow(proj[2]); val iTaken = c.getColumnIndexOrThrow(proj[3])
            val iAdded = c.getColumnIndexOrThrow(proj[4]); val iMod = c.getColumnIndexOrThrow(proj[5])
            val iW = c.getColumnIndexOrThrow(proj[6]); val iH = c.getColumnIndexOrThrow(proj[7])
            val iOr = c.getColumnIndexOrThrow(proj[8]); val iDur = c.getColumnIndexOrThrow(proj[9])
            val iSize = c.getColumnIndexOrThrow(proj[10]); val iBid = c.getColumnIndexOrThrow(proj[11])
            val iBn = c.getColumnIndexOrThrow(proj[12]); val iRel = c.getColumnIndexOrThrow(proj[13])
            val iName = c.getColumnIndexOrThrow(proj[14]); val iFav = c.getColumnIndexOrThrow(proj[15])
            val iExp = c.getColumnIndexOrThrow(proj[16])
            val iFps = c.getColumnIndex(proj[17])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val added = c.getLong(iAdded) * 1000L
                val modified = c.getLong(iMod) * 1000L
                var taken = c.getLong(iTaken)
                if (taken <= 0L) taken = if (modified > 0) modified else added
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Media(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    isVideo = video,
                    mime = c.getString(iMime) ?: if (video) "video/*" else "image/*",
                    dateTaken = taken,
                    dateAdded = added,
                    dateModified = modified,
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    orientation = c.getInt(iOr),
                    duration = c.getLong(iDur),
                    size = c.getLong(iSize),
                    bucketId = c.getLong(iBid),
                    bucketName = c.getString(iBn) ?: "",
                    relativePath = c.getString(iRel) ?: "",
                    displayName = c.getString(iName) ?: "",
                    isFavorite = c.getInt(iFav) == 1,
                    expiresAt = c.getLong(iExp) * 1000L,
                    fps = if (iFps >= 0 && !c.isNull(iFps)) c.getFloat(iFps) else 0f,
                )
            }
        }
        out.sortWith(compareBy<Media> { it.dateTaken }.thenBy { it.id })
        return out
    }

    /** Metadata for a single uri opened from another app (may not be in the library). */
    suspend fun describe(uri: Uri, mimeHint: String?): Media? = withContext(Dispatchers.IO) {
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        if (uri.authority == MediaStore.AUTHORITY && id > 0) {
            _library.value.firstOrNull { it.id == id }?.let { return@withContext it }
        }
        var name = uri.lastPathSegment ?: ""
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
        }
        val mime = mimeHint ?: resolver.getType(uri) ?: ""
        val video = mime.startsWith("video")
        var w = 0; var h = 0
        if (!video) runCatching {
            resolver.openInputStream(uri)?.use { s ->
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(s, null, o); w = o.outWidth; h = o.outHeight
            }
        }
        Media(
            id = -1L, uri = uri, isVideo = video, mime = mime, dateTaken = System.currentTimeMillis(),
            dateAdded = 0, dateModified = 0, width = w, height = h, orientation = 0, duration = 0, size = size,
            bucketId = 0, bucketName = "", relativePath = "", displayName = name, isFavorite = false,
        )
    }

    // ---- system requests (each shows a system confirmation unless Photos may manage media) ----
    fun trashRequest(items: List<Media>, trash: Boolean): IntentSender? = runCatching {
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash).intentSender
    }.getOrNull()

    fun favoriteRequest(items: List<Media>, favorite: Boolean): IntentSender? = runCatching {
        MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite).intentSender
    }.getOrNull()

    fun writeRequest(items: List<Media>): IntentSender? = runCatching {
        MediaStore.createWriteRequest(resolver, items.map { it.uri }).intentSender
    }.getOrNull()

    fun deleteRequest(items: List<Media>): IntentSender? = runCatching {
        MediaStore.createDeleteRequest(resolver, items.map { it.uri }).intentSender
    }.getOrNull()

    fun canManageMedia(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) MediaStore.canManageMedia(context) else false

    /** Copies an item to a new library entry next to the original (or Pictures / Movies). */
    suspend fun duplicate(m: Media): Boolean = withContext(Dispatchers.IO) {
        val base = m.displayName.substringBeforeLast('.', m.displayName)
        val ext = m.displayName.substringAfterLast('.', if (m.isVideo) "mp4" else "jpg")
        val allowed = if (m.isVideo) listOf("DCIM/", "Movies/", "Pictures/") else listOf("DCIM/", "Pictures/")
        val rel = m.relativePath.takeIf { p -> allowed.any { p.startsWith(it) } } ?: if (m.isVideo) "Movies/" else "Pictures/"
        insertCopy(
            openInput = { resolver.openInputStream(m.uri) },
            name = "$base (1).$ext", mime = m.mime, relPath = rel, dateTaken = m.dateTaken, video = m.isVideo,
        ) != null
    }

    fun insertCopy(openInput: () -> java.io.InputStream?, name: String, mime: String, relPath: String, dateTaken: Long, video: Boolean): Uri? {
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            openInput()?.use { input -> resolver.openOutputStream(dest)?.use { out -> input.copyTo(out, 1 shl 16) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(dest, null, null) }
            return null
        }
        runCatching { resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
        return dest
    }

    /** Copies items into private storage for Hidden. Returns the ones that were copied. */
    suspend fun copyToHidden(items: List<Media>): List<Media> = withContext(Dispatchers.IO) {
        items.filter { m -> hidden.import(m) { resolver.openInputStream(m.uri) } }
    }

    suspend fun rollbackHidden(items: List<Media>) = withContext(Dispatchers.IO) { hidden.removeImported(items.map { it.id }) }

    suspend fun unhide(items: List<Media>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (m in items) {
            val file = File(hidden.dir, m.hiddenFile ?: continue)
            val allowed = if (m.isVideo) listOf("DCIM/", "Movies/", "Pictures/") else listOf("DCIM/", "Pictures/")
            val rel = m.relativePath.takeIf { p -> allowed.any { p.startsWith(it) } } ?: if (m.isVideo) "Movies/" else "Pictures/"
            val uri = insertCopy({ file.inputStream() }, m.displayName.ifBlank { file.name }, m.mime, rel, m.dateTaken, m.isVideo)
            if (uri != null) {
                hidden.delete(m)
                n++
            }
        }
        refresh(200)
        n
    }

    suspend fun deleteHidden(items: List<Media>) = withContext(Dispatchers.IO) { items.forEach { hidden.delete(it) } }
}
