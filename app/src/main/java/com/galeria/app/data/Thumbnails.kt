package com.galeria.app.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options

/**
 * Grid thumbnails come straight from Android's own thumbnail cache (ContentResolver.loadThumbnail),
 * which is what makes scrolling a large library fast. Sizes are bucketed so zoom levels share entries.
 */
data class Thumb(val uri: Uri, val px: Int, val version: Long = 0L)

fun thumbBucket(px: Int): Int = when {
    px <= 96 -> 96
    px <= 192 -> 192
    px <= 320 -> 320
    px <= 512 -> 512
    else -> 768
}

class ThumbFetcher(private val data: Thumb, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bmp = context.contentResolver.loadThumbnail(data.uri, Size(data.px, data.px), null)
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bmp),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.uri.scheme == "content") ThumbFetcher(data, context) else null
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "${data.uri}#${data.px}#${data.version}"
}
