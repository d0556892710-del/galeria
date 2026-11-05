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

class PhotosApplication : Application(), ImageLoaderFactory {
    lateinit var repo: MediaRepository
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        repo = MediaRepository(this)
        settings = SettingsStore(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ThumbFetcher.Factory(this@PhotosApplication))
            add(ThumbKeyer())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.35).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(false)
        .allowRgb565(false)
        .build()
}
