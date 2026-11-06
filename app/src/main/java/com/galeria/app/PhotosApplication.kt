package com.galeria.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.galeria.app.data.MediaRepository
import com.galeria.app.data.SettingsStore
import com.galeria.app.data.ThumbFetcher
import com.galeria.app.data.ThumbKeyer

const val CRASH_FILE = "last_crash.txt"

class PhotosApplication : Application(), ImageLoaderFactory {
    lateinit var repo: MediaRepository
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep a report of any crash, so it can be shown (and sent) on the next launch.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "Photos $v · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT}) · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nThread: ${t.name}\n\n" + e.stackTraceToString().take(12000)
                )
            }
            previous?.uncaughtException(t, e)
        }
        repo = MediaRepository(this)
        settings = SettingsStore(this)
        com.galeria.app.data.ThumbLoader.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ThumbFetcher.Factory(this@PhotosApplication))
            add(ThumbKeyer())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(false)
        .allowRgb565(false)
        .build()
}
