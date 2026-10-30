package com.alphainventor.filemanager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.alphainventor.filemanager.cloud.CloudManager
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 İki Katmanlı (RAM LruCache + Disk Cache) Hızlı Küçük Resim Yöneticisi:
 * - Hem "path|lastModified|isVideo" anahtarıyla hem de doğrudan dosya yolu (pathCache) ile 0 ms RAM erişimi sağlar;
 *   böylece Adapters.kt ve ImageViewerActivity içindeki ThumbnailCacheManager.getCached(path) çağrıları anında yanıt verir.
 * - Disk önbelleği (cacheDir/fm_thumb_disk_cache) sayesinde uygulama yeniden açılsa veya klasörler arasında
 *   geçiş yapılsa bile MediaMetadataRetriever tekrar çalıştırılmaz; küçük resimler diskten 1-2 ms içinde yüklenir.
 */
object ThumbnailCacheManager {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 5).coerceAtLeast(16384)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val scope = CoroutineScope(SupervisorJob() + thumbDispatcher)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = (bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    // Doğrudan path üzerinden 0 ms erişim için ikincil indeks
    private val pathCache = ConcurrentHashMap<String, Bitmap>()

    // 120Hz Uygulamalar ve Bellek Analizi kaydırması için uygulama ikon önbelleği
    private val appIconCache = ConcurrentHashMap<String, Drawable>()

    @Volatile
    private var diskCacheDir: File? = null

    private fun ensureDiskCacheDir(context: Context): File {
        val existing = diskCacheDir
        if (existing != null && existing.exists()) return existing
        val dir = File(context.applicationContext.cacheDir, "fm_thumb_disk_cache")
        if (!dir.exists()) dir.mkdirs()
        diskCacheDir = dir
        return dir
    }

    fun getCached(path: String): Bitmap? {
        if (path.isBlank()) return null
        memoryCache.get(path)?.takeIf { !it.isRecycled }?.let { return it }
        return pathCache[path]?.takeIf { !it.isRecycled }
    }

    fun putCached(path: String, bitmap: Bitmap) {
        if (path.isBlank() || bitmap.isRecycled) return
        memoryCache.put(path, bitmap)
        pathCache[path] = bitmap
    }

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
        loadThumbnailByPath(file.absolutePath, file.lastModified(), isVideo = false, imageView = imageView, fallbackRes = fallbackRes)
    }

    fun loadVideoThumbnail(file: File, imageView: ImageView, fallbackRes: Int) {
        loadThumbnailByPath(file.absolutePath, file.lastModified(), isVideo = true, imageView = imageView, fallbackRes = fallbackRes)
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
        val cached = memoryCache.get(key)?.takeIf { !it.isRecycled }
            ?: pathCache[path]?.takeIf { !it.isRecycled }
        if (cached != null) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setImageBitmap(cached)
            return
        }
        if (fallbackRes != 0) {
            imageView.setImageResource(fallbackRes)
        } else {
            imageView.setImageDrawable(null)
        }
        val appContext = imageView.context.applicationContext
        scope.launch {
            val diskDir = ensureDiskCacheDir(appContext)
            val diskFile = File(diskDir, md5Key(key) + ".jpg")
            var bmp: Bitmap? = null

            if (diskFile.exists() && diskFile.length() > 0L) {
                bmp = try {
                    BitmapFactory.decodeFile(diskFile.absolutePath)
                } catch (_: Throwable) {
                    null
                }
            }

            if (bmp == null) {
                bmp = decodeSampledPreview(path, isVideo, 240)
                if (bmp != null) {
                    try {
                        FileOutputStream(diskFile).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            if (bmp != null && !bmp.isRecycled) {
                memoryCache.put(key, bmp)
                pathCache[path] = bmp
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
            if (path.startsWith("http://") || path.startsWith("https://")) {
                val req = Request.Builder().url(path).get().build()
                CloudManager.httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }

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
                    try {
                        retriever.release()
                    } catch (_: Throwable) {
                    }
                }
            } else {
                // Önce gömülü EXIF küçük resmini kontrol et (çok daha hızlı)
                val exifOrientation = try {
                    val exif = ExifInterface(path)
                    if (exif.hasThumbnail()) {
                        val thumbBytes = exif.thumbnailBytes
                        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                            val exifBmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                            if (exifBmp != null && exifBmp.width >= targetPx / 2) {
                                return rotateBitmapIfRequired(exifBmp, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
                            }
                        }
                    }
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: Throwable) {
                    ExifInterface.ORIENTATION_NORMAL
                }

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
                val rawBmp = BitmapFactory.decodeFile(path, opts) ?: return null
                rotateBitmapIfRequired(rawBmp, exifOrientation)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun md5Key(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            input.hashCode().toUInt().toString(16)
        }
    }

    fun trimMemory() {
        memoryCache.trimToSize(memoryCache.maxSize() / 2)
    }

    fun clear() {
        memoryCache.evictAll()
        pathCache.clear()
    }
}
