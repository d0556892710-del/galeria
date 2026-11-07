package com.galeria.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Point
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The grid's own thumbnail engine.
 *
 * - Thumbnails come from Android's thumbnail cache (the same files the system gallery uses).
 * - They are decoded straight into GPU memory (hardware bitmaps), so scrolling never uploads textures.
 * - A memory cache answers synchronously: a cell that scrolls back into view has its picture
 *   in the very first frame, with no blank flash.
 * - Loads that scroll away before they start are cancelled; identical requests share one decode.
 */
object ThumbLoader {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Photos", "background task failed", e) })

    /** What is on screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(8)

    /** Look-ahead: only spare capacity, so it never delays what you are looking at. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioLow = Dispatchers.IO.limitedParallelism(2)

    private val budgetKb = (Runtime.getRuntime().maxMemory() / 3 / 1024).toInt().coerceIn(48 * 1024, 320 * 1024)
    private val cache = object : LruCache<String, ImageBitmap>(budgetKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.width * value.height * 4 / 1024)
    }

    private class Pending(val high: Boolean) {
        lateinit var job: Deferred<ImageBitmap?>
        var waiters = 0
        @Volatile var started = false
    }
    private val inflight = HashMap<String, Pending>()
    private val lock = Any()

    private val buckets = intArrayOf(96, 192, 320, 512, 768, 1080, 1440)

    fun init(c: Context) {
        app = c.applicationContext
    }

    fun key(m: Media, px: Int) = "${m.hiddenFile ?: m.uri}#$px#${m.dateModified}"

    /** The best picture already in memory, and whether it is sharp enough to skip loading. */
    fun peek(m: Media, px: Int): Pair<ImageBitmap?, Boolean> {
        cache.get(key(m, px))?.let { return it to true }
        for (b in buckets) if (b > px) cache.get(key(m, b))?.let { return it to true }
        for (b in buckets.reversed()) if (b < px) cache.get(key(m, b))?.let { return it to false }
        return null to false
    }

    fun clear() = cache.evictAll()

    /**
     * [high] = needed on screen now. A look-ahead request that has not started yet is replaced by
     * an on-screen one, so visible cells never wait behind prefetching.
     */
    suspend fun load(m: Media, px: Int, high: Boolean = true): ImageBitmap? {
        val k = key(m, px)
        cache.get(k)?.let { return it }
        val p = synchronized(lock) {
            var cur = inflight[k]
            if (cur != null && high && !cur.high && !cur.started) {
                cur.job.cancel(); inflight.remove(k, cur); cur = null
            }
            (cur ?: newPending(k, m, px, high).also { inflight[k] = it }).also { it.waiters++ }
        }
        try {
            return p.job.await()
        } finally {
            synchronized(lock) {
                p.waiters--
                if (p.waiters <= 0 && !p.job.isCompleted) {
                    p.job.cancel()
                    inflight.remove(k, p)
                }
            }
        }
    }

    private fun newPending(k: String, m: Media, px: Int, high: Boolean): Pending {
        val p = Pending(high)
        p.job = scope.async(if (high) io else ioLow, start = CoroutineStart.LAZY) {
            p.started = true
            val img = runCatching { decode(m, px) }.getOrNull()?.asImageBitmap()
            if (img != null) cache.put(k, img)
            synchronized(lock) { inflight.remove(k, p) }
            img
        }
        p.job.start()
        return p
    }

    /** Thrown from the header callback when the system thumbnail is smaller than the cell needs. */
    private class TooSmall : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun listener(px: Int, needSharp: Boolean) = ImageDecoder.OnHeaderDecodedListener { d, info, _ ->
        val w = info.size.width; val h = info.size.height
        val short = min(w, h)
        // Never upscale a small cached thumbnail into a big cell: that is what looks blurry.
        if (needSharp && short < px * .72f) throw TooSmall()
        d.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        if (short > px) {
            val s = px.toFloat() / short
            d.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        }
    }

    /** A sharp frame straight from the video, sized for the cell. */
    private fun videoFrame(m: Media, px: Int): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(app, m.uri)
            val vw = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) vh to vw else vw to vh
            val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val b = if (dw > 0 && dh > 0 && min(dw, dh) > px) {
                val s = px.toFloat() / min(dw, dh)
                r.getScaledFrameAtTime(-1L, opt, max(1, (dw * s).roundToInt()), max(1, (dh * s).roundToInt()))
            } else r.getFrameAtTime(-1L, opt)
            return b?.let { it.copy(Bitmap.Config.HARDWARE, false)?.also { _ -> it.recycle() } ?: it }
        } finally {
            runCatching { r.release() }
        }
    }

    private suspend fun decode(m: Media, px: Int): Bitmap? {
        coroutineContext.ensureActive()
        val signal = CancellationSignal()
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (it != null) signal.cancel() }
        try {
            val listener = listener(px, needSharp = false)
            val f = m.hiddenFile
            if (f == null) {
                val opts = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(px, px)) }
                val thumb = ImageDecoder.createSource {
                    app.contentResolver.openTypedAssetFileDescriptor(m.uri, "image/*", opts, signal) ?: throw FileNotFoundException(m.uri.toString())
                }
                if (m.isVideo) {
                    // Big video cells: a frame from the video itself is far sharper than the cached thumbnail.
                    if (px >= 512) runCatching { videoFrame(m, px) }.getOrNull()?.let { return it }
                } else {
                    // Photos: the system's cached thumbnail when it is big enough (fast),
                    // otherwise the photo itself, scaled to exactly what the cell needs (sharp).
                    if (px < 768) {
                        try {
                            return ImageDecoder.decodeBitmap(thumb, listener(px, needSharp = true))
                        } catch (e: TooSmall) {
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                        }
                    }
                    coroutineContext.ensureActive()
                    runCatching { return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, m.uri), listener) }
                }
            }
            if (f != null) {
                val file = File(f)
                if (m.isVideo) {
                    val b = ThumbnailUtils.createVideoThumbnail(file, Size(px, px), signal)
                    return b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
                }
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), listener)
            }
            val opts = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(px, px)) }
            val src = ImageDecoder.createSource {
                app.contentResolver.openTypedAssetFileDescriptor(m.uri, "image/*", opts, signal) ?: throw FileNotFoundException(m.uri.toString())
            }
            return try {
                ImageDecoder.decodeBitmap(src, listener)
            } catch (t: Throwable) {
                coroutineContext.ensureActive()
                // Some providers refuse typed thumbnails: fall back to the system helper.
                val b = app.contentResolver.loadThumbnail(m.uri, Size(px, px), signal)
                b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
            }
        } finally {
            handle?.dispose()
        }
    }
}
