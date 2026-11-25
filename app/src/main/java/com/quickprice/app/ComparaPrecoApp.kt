package com.quickprice.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/** Configuração única do Coil, partilhada por todas as Activities. */
class ComparaPrecoApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "coil_product_images"))
                .maxSizeBytes(250L * 1024L * 1024L)
                .build()
        }
        .crossfade(220)
        .build()
}
