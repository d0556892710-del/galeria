package com.galeria.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

/** Live Photo playback styles (the LIVE menu in the viewer). */
enum class LiveEffect { Live, Loop, Bounce, LongExposure, Off }

/**
 * Non-destructive editing, the iOS way: the untouched original is kept privately the first time a
 * photo is edited, the slider values are remembered, and every later edit re-renders from the
 * original. "Revert to Original" puts the original bytes back.
 */
class EditStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("edits", Context.MODE_PRIVATE)
    private val livePrefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "originals").apply { mkdirs() }

    private val _effects = MutableStateFlow(readEffects())
    val effects: StateFlow<Map<Long, LiveEffect>> = _effects.asStateFlow()

    fun params(id: Long): String? = prefs.getString(id.toString(), null)
    fun setParams(id: Long, json: String?) {
        prefs.edit().apply { if (json == null) remove(id.toString()) else putString(id.toString(), json) }.apply()
    }

    fun backup(m: Media) = File(dir, m.id.toString())
    fun hasBackup(m: Media) = backup(m).exists()

    private fun originalUri(m: Media): Uri =
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
            runCatching { MediaStore.setRequireOriginal(m.uri) }.getOrDefault(m.uri) else m.uri

    /** Keep the untouched original (once). */
    suspend fun ensureBackup(m: Media): Boolean = withContext(Dispatchers.IO) {
        val f = backup(m)
        if (f.exists() && f.length() > 0) return@withContext true
        runCatching {
            val tmp = File(dir, "${m.id}.tmp")
            val input = runCatching { context.contentResolver.openInputStream(originalUri(m)) }.getOrNull()
                ?: context.contentResolver.openInputStream(m.uri)
            input?.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            tmp.length() > 0 && tmp.renameTo(f)
        }.getOrDefault(false)
    }

    /** The source to edit from: the kept original if there is one, otherwise the file itself. */
    fun sourceFile(m: Media): File? = backup(m).takeIf { it.exists() && it.length() > 0 }

    /** Decode for the editor preview (software, long side ≤ [maxSide]). */
    suspend fun decodePreview(m: Media, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val f = sourceFile(m)
            val src = if (f != null) ImageDecoder.createSource(f) else ImageDecoder.createSource(context.contentResolver, m.uri)
            ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
                val w = info.size.width; val h = info.size.height
                val s = maxSide.toFloat() / max(w, h)
                if (s < 1f) d.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }.getOrNull()
    }

    /** Decode at full size (capped) for saving. */
    suspend fun decodeFull(m: Media, maxSide: Int = 6144): Bitmap? = decodePreview(m, maxSide)

    /** A frame of a Live Photo's video (for a new key photo). */
    fun frameAt(video: File, us: Long, maxSide: Int): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            val s = if (dw > 0 && dh > 0) minOf(1f, maxSide.toFloat() / max(dw, dh)) else 1f
            if (s < 1f && dw > 0) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, (dw * s).toInt(), (dh * s).toInt())
            else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            r.release()
        }
    }.getOrNull()

    /** JPEG bytes with the original's EXIF (date, camera, place) and upright orientation. */
    suspend fun encode(m: Media, bmp: Bitmap, png: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
        if (png) return@withContext out.toByteArray()
        val tmp = File(context.cacheDir, "edit_${m.id}.jpg")
        tmp.writeBytes(out.toByteArray())
        runCatching {
            val srcExif = sourceFile(m)?.let { ExifInterface(it) }
                ?: context.contentResolver.openInputStream(originalUri(m))?.use { ExifInterface(it) }
            val dst = ExifInterface(tmp)
            if (srcExif != null) for (tag in EXIF_TAGS) srcExif.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bmp.width.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bmp.height.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Photos")
            dst.saveAttributes()
        }
        tmp.readBytes().also { tmp.delete() }
    }

    /** The Live Photo video bytes of the kept original (or of the file). */
    fun originalVideo(m: Media, motion: MotionStore): ByteArray? = runCatching {
        val f = sourceFile(m)
        if (f != null) {
            val xmp = f.inputStream().use { MotionStore.readXmp(it) } ?: ""
            val info = motion.infoFromXmp(xmp, f.length()) { motion.findMp4InFile(f) } ?: return null
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(info.start)
                ByteArray(info.length.toInt()).also { raf.readFully(it) }
            }
        } else {
            val info = motion.info(m) ?: return null
            context.contentResolver.openFileDescriptor(m.uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val buf = java.nio.ByteBuffer.allocate(info.length.toInt())
                    ch.position(info.start)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    buf.array()
                }
            }
        }
    }.getOrNull()

    /** Overwrite the photo (write access must already be granted). */
    suspend fun write(m: Media, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(m.uri, "wt")?.use { it.write(bytes); true } ?: false
        }.getOrDefault(false)
    }

    /** Put the original back. */
    suspend fun revert(m: Media): Boolean = withContext(Dispatchers.IO) {
        val f = backup(m)
        if (!f.exists()) return@withContext false
        val ok = write(m, f.readBytes())
        if (ok) { f.delete(); setParams(m.id, null) }
        ok
    }

    // ---------------------------------------------------------------- Live effects
    fun effect(id: Long): LiveEffect = _effects.value[id] ?: LiveEffect.Live
    fun setEffect(id: Long, e: LiveEffect) {
        livePrefs.edit().apply { if (e == LiveEffect.Live) remove("e$id") else putString("e$id", e.name) }.apply()
        _effects.value = readEffects()
    }
    private fun readEffects(): Map<Long, LiveEffect> = livePrefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("e")) return@mapNotNull null
        val id = k.drop(1).toLongOrNull() ?: return@mapNotNull null
        val e = runCatching { LiveEffect.valueOf(v as String) }.getOrNull() ?: return@mapNotNull null
        id to e
    }.toMap()

    @Suppress("unused")
    private fun decodeBounds(f: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(f.absolutePath, this) }

    companion object {
        private val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
    }
}
