package com.galeria.app.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/** One photo or video. [hiddenFile] is set for items stored privately in Hidden. */
@Immutable
data class Media(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val mime: String,
    val dateTaken: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val duration: Long,
    val size: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val displayName: String,
    val isFavorite: Boolean,
    val expiresAt: Long = 0L,
    val hiddenFile: String? = null,
) {
    /** Width / height as displayed (orientation applied). */
    val aspect: Float
        get() {
            val w = if (orientation == 90 || orientation == 270) height else width
            val h = if (orientation == 90 || orientation == 270) width else height
            return if (w > 0 && h > 0) w.toFloat() / h else 1f
        }
    val displayWidth: Int get() = if (orientation == 90 || orientation == 270) height else width
    val displayHeight: Int get() = if (orientation == 90 || orientation == 270) width else height

    val isScreenshot: Boolean
        get() = !isVideo && (relativePath.contains("Screenshot", true) || bucketName.contains("Screenshot", true) ||
            displayName.startsWith("Screenshot", true))
    val isScreenRecording: Boolean
        get() = isVideo && (relativePath.contains("ScreenRecord", true) || relativePath.contains("Screen record", true) ||
            bucketName.contains("Screen record", true) || bucketName.contains("ScreenRecord", true))
    val isPanorama: Boolean get() = !isVideo && (aspect >= 2.4f || aspect <= 1f / 2.4f && displayName.contains("PANO", true))
    val isHidden: Boolean get() = hiddenFile != null
}

enum class ThemeMode { System, Light, Dark }
enum class GlassMode { Clear, Tinted }
enum class SortMode { Captured, Added }
enum class LibFilter { All, Favourites, Edited, Photos, Videos, Screenshots }
