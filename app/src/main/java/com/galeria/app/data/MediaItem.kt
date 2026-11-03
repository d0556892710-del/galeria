package com.galeria.app.data

import android.net.Uri

enum class MediaType { IMAGE, VIDEO, GIF }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateTaken: Long,          // milliseconds
    val durationMs: Long = 0,    // video duration, 0 for images
    val type: MediaType = MediaType.IMAGE,
    val width: Int = 0,
    val height: Int = 0,
    val path: String = "",
    var isHidden: Boolean = false,
    var isFavorite: Boolean = false
) {
    val isVideo get() = type == MediaType.VIDEO
    val isGif   get() = type == MediaType.GIF
    val isImage get() = type == MediaType.IMAGE

    /** e.g. "0:35" */
    val durationFormatted: String get() {
        if (durationMs <= 0) return ""
        val sec  = (durationMs / 1000).toInt()
        val min  = sec / 60
        val rem  = sec % 60
        return "%d:%02d".format(min, rem)
    }
}
