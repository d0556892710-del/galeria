package com.galeria.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Changes a photo's own date and place (written into the file, so every app sees it),
 * like Adjust Date & Time / Adjust Location on iPhone. Write access must already be granted.
 */
object Metadata {
    /** Formats whose metadata Android can rewrite in place. */
    fun writable(m: Media): Boolean = !m.isVideo && m.hiddenFile == null && (
        m.mime.equals("image/jpeg", true) || m.mime.equals("image/png", true) || m.mime.equals("image/webp", true) ||
            m.displayName.endsWith(".jpg", true) || m.displayName.endsWith(".jpeg", true)
        )

    suspend fun setDate(context: Context, m: Media, ms: Long): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        if (writable(m)) ok = runCatching {
            context.contentResolver.openFileDescriptor(m.uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                val s = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(ms))
                ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s)
                ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, s)
                ex.setAttribute(ExifInterface.TAG_DATETIME, s)
                val off = TimeZone.getDefault().getOffset(ms) / 60000
                val o = String.format(Locale.US, "%s%02d:%02d", if (off < 0) "-" else "+", abs(off) / 60, abs(off) % 60)
                ex.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, o)
                ex.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, o)
                ex.setAttribute(ExifInterface.TAG_OFFSET_TIME, o)
                ex.saveAttributes()
                true
            } ?: false
        }.getOrDefault(false)
        // The library's own record (also the only way for videos).
        val row = runCatching {
            context.contentResolver.update(m.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, ms) }, null, null) > 0
        }.getOrDefault(false)
        ok || row
    }

    suspend fun setLocation(context: Context, m: Media, latLon: DoubleArray?): Boolean = withContext(Dispatchers.IO) {
        if (!writable(m)) return@withContext false
        runCatching {
            context.contentResolver.openFileDescriptor(m.uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                if (latLon == null) {
                    listOf(
                        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ).forEach { ex.setAttribute(it, null) }
                } else ex.setLatLong(latLon[0], latLon[1])
                ex.saveAttributes()
                true
            } ?: false
        }.getOrDefault(false)
    }

    /** Where a photo was taken (needs the media-location permission to see it). */
    suspend fun location(context: Context, m: Media): DoubleArray? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = if (m.hiddenFile == null && context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
                MediaStore.setRequireOriginal(m.uri) else m.uri
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }
        }.getOrNull()
    }
}

/** What Copy Edits / Copy Location put aside for pasting on other photos. */
object MetaClipboard {
    var location: DoubleArray? = null
    var editsJson: String? = null
}
