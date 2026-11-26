package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import kotlin.math.max

/** Spatial Scenes need the GPU drawing of Android 13+. */
val spatialSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

/**
 * Remembers what the depth model said about each photo (on disk, so a photo is looked at once), and
 * builds scenes on demand.
 */
object SpatialStore {
    private val analysis = java.util.concurrent.ConcurrentHashMap<String, Spatial.Analysis>()
    private val depth = object : android.util.LruCache<String, Depth.Map>(6) {}

    private fun key(m: Media) = "${m.id}_${m.dateModified}"
    private fun dir(context: Context) = File(context.cacheDir, "depth").apply { mkdirs() }

    fun known(m: Media): Spatial.Analysis? = analysis[key(m)]

    /** Whether [m] can become a Spatial Scene (runs the depth model the first time; background thread). */
    fun analyse(context: Context, m: Media): Spatial.Analysis? {
        if (!spatialSupported || m.isVideo || m.isScreenshot || !Depth.available(context)) return null
        val k = key(m)
        analysis[k]?.let { return it }
        val meta = File(dir(context), "$k.txt")
        if (meta.exists()) runCatching {
            val p = meta.readText().trim().split(' ')
            val a = Spatial.Analysis(p[0] == "1", p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
            analysis[k] = a; return a
        }
        val t0 = System.currentTimeMillis()
        val bmp = decode(context, m, 1024) ?: return null
        val d = try { Depth.estimate(context, bmp) } finally { bmp.recycle() }
        val a = Spatial.analyse(d)
        analysis[k] = a; depth.put(k, d)
        runCatching {
            meta.writeText("${if (a.eligible) 1 else 0} ${a.cut} ${a.focus} ${a.score}")
            File(dir(context), "$k.png").outputStream().use { o -> d.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, o) }
        }
        android.util.Log.i("PhotosPerf", "spatial analyse id=${m.id} eligible=${a.eligible} score=${"%.2f".format(a.score)} ms=${System.currentTimeMillis() - t0}")
        return a
    }

    /** The scene for [m] (background thread). */
    fun scene(context: Context, m: Media): Pair<Spatial.Scene, Bitmap>? {
        val a = analyse(context, m)?.takeIf { it.eligible } ?: return null
        val k = key(m)
        val t0 = System.currentTimeMillis()
        val d = depth.get(k) ?: runCatching {
            BitmapLoad.png(File(dir(context), "$k.png"))?.let { Depth.Map.fromBitmap(it) }
        }.getOrNull() ?: decode(context, m, 1024)?.let { b -> try { Depth.estimate(context, b) } finally { b.recycle() } } ?: return null
        val photo = decode(context, m, 1280) ?: return null
        val s = Spatial.build(m.id, photo, d, a)
        android.util.Log.i("PhotosPerf", "spatial scene id=${m.id} ${photo.width}x${photo.height} ms=${System.currentTimeMillis() - t0}")
        return s to photo
    }

    fun decode(context: Context, m: Media, side: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, m.uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = side.toFloat() / max(info.size.width, info.size.height)
            if (s < 1f) dec.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    private object BitmapLoad {
        fun png(f: File): Bitmap? = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
    }
}
