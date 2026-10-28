package com.alphainventor.filemanager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ThumbnailCacheManager {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(8192)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val scope = CoroutineScope(SupervisorJob() + thumbDispatcher)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    // 120Hz Uygulamalar ve Bellek Analizi kaydırması için uygulama ikon önbelleği
    private val appIconCache = ConcurrentHashMap<String, Drawable>()

    fun getCached(path: String): Bitmap? = memoryCache.get(path)

    fun getCachedAppIcon(packageName: String): Drawable? = appIconCache[packageName]

    fun putCachedAppIcon(packageName: String, drawable: Drawable) {
        appIconCache[packageName] = drawable
    }

    fun loadAppIconAsync(context: Context, packageName: String, imageView: ImageView, fallbackRes: Int) {
        imageView.tag = "app:$packageName"
        val cached = appIconCache[packageName]
        if (cached != null) {
            imageView.setImageDrawable(cached)
            return
        }
        imageView.setImageResource(fallbackRes)
        val appCtx = context.applicationContext
        scope.launch {
            val icon = try {
                appCtx.packageManager.getApplicationIcon(packageName)
            } catch (_: Throwable) {
                null
            }
            if (icon != null) {
                appIconCache[packageName] = icon
                withContext(Dispatchers.Main) {
                    if (imageView.tag == "app:$packageName") {
                        imageView.setImageDrawable(icon)
                    }
                }
            }
        }
    }

    fun loadImageThumbnail(file: File, imageView: ImageView, fallbackRes: Int) {
        loadThumbnailByPath(file.absolutePath, 0L, isVideo = false, imageView = imageView, fallbackRes = fallbackRes)
    }

    fun loadVideoThumbnail(file: File, imageView: ImageView, fallbackRes: Int) {
        loadThumbnailByPath(file.absolutePath, 0L, isVideo = true, imageView = imageView, fallbackRes = fallbackRes)
    }

    fun loadThumbnailByPath(
        path: String,
        lastModified: Long = 0L,
        isVideo: Boolean,
        imageView: ImageView,
        fallbackRes: Int
    ) {
        if (path.isBlank()) {
            if (fallbackRes != 0) imageView.setImageResource(fallbackRes) else imageView.setImageDrawable(null)
            return
        }
        val key = "$path|$lastModified|$isVideo"
        imageView.tag = key
        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setImageBitmap(cached)
            return
        }
        if (fallbackRes != 0) {
            imageView.setImageResource(fallbackRes)
        } else {
            imageView.setImageDrawable(null)
        }
        scope.launch {
            val bmp = decodeSampledPreview(path, isVideo, 200)
            if (bmp != null) {
                memoryCache.put(key, bmp)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) {
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun decodeSampledPreview(path: String, isVideo: Boolean, targetPx: Int): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            if (isVideo) {
                val fastThumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ThumbnailUtils.createVideoThumbnail(file, Size(targetPx, targetPx), null)
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                }
                if (fastThumb != null) return fastThumb

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { ThumbnailUtils.extractThumbnail(it, targetPx, targetPx) }
                } finally {
                    retriever.release()
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(path, opts)
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
