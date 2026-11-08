package com.galeria.app.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sets a photo as the wallpaper in one tap: fills the screen, centred, nothing to adjust. */
object Wallpaper {
    const val HOME = WallpaperManager.FLAG_SYSTEM
    const val LOCK = WallpaperManager.FLAG_LOCK
    const val BOTH = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK

    suspend fun set(context: Context, m: Media, which: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val sw = min(bounds.width(), bounds.height())
            val sh = max(bounds.width(), bounds.height())
            val src = m.hiddenFile?.let { ImageDecoder.createSource(File(it)) } ?: ImageDecoder.createSource(context.contentResolver, m.uri)
            // Decode just big enough to cover the screen (orientation from EXIF is applied by the decoder).
            val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = max(sw.toFloat() / info.size.width, sh.toFloat() / info.size.height)
                if (s < 1f) d.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
            }
            // Centre crop to the screen's shape.
            val target = sw.toFloat() / sh
            val crop = if (bmp.width.toFloat() / bmp.height > target) {
                val w = (bmp.height * target).roundToInt().coerceIn(1, bmp.width)
                Rect((bmp.width - w) / 2, 0, (bmp.width - w) / 2 + w, bmp.height)
            } else {
                val h = (bmp.width / target).roundToInt().coerceIn(1, bmp.height)
                Rect(0, (bmp.height - h) / 2, bmp.width, (bmp.height - h) / 2 + h)
            }
            val cut = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
            val out = if (cut.width > sw) Bitmap.createScaledBitmap(cut, sw, sh, true) else cut
            val jpeg = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 96, it) }.toByteArray()
            if (out !== cut) out.recycle()
            if (cut !== bmp) cut.recycle()
            bmp.recycle()
            WallpaperManager.getInstance(context).setStream(ByteArrayInputStream(jpeg), null, true, which)
            true
        }.getOrDefault(false)
    }
}
