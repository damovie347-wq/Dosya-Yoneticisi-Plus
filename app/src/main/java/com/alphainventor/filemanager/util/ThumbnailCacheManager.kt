package com.alphainventor.filemanager.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File

object ThumbnailCacheManager {
    // Maksimum RAM'in sadece 1/10'unu kullanır, RGB_565 ile yarı yarıya bellek tasarrufu sağlar
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 10).coerceAtLeast(4096)

    // CPU çekirdeklerini yormamak ve ısınmayı önlemek için maksimum 3 iş parçacığı ile sınırlandırılmış havuz
    @OptIn(ExperimentalCoroutinesApi::class)
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val scope = CoroutineScope(SupervisorJob() + thumbDispatcher)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    fun getCached(path: String): Bitmap? = memoryCache.get(path)

    fun loadThumbnailAsync(file: File, isVideo: Boolean, imageView: ImageView, fallbackRes: Int) {
        val key = "${file.absolutePath}_${file.lastModified()}"
        imageView.tag = key
        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageResource(fallbackRes)
        scope.launch {
            val bmp = decodeSampledPreview(file, isVideo, 144)
            if (bmp != null) {
                memoryCache.put(key, bmp)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun decodeSampledPreview(file: File, isVideo: Boolean, targetPx: Int): Bitmap? {
        return try {
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { ThumbnailUtils.extractThumbnail(it, targetPx, targetPx) }
                } finally {
                    retriever.release()
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun trimMemory() {
        memoryCache.trimToSize(memoryCache.maxSize() / 2)
    }

    fun clear() {
        memoryCache.evictAll()
    }
}
