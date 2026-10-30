package com.alphainventor.filemanager.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StorageOverview(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val usedPercent: Int
)

data class RemovableStorageVolume(
    val title: String,
    val path: String,
    val overview: StorageOverview,
    val isUsb: Boolean = false
)

data class CategoryStat(
    val totalBytes: Long,
    val count: Int
)

data class QuickDashboardStats(
    val images: CategoryStat,
    val audio: CategoryStat,
    val videos: CategoryStat,
    val docs: CategoryStat,
    val newFiles: CategoryStat
)

data class StorageAnalysisReport(
    val rootPath: String,
    val rootTitle: String,
    val overview: StorageOverview,
    val imagesBytes: Long,
    val audioBytes: Long,
    val videosBytes: Long,
    val docsBytes: Long,
    val appsBytes: Long,
    val archivesBytes: Long,
    val othersBytes: Long,
    val topFolders: List<FileItem>,
    val duplicateGroups: List<List<FileItem>>,
    val largeFiles: List<FileItem>,
    val installedApps: List<InstalledAppItem>,
    val imagesCount: Int = 0,
    val audioCount: Int = 0,
    val videosCount: Int = 0,
    val docsCount: Int = 0,
    val folderSizeMap: Map<String, Long> = emptyMap(),
    val folderItemCountMap: Map<String, Int> = emptyMap(),
    val cacheBytes: Long = 0L,
    val cachedApps: List<Pair<InstalledAppItem, Long>> = emptyList()
)

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val isSystemApp: Boolean,
    val lastUpdateTime: Long
) {
    val apkSize: Long
        get() = apkSizeBytes
}

data class DrawerBookmarkEntry(
    val title: String,
    val path: String,
    val isDirectory: Boolean = true
)

data class DrawerHistoryEntry(
    val title: String,
    val path: String,
    val section: String,
    val isDirectory: Boolean,
    val timestamp: Long
)

data class ViewerSessionData(
    val paths: ArrayList<String>,
    val titles: ArrayList<String>,
    val initialIndex: Int,
    val authToken: String = ""
)

object LocalFileRepository {

    private const val PREF_DRAWER_DATA = "FileManagerDrawerPrefs"
    private const val KEY_BOOKMARKS_JSON = "bookmarks_v2"
    private const val KEY_HISTORY_JSON = "history_v2"

    val rootStorageDir: File
        get() = Environment.getExternalStorageDirectory()

    val recycleBinDir: File
        get() = File(rootStorageDir, ".DosyaYoneticisiPlus_RecycleBin").apply {
            if (!exists()) mkdirs()
        }

    @Volatile
    var clipboardFiles: List<FileItem> = emptyList()

    @Volatile
    var clipboardIsMove: Boolean = false

    @Volatile
    var lastAnalysisReport: StorageAnalysisReport? = null

    // ==================== ORİJİNAL APK Lax/O1/b; & viewer/c-e BELLEK ÖNBELLEĞİ ====================
    // Medya kategorileri, klasör kutucukları (buckets), yerel klasörler ve görüntüleyici oturumu için
    // 0 ms anlık erişim sağlayan bellek içi önbellek katmanı.
    private val categoryFilesCache = ConcurrentHashMap<String, List<FileItem>>()
    private val categoryBucketsCache = ConcurrentHashMap<String, List<FileItem>>()
    private val bucketFilesMapCache = ConcurrentHashMap<String, List<FileItem>>()
    private val directoryListCache = ConcurrentHashMap<String, List<FileItem>>()
    private val viewerSessionCache = ConcurrentHashMap<String, ViewerSessionData>()

    @Volatile
    private var cachedQuickStats: QuickDashboardStats? = null
    @Volatile
    private var cachedQuickStatsTimeMs: Long = 0L

    fun invalidateCaches() {
        categoryFilesCache.clear()
        categoryBucketsCache.clear()
        bucketFilesMapCache.clear()
        directoryListCache.clear()
        cachedQuickStats = null
    }

    fun putViewerSession(
        paths: ArrayList<String>,
        titles: ArrayList<String>,
        initialIndex: Int,
        authToken: String = ""
    ): String {
        val key = "vs_${System.currentTimeMillis()}"
        if (viewerSessionCache.size > 12) {
            viewerSessionCache.clear()
        }
        viewerSessionCache[key] = ViewerSessionData(paths, titles, initialIndex, authToken)
        return key
    }

    fun getViewerSession(key: String?): ViewerSessionData? {
        if (key.isNullOrBlank()) return null
        return viewerSessionCache[key]
    }

    /**
     * FileExplorerActivity.loadCurrentDirectory içinde UI thread'den çağrılarak
     * önbellekteki klasör veya kategori içeriğini 0 ms'de (loading spinner göstermeden) döndürür.
     */
    fun getCachedExplorerItems(
        mode: String,
        currentPath: String,
        categoryName: String,
        selectedBucketPath: String
    ): List<FileItem>? {
        val showHidden = AppSettings.showHiddenFiles
        val sortBy = AppSettings.sortBy
        val sortAsc = AppSettings.sortAscending
        return when (mode) {
            "LOCAL" -> {
                val raw = directoryListCache["$currentPath|$showHidden"] ?: return null
                sortFileItems(raw, sortBy, sortAsc)
            }
            "CATEGORY" -> {
                if (categoryName in setOf("VIDEOS", "IMAGES", "AUDIO", "DOCUMENTS")) {
                    if (selectedBucketPath.isEmpty()) {
                        val rawBuckets = categoryBucketsCache["$categoryName|$showHidden"] ?: return null
                        sortBucketsList(rawBuckets, sortBy, sortAsc)
                    } else {
                        val rawBucketFiles = bucketFilesMapCache["$categoryName|$showHidden|$selectedBucketPath"] ?: return null
                        sortFileItems(rawBucketFiles, sortBy, sortAsc)
                    }
                } else {
                    val rawCat = categoryFilesCache["$categoryName|$showHidden"] ?: return null
                    sortFileItems(rawCat, sortBy, sortAsc)
                }
            }
            else -> null
        }
    }

    private fun sortBucketsList(buckets: List<FileItem>, sortBy: Int, asc: Boolean): List<FileItem> {
        return when (sortBy) {
            0 -> if (asc) buckets.sortedBy { it.name.lowercase(Locale("tr", "TR")) } else buckets.sortedByDescending { it.name.lowercase(Locale("tr", "TR")) }
            1 -> if (asc) buckets.sortedBy { it.childCount } else buckets.sortedByDescending { it.childCount }
            2, 5 -> if (asc) buckets.sortedBy { it.lastModified } else buckets.sortedByDescending { it.lastModified }
            4 -> buckets
            else -> buckets.sortedWith(compareByDescending<FileItem> { it.childCount }.thenByDescending { it.lastModified })
        }
    }

    fun getStorageOverviewForPath(path: String): StorageOverview {
        return try {
            val stat = StatFs(path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = (total - free).coerceAtLeast(0L)
            val pct = if (total > 0) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
            StorageOverview(total, used, free, pct)
        } catch (_: Throwable) {
            StorageOverview(128_000_000_000L, 84_480_000_000L, 43_520_000_000L, 66)
        }
    }

    fun getStorageOverview(): StorageOverview = getStorageOverviewForPath(rootStorageDir.absolutePath)

    fun getMainStorageOverview(): StorageOverview = getStorageOverview()

    fun getRemovableStorageVolumes(context: Context): List<RemovableStorageVolume> {
        val volumes = LinkedHashMap<String, RemovableStorageVolume>()
        val mainPath = try {
            rootStorageDir.canonicalPath
        } catch (_: Throwable) {
            rootStorageDir.absolutePath
        }

        try {
            val extDirs = context.getExternalFilesDirs(null)
            for (dir in extDirs) {
                if (dir == null) continue
                val abs = dir.absolutePath
                val rootPath = abs.substringBefore("/Android/data").trimEnd('/')
                if (rootPath.isNotEmpty() && rootPath != mainPath && rootPath != rootStorageDir.absolutePath) {
                    val rootFile = File(rootPath)
                    if (rootFile.exists() && rootFile.totalSpace > 0L) {
                        val ov = getStorageOverviewForPath(rootFile.absolutePath)
                        val isUsb = rootPath.contains("usb", ignoreCase = true) || rootPath.contains("otg", ignoreCase = true)
                        volumes[rootPath] = RemovableStorageVolume(
                            title = if (isUsb) "USB bellek" else "SD kart",
                            path = rootPath,
                            overview = ov,
                            isUsb = isUsb
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                val svList = sm?.storageVolumes.orEmpty()
                for (sv in svList) {
                    if (!sv.isPrimary && sv.isRemovable) {
                        val dir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            sv.directory
                        } else {
                            try {
                                val m = sv.javaClass.getMethod("getPathFile")
                                m.invoke(sv) as? File
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        if (dir != null && dir.exists() && dir.totalSpace > 0L) {
                            val p = dir.absolutePath
                            if (!volumes.containsKey(p) && p != rootStorageDir.absolutePath) {
                                val desc = sv.getDescription(context)?.takeIf { it.isNotBlank() } ?: "SD kart"
                                val isUsb = desc.contains("usb", ignoreCase = true)
                                volumes[p] = RemovableStorageVolume(
                                    title = if (isUsb) "USB bellek" else "SD kart",
                                    path = p,
                                    overview = getStorageOverviewForPath(p),
                                    isUsb = isUsb
                                )
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        try {
            val storageRoot = File("/storage")
            val children = storageRoot.listFiles().orEmpty()
            val sdRegex = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
            for (ch in children) {
                if (sdRegex.matches(ch.name) && ch.exists() && ch.totalSpace > 0L) {
                    val p = ch.absolutePath
                    if (!volumes.containsKey(p)) {
                        volumes[p] = RemovableStorageVolume(
                            title = "SD kart",
                            path = p,
                            overview = getStorageOverviewForPath(p),
                            isUsb = false
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return volumes.values.toList()
    }

    suspend fun getQuickDashboardStats(context: Context): QuickDashboardStats = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedQuickStats
        if (cached != null && (now - cachedQuickStatsTimeMs) < 15_000L) {
            return@withContext cached
        }

        val imgStat = queryMediaStoreStat(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val audStat = queryMediaStoreStat(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val vidStat = queryMediaStoreStat(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        val docStat = queryDocumentsStat(context)
        val newStat = queryNewFilesStat(context)

        val result = if (imgStat.count == 0 && vidStat.count == 0 && audStat.count == 0) {
            scanQuickFallbackStats(context)
        } else {
            QuickDashboardStats(
                images = imgStat,
                audio = audStat,
                videos = vidStat,
                docs = docStat,
                newFiles = newStat
            )
        }
        cachedQuickStats = result
        cachedQuickStatsTimeMs = now
        result
    }

    private fun queryMediaStoreStat(context: Context, uri: Uri): CategoryStat {
        var count = 0
        var bytes = 0L
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val p = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    if (p.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                    if (!AppSettings.showHiddenFiles && isHiddenPath(p)) continue
                    count++
                    if (sizeCol >= 0) {
                        bytes += cursor.getLong(sizeCol).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return CategoryStat(bytes, count)
    }

    private fun isHiddenPath(path: String): Boolean {
        val parts = path.split('/')
        for (part in parts) {
            if (part.startsWith(".") && part.length > 1) return true
        }
        return false
    }

    private fun queryDocumentsStat(context: Context): CategoryStat {
        var count = 0
        var bytes = 0L
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}"
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (ext in FileItem.DOC_EXTENSIONS) {
                        val sz = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                        if (sz > 0L) {
                            count++
                            bytes += sz
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return CategoryStat(bytes, count)
    }

    private fun queryNewFilesStat(context: Context): CategoryStat {
        var count = 0
        var bytes = 0L
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val sevenDaysAgoSec = (System.currentTimeMillis() / 1000L) - (7L * 24 * 3600)
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE
            )
            val selection = "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
            val args = arrayOf(sevenDaysAgoSec.toString())
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val p = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    if (p.isNotEmpty() && !p.contains("/Android/data") && !p.contains("/.DosyaYoneticisiPlus_RecycleBin")) {
                        count++
                        if (sizeCol >= 0) bytes += cursor.getLong(sizeCol).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return CategoryStat(bytes, count)
    }

    private fun scanQuickFallbackStats(context: Context): QuickDashboardStats {
        var imgB = 0L; var imgC = 0
        var audB = 0L; var audC = 0
        var vidB = 0L; var vidC = 0
        var docB = 0L; var docC = 0
        var newB = 0L; var newC = 0
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000L

        val roots = mutableListOf(rootStorageDir)
        getRemovableStorageVolumes(context).forEach { volumes ->
            val f = File(volumes.path)
            if (f.exists()) roots.add(f)
        }

        fun walkFast(dir: File, depth: Int) {
            if (depth > 5) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".")) continue
                val p = f.absolutePath
                if (p.contains("/Android/data") || p.contains("/Android/obb")) continue
                if (f.isDirectory) {
                    walkFast(f, depth + 1)
                } else {
                    val len = f.length()
                    val ext = f.extension.lowercase()
                    when {
                        ext in FileItem.IMAGE_EXTENSIONS -> { imgB += len; imgC++ }
                        ext in FileItem.AUDIO_EXTENSIONS -> { audB += len; audC++ }
                        ext in FileItem.VIDEO_EXTENSIONS -> { vidB += len; vidC++ }
                        ext in FileItem.DOC_EXTENSIONS -> { docB += len; docC++ }
                    }
                    if (f.lastModified() >= cutoff) {
                        newB += len
                        newC++
                    }
                }
            }
        }

        for (r in roots) walkFast(r, 0)

        return QuickDashboardStats(
            images = CategoryStat(imgB, imgC),
            audio = CategoryStat(audB, audC),
            videos = CategoryStat(vidB, vidC),
            docs = CategoryStat(docB, docC),
            newFiles = CategoryStat(newB, newC)
        )
    }

    /**
     * Orijinal APK'daki (Lcom/alphainventor/filemanager/file/u; & Lax/O1/b;) Videolar / Görüntüler / Ses / Belgeler
     * klasör gruplama (Folder Bucket) ve 0 ms önbellekleme mantığı:
     * - Tüm klasör kutucuklarını tek geçişte hesaplar ve her klasörün dosya listesini `bucketFilesMapCache` içine kaydeder.
     * - Böylece kullanıcı herhangi bir klasör kutucuğuna tıkladığında 0 ms'de (yeniden tarama olmadan) açılır!
     */
    suspend fun getCategoryFolderBuckets(
        context: Context,
        category: String,
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val showHidden = AppSettings.showHiddenFiles
        val cacheKey = "$category|$showHidden"

        if (!forceRefresh) {
            val cachedBuckets = categoryBucketsCache[cacheKey]
            if (cachedBuckets != null) {
                return@withContext sortBucketsList(cachedBuckets, AppSettings.sortBy, AppSettings.sortAscending)
            }
        }

        val allCategoryFiles = getCategoryFiles(context, category, forceRefresh)
        if (allCategoryFiles.isEmpty()) return@withContext emptyList()

        val grouped = LinkedHashMap<String, MutableList<FileItem>>()
        for (file in allCategoryFiles) {
            val parentPath = file.path.substringBeforeLast('/', rootStorageDir.absolutePath)
            grouped.getOrPut(parentPath) { ArrayList() }.add(file)
        }

        val buckets = ArrayList<FileItem>(grouped.size)
        for ((folderPath, files) in grouped) {
            if (files.isEmpty()) continue
            bucketFilesMapCache["$category|$showHidden|$folderPath"] = files
            val folderName = folderPath.substringAfterLast('/').ifBlank { "Ana bellek" }
            val newestMedia = files.maxByOrNull { it.lastModified } ?: files.first()
            val totalSize = files.sumOf { it.size }
            buckets.add(
                FileItem(
                    id = folderPath,
                    name = folderName,
                    path = folderPath,
                    isDirectory = true,
                    size = totalSize,
                    lastModified = newestMedia.lastModified,
                    childCount = files.size,
                    sourceType = FileSourceType.LOCAL,
                    firstMediaPreviewPath = newestMedia.path,
                    mediaCategoryBadge = category
                )
            )
        }

        categoryBucketsCache[cacheKey] = buckets
        sortBucketsList(buckets, AppSettings.sortBy, AppSettings.sortAscending)
    }

    suspend fun getCategoryFilesInBucket(
        context: Context,
        category: String,
        bucketFolderPath: String,
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val showHidden = AppSettings.showHiddenFiles
        val bucketKey = "$category|$showHidden|$bucketFolderPath"

        if (!forceRefresh) {
            bucketFilesMapCache[bucketKey]?.let { cached ->
                return@withContext sortFileItems(cached, AppSettings.sortBy, AppSettings.sortAscending)
            }
        }

        val all = getCategoryFiles(context, category, forceRefresh)
        val filtered = all.filter { item ->
            item.path.substringBeforeLast('/', "") == bucketFolderPath
        }
        if (filtered.isNotEmpty()) {
            bucketFilesMapCache[bucketKey] = filtered
            return@withContext sortFileItems(filtered, AppSettings.sortBy, AppSettings.sortAscending)
        }
        val dir = File(bucketFolderPath)
        val raw = dir.listFiles().orEmpty()
        val list = ArrayList<FileItem>()
        for (f in raw) {
            if (f.isDirectory) continue
            if (!showHidden && f.name.startsWith(".")) continue
            val fi = FileItem(
                id = f.absolutePath,
                name = f.name,
                path = f.absolutePath,
                isDirectory = false,
                size = f.length(),
                lastModified = f.lastModified(),
                sourceType = FileSourceType.LOCAL
            )
            val match = when (category) {
                "IMAGES" -> fi.isImageFile()
                "AUDIO" -> fi.isAudioFile()
                "VIDEOS" -> fi.isVideoFile()
                "DOCUMENTS" -> fi.isDocumentFile()
                else -> true
            }
            if (match) list.add(fi)
        }
        bucketFilesMapCache[bucketKey] = list
        sortFileItems(list, AppSettings.sortBy, AppSettings.sortAscending)
    }

    suspend fun getCategoryFiles(
        context: Context? = null,
        category: String,
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val showHidden = AppSettings.showHiddenFiles
        val cacheKey = "$category|$showHidden"

        if (!forceRefresh) {
            categoryFilesCache[cacheKey]?.let { cached ->
                return@withContext sortFileItems(cached, AppSettings.sortBy, AppSettings.sortAscending)
            }
        }

        val merged = LinkedHashMap<String, FileItem>()

        if (context != null) {
            val mediaItems = queryMediaStoreCategoryItems(context, category)
            for (item in mediaItems) {
                merged[item.path] = item
            }
        }

        // Eğer "Gizli dosyaları göster" açıksa (veya MediaStore boşsa), MediaStore'un atladığı
        // gizli klasörleri (.aa, .Private, .statuses, .thumbnails, .!%#@$) ve .nomedia klasörlerini de tara!
        if (showHidden || merged.isEmpty()) {
            val scanned = scanCategoryFiles(context, category, onlyHiddenOrNomedia = merged.isNotEmpty() && showHidden)
            for (item in scanned) {
                if (!merged.containsKey(item.path)) {
                    merged[item.path] = item
                }
            }
        }

        val rawList = merged.values.toList()
        categoryFilesCache[cacheKey] = rawList
        sortFileItems(rawList, AppSettings.sortBy, AppSettings.sortAscending)
    }

    private fun queryMediaStoreCategoryItems(context: Context, category: String): List<FileItem> {
        val list = ArrayList<FileItem>()
        val showHidden = AppSettings.showHiddenFiles
        try {
            when (category) {
                "IMAGES", "VIDEOS", "AUDIO" -> {
                    val uri = when (category) {
                        "IMAGES" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "VIDEOS" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.MIME_TYPE
                    )
                    val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                            if (path.isBlank() || path.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                            if (!showHidden && isHiddenPath(path)) continue

                            // Orijinal APK optimizasyonu: MediaStore'dan gelen SIZE ve DATE_MODIFIED > 0 ise
                            // her satır için bloklayıcı JNI File.length() / File.lastModified() çağrısı yapma!
                            val rawSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                            val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                                ?.takeIf { it.isNotBlank() }
                                ?: path.substringAfterLast('/')
                            val size = if (rawSize > 0L) rawSize else File(path).length()
                            if (size <= 0L && !File(path).exists()) continue
                            val lastMod = if (modSec > 0L) modSec * 1000L else File(path).lastModified()
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                            val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Ana bellek" }

                            list.add(
                                FileItem(
                                    id = path,
                                    name = name,
                                    path = path,
                                    isDirectory = false,
                                    size = size,
                                    lastModified = lastMod,
                                    mimeType = mime,
                                    sourceType = FileSourceType.LOCAL,
                                    parentId = parentName
                                )
                            )
                        }
                    }

                    // Gizli dosyalar açıksa MediaStore.Files tablosundaki indekslenmiş diğer medya dosyalarını da ekle
                    if (showHidden) {
                        val filesUri = MediaStore.Files.getContentUri("external")
                        context.contentResolver.query(filesUri, projection, null, null, sortOrder)?.use { cursor ->
                            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                                if (path.isBlank() || path.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                                val ext = path.substringAfterLast('.', "").lowercase()
                                val matchesCat = when (category) {
                                    "VIDEOS" -> ext in FileItem.VIDEO_EXTENSIONS
                                    "IMAGES" -> ext in FileItem.IMAGE_EXTENSIONS
                                    "AUDIO" -> ext in FileItem.AUDIO_EXTENSIONS
                                    else -> false
                                }
                                if (!matchesCat) continue
                                val rawSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                                val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                                val size = if (rawSize > 0L) rawSize else File(path).length()
                                if (size <= 0L) continue
                                val lastMod = if (modSec > 0L) modSec * 1000L else File(path).lastModified()
                                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                                val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Ana bellek" }
                                list.add(
                                    FileItem(
                                        id = path,
                                        name = path.substringAfterLast('/'),
                                        path = path,
                                        isDirectory = false,
                                        size = size,
                                        lastModified = lastMod,
                                        mimeType = mime,
                                        sourceType = FileSourceType.LOCAL,
                                        parentId = parentName
                                    )
                                )
                            }
                        }
                    }
                }
                "DOCUMENTS" -> {
                    val uri = MediaStore.Files.getContentUri("external")
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED,
                        MediaStore.Files.FileColumns.MIME_TYPE
                    )
                    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                    context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                        val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                            if (path.isBlank() || path.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                            if (!showHidden && isHiddenPath(path)) continue
                            val ext = path.substringAfterLast('.', "").lowercase()
                            if (ext !in FileItem.DOC_EXTENSIONS) continue
                            val rawSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                            val size = if (rawSize > 0L) rawSize else File(path).length()
                            if (size <= 0L) continue
                            val lastMod = if (modSec > 0L) modSec * 1000L else File(path).lastModified()
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                            val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Ana bellek" }

                            list.add(
                                FileItem(
                                    id = path,
                                    name = path.substringAfterLast('/'),
                                    path = path,
                                    isDirectory = false,
                                    size = size,
                                    lastModified = lastMod,
                                    mimeType = mime,
                                    sourceType = FileSourceType.LOCAL,
                                    parentId = parentName
                                )
                            )
                        }
                    }
                }
                "NEW_FILES" -> {
                    val uri = MediaStore.Files.getContentUri("external")
                    val sevenDaysAgoSec = (System.currentTimeMillis() / 1000L) - (7L * 24 * 3600)
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED,
                        MediaStore.Files.FileColumns.MIME_TYPE
                    )
                    val selection = "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
                    val args = arrayOf(sevenDaysAgoSec.toString())
                    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                    context.contentResolver.query(uri, projection, selection, args, sortOrder)?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                        val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                            if (path.isBlank() || path.contains("/Android/data") || path.contains("/Android/obb") || path.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                            if (!showHidden && isHiddenPath(path)) continue
                            val rawSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                            val size = if (rawSize > 0L) rawSize else File(path).length()
                            if (size <= 0L) continue
                            val lastMod = if (modSec > 0L) modSec * 1000L else File(path).lastModified()
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                            val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Ana bellek" }

                            list.add(
                                FileItem(
                                    id = path,
                                    name = path.substringAfterLast('/'),
                                    path = path,
                                    isDirectory = false,
                                    size = size,
                                    lastModified = lastMod,
                                    mimeType = mime,
                                    sourceType = FileSourceType.LOCAL,
                                    parentId = parentName,
                                    sectionHeader = FormatUtils.formatSectionDate(lastMod)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    suspend fun listDirectory(
        dir: File,
        showHidden: Boolean = AppSettings.showHiddenFiles,
        sortBy: Int = AppSettings.sortBy,
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val cacheKey = "${dir.absolutePath}|$showHidden"
        if (!forceRefresh) {
            directoryListCache[cacheKey]?.let { cached ->
                return@withContext sortFileItems(cached, sortBy, AppSettings.sortAscending)
            }
        }

        val rawFiles = dir.listFiles() ?: return@withContext emptyList()
        val items = ArrayList<FileItem>(rawFiles.size)
        for (f in rawFiles) {
            if (f.name == ".DosyaYoneticisiPlus_RecycleBin") continue
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            val childCount = if (isDir) {
                f.list()?.count { showHidden || !it.startsWith(".") } ?: 0
            } else 0
            items.add(
                FileItem(
                    id = f.absolutePath,
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else f.length(),
                    lastModified = f.lastModified(),
                    childCount = childCount,
                    sourceType = FileSourceType.LOCAL
                )
            )
        }
        directoryListCache[cacheKey] = items
        sortFileItems(items, sortBy, AppSettings.sortAscending)
    }

    fun sortFileItems(items: List<FileItem>, sortBy: Int, asc: Boolean): List<FileItem> {
        if (sortBy == 4) return items
        return items.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }.let { comp ->
                when (sortBy) {
                    1 -> if (asc) comp.thenBy { it.size } else comp.thenByDescending { it.size }
                    2, 5 -> if (asc) comp.thenBy { it.lastModified } else comp.thenByDescending { it.lastModified }
                    3 -> if (asc) comp.thenBy { it.extension }.thenBy { it.name.lowercase(Locale("tr", "TR")) }
                    else comp.thenByDescending { it.extension }.thenByDescending { it.name.lowercase(Locale("tr", "TR")) }
                    else -> if (asc) comp.thenBy { it.name.lowercase(Locale("tr", "TR")) } else comp.thenByDescending { it.name.lowercase(Locale("tr", "TR")) }
                }
            }
        )
    }

    suspend fun scanCategoryFiles(
        context: Context? = null,
        category: String,
        onlyHiddenOrNomedia: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val results = ArrayList<FileItem>()
        val roots = mutableListOf(rootStorageDir)
        if (context != null) {
            getRemovableStorageVolumes(context).forEach { vol ->
                val f = File(vol.path)
                if (f.exists()) roots.add(f)
            }
        }
        val showHidden = AppSettings.showHiddenFiles

        fun walkDir(dir: File, depth: Int, insideHiddenOrNomedia: Boolean) {
            if (depth > 6) return
            val dirName = dir.name
            if (dirName == ".DosyaYoneticisiPlus_RecycleBin") return
            val absPath = dir.absolutePath
            if (absPath.contains("/Android/data") || absPath.contains("/Android/obb")) return

            val children = dir.listFiles() ?: return
            val hasNomedia = children.any { it.name.equals(".nomedia", ignoreCase = true) }
            val currentHiddenCtx = insideHiddenOrNomedia || dirName.startsWith(".") || hasNomedia

            for (f in children) {
                if (f.name == ".DosyaYoneticisiPlus_RecycleBin") continue
                if (!showHidden && f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walkDir(f, depth + 1, currentHiddenCtx || f.name.startsWith("."))
                } else {
                    if (onlyHiddenOrNomedia && !currentHiddenCtx && !f.name.startsWith(".")) continue
                    val item = FileItem(
                        id = f.absolutePath,
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = false,
                        size = f.length(),
                        lastModified = f.lastModified(),
                        sourceType = FileSourceType.LOCAL,
                        parentId = f.parentFile?.name ?: "Ana bellek"
                    )
                    val match = when (category) {
                        "IMAGES" -> item.isImageFile()
                        "AUDIO" -> item.isAudioFile()
                        "VIDEOS" -> item.isVideoFile()
                        "DOCUMENTS" -> item.isDocumentFile()
                        "NEW_FILES" -> (System.currentTimeMillis() - item.lastModified) <= 7L * 24 * 3600 * 1000
                        else -> false
                    }
                    if (match) {
                        results.add(
                            if (category == "NEW_FILES") {
                                item.copy(sectionHeader = FormatUtils.formatSectionDate(item.lastModified))
                            } else item
                        )
                    }
                }
            }
        }

        for (r in roots) walkDir(r, 0, false)
        results.sortedByDescending { it.lastModified }
    }

    /**
     * Hızlı (<1 saniye) ve İnteraktif Bellek Analizi Motoru:
     * - MediaStore + dizin ağacı üzerinden tüm alt klasörlerin (Download -> 1DMP -> Compressed -> Alien vb.)
     *   rekürsif toplam boyutlarını tek geçişte hesaplar (folderSizeMap).
     * - Kullanıcı "DAHA FAZLA" veya herhangi bir klasöre tıkladığında 0 ms gecikmeyle alt klasörleri
     *   boyut yüzdesine ve yeşil/mavi çubuklara göre sıralı açar!
     */
    suspend fun analyzeStorage(
        context: Context? = null,
        targetRootPath: String = rootStorageDir.absolutePath,
        targetTitle: String = "Ana bellek"
    ): StorageAnalysisReport = withContext(Dispatchers.IO) {
        val cleanRoot = targetRootPath.trimEnd('/')
        val overview = getStorageOverviewForPath(cleanRoot)

        var imgBytes = 0L
        var audBytes = 0L
        var vidBytes = 0L
        var docBytes = 0L
        var arcBytes = 0L
        var othBytes = 0L

        var imgCount = 0
        var audCount = 0
        var vidCount = 0
        var docCount = 0

        // Tüm klasör derinlikleri için rekürsif toplam boyut haritası
        val allDirBytesMap = HashMap<String, Long>()
        val topFolderCountMap = HashMap<String, Int>()
        val sizeMap = HashMap<Long, MutableList<File>>()
        val largeList = ArrayList<FileItem>()
        val seenFilePaths = HashSet<String>()

        fun accumulateFileIntoDirs(filePath: String, fileLen: Long, lastMod: Long) {
            if (!seenFilePaths.add(filePath)) return
            val fileName = filePath.substringAfterLast('/')
            val parentDir = filePath.substringBeforeLast('/', "")
            val item = FileItem(
                id = filePath,
                name = fileName,
                path = filePath,
                isDirectory = false,
                size = fileLen,
                lastModified = lastMod,
                parentId = filePath.removePrefix("$cleanRoot/").substringBeforeLast('/', "")
            )

            when {
                item.isImageFile() -> { imgBytes += fileLen; imgCount++ }
                item.isAudioFile() -> { audBytes += fileLen; audCount++ }
                item.isVideoFile() -> { vidBytes += fileLen; vidCount++ }
                item.isDocumentFile() -> { docBytes += fileLen; docCount++ }
                item.isArchiveFile() || item.isApk -> arcBytes += fileLen
                else -> othBytes += fileLen
            }

            // Dosyanın bulunduğu klasörden cleanRoot'a kadar tüm üst klasörlere boyutu ekle
            var curDir = parentDir
            while (curDir.length >= cleanRoot.length && curDir.startsWith(cleanRoot)) {
                allDirBytesMap[curDir] = (allDirBytesMap[curDir] ?: 0L) + fileLen
                if (curDir == cleanRoot) break
                val nextSlash = curDir.lastIndexOf('/')
                if (nextSlash <= 0) break
                curDir = curDir.substring(0, nextSlash)
            }

            val rel = filePath.removePrefix("$cleanRoot/")
            val topSeg = rel.substringBefore('/', "")
            if (topSeg.isNotEmpty()) {
                val topFolderPath = "$cleanRoot/$topSeg"
                topFolderCountMap[topFolderPath] = (topFolderCountMap[topFolderPath] ?: 0) + 1
            }

            if (fileLen >= 10L * 1024 * 1024) {
                largeList.add(item)
            }
            if (fileLen >= 512 * 1024L) {
                sizeMap.getOrPut(fileLen) { ArrayList() }.add(File(filePath))
            }
        }

        if (context != null) {
            try {
                val uri = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
                )
                val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
                val args = arrayOf("$cleanRoot/%")
                context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        if (path.isBlank() || path.contains("/.DosyaYoneticisiPlus_RecycleBin")) continue
                        val len = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                        if (len <= 0L) continue
                        val modSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                        val lastMod = if (modSec > 0L) modSec * 1000L else System.currentTimeMillis()
                        accumulateFileIntoDirs(path, len, lastMod)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // MediaStore'un atladığı klasörleri (.aa, Files, 1DMP vb.) de hızlıca tamamla
        fun walkSupplement(dir: File, depth: Int) {
            if (depth > 5) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.name == ".DosyaYoneticisiPlus_RecycleBin") continue
                val p = f.absolutePath
                if (p.contains("/Android/data") || p.contains("/Android/obb")) continue
                if (f.isDirectory) {
                    walkSupplement(f, depth + 1)
                } else {
                    if (!seenFilePaths.contains(p)) {
                        accumulateFileIntoDirs(p, f.length(), f.lastModified())
                    }
                }
            }
        }
        walkSupplement(File(cleanRoot), 0)

        val rootDirFile = File(cleanRoot)
        val topFolders = ArrayList<FileItem>()
        rootDirFile.listFiles()?.forEach { f ->
            if (f.name == ".DosyaYoneticisiPlus_RecycleBin") return@forEach
            if (!AppSettings.showHiddenFiles && f.name.startsWith(".")) return@forEach
            if (f.isDirectory) {
                val sz = allDirBytesMap[f.absolutePath] ?: 0L
                val directChildren = f.list()?.count { AppSettings.showHiddenFiles || !it.startsWith(".") } ?: 0
                topFolders.add(
                    FileItem(
                        id = f.absolutePath,
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = true,
                        size = sz,
                        lastModified = f.lastModified(),
                        childCount = directChildren,
                        sourceType = FileSourceType.LOCAL
                    )
                )
            }
        }
        topFolders.sortByDescending { it.size }

        val duplicateGroups = ArrayList<List<FileItem>>()
        val candidateEntries = sizeMap.entries
            .filter { it.value.size > 1 }
            .sortedByDescending { it.key }
            .take(40)

        for ((_, sameSizeFiles) in candidateEntries) {
            val byHash = sameSizeFiles.take(12).groupBy { quickPartialHash(it) }
            for ((hash, group) in byHash) {
                if (hash.isNotEmpty() && group.size > 1) {
                    duplicateGroups.add(
                        group.map { f ->
                            FileItem(
                                id = f.absolutePath,
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = false,
                                size = f.length(),
                                lastModified = f.lastModified(),
                                parentId = f.parentFile?.name ?: ""
                            )
                        }
                    )
                }
            }
        }

        val installedApps = if (context != null && cleanRoot == rootStorageDir.absolutePath) {
            getInstalledApps(context, includeSystem = false)
        } else {
            emptyList()
        }
        val appsBytes = installedApps.sumOf { it.apkSizeBytes }
        val accounted = imgBytes + audBytes + vidBytes + docBytes + arcBytes + appsBytes
        val computedOthers = (overview.usedBytes - accounted).coerceAtLeast(othBytes)

        val report = StorageAnalysisReport(
            rootPath = cleanRoot,
            rootTitle = targetTitle,
            overview = overview,
            imagesBytes = imgBytes,
            audioBytes = audBytes,
            videosBytes = vidBytes,
            docsBytes = docBytes,
            appsBytes = appsBytes,
            archivesBytes = arcBytes,
            othersBytes = computedOthers,
            topFolders = topFolders,
            duplicateGroups = duplicateGroups.sortedByDescending { it.firstOrNull()?.size ?: 0L },
            largeFiles = largeList.sortedByDescending { it.size }.take(200),
            installedApps = installedApps,
            imagesCount = imgCount,
            audioCount = audCount,
            videosCount = vidCount,
            docsCount = docCount,
            folderSizeMap = allDirBytesMap
        )
        lastAnalysisReport = report
        report
    }

    /**
     * Video 5'teki (00:09 - 00:23) Bellek Analizi Klasör İçi Boyut Gezgini:
     * Seçilen klasörün içindeki tüm alt klasörleri (rekürsif toplam boyutlarıyla) ve dosyaları
     * en büyükten en küçüğe doğru sıralar!
     */
    suspend fun getFolderSizeDrillDownItems(
        dirPath: String,
        report: StorageAnalysisReport,
        forceShowHidden: Boolean = false,
        sortBy: Int = 1,
        sortAsc: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val dir = File(dirPath)
        val children = dir.listFiles() ?: return@withContext emptyList()
        val nonRecycle = children.filter { it.name != ".DosyaYoneticisiPlus_RecycleBin" }
        val hasVisibleChildren = nonRecycle.any { !it.name.startsWith(".") }
        val showHidden = forceShowHidden || AppSettings.showHiddenFiles || !hasVisibleChildren
        val items = ArrayList<FileItem>(nonRecycle.size)

        for (f in nonRecycle) {
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            val totalSize = if (isDir) {
                report.folderSizeMap[f.absolutePath] ?: computeDirectorySizeBounded(f, 0)
            } else {
                f.length()
            }
            val subList = if (isDir) f.list() else null
            val directCount = if (isDir && subList != null) {
                val vis = subList.count { showHidden || !it.startsWith(".") }
                if (vis == 0 && subList.isNotEmpty()) subList.size else vis
            } else 0
            items.add(
                FileItem(
                    id = f.absolutePath,
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = isDir,
                    size = totalSize,
                    lastModified = f.lastModified(),
                    childCount = directCount,
                    sourceType = FileSourceType.LOCAL
                )
            )
        }
        when (sortBy) {
            0 -> if (sortAsc) items.sortedBy { it.name.lowercase(Locale("tr", "TR")) } else items.sortedByDescending { it.name.lowercase(Locale("tr", "TR")) }
            2 -> if (sortAsc) items.sortedBy { it.lastModified } else items.sortedByDescending { it.lastModified }
            else -> if (sortAsc) items.sortedWith(compareBy<FileItem> { it.size }.thenBy { it.name.lowercase(Locale("tr", "TR")) })
                    else items.sortedWith(compareByDescending<FileItem> { it.size }.thenBy { it.name.lowercase(Locale("tr", "TR")) })
        }
    }

    private fun computeDirectorySizeBounded(dir: File, depth: Int): Long {
        if (depth > 4) return 0L
        val children = dir.listFiles() ?: return 0L
        var sum = 0L
        for (c in children) {
            sum += if (c.isDirectory) computeDirectorySizeBounded(c, depth + 1) else c.length()
        }
        return sum
    }

    private fun quickPartialHash(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                val read = fis.read(buf)
                if (read > 0) md.update(buf, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    // ==================== SOL ÇEKMECE YER İMLERİ (FAVORİLER) & GEÇMİŞ ====================

    fun getBookmarks(context: Context): List<DrawerBookmarkEntry> {
        val prefs = context.getSharedPreferences(PREF_DRAWER_DATA, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARKS_JSON, null)
        if (raw.isNullOrBlank()) {
            val defaults = listOf(
                DrawerBookmarkEntry("Kamera", File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").absolutePath, true),
                DrawerBookmarkEntry("Filmler", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath, true),
                DrawerBookmarkEntry("Müzik", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath, true)
            )
            saveBookmarks(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<DrawerBookmarkEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DrawerBookmarkEntry(
                        title = o.optString("title"),
                        path = o.optString("path"),
                        isDirectory = o.optBoolean("isDirectory", true)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveBookmarks(context: Context, list: List<DrawerBookmarkEntry>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().apply {
                put("title", b.title)
                put("path", b.path)
                put("isDirectory", b.isDirectory)
            })
        }
        context.getSharedPreferences(PREF_DRAWER_DATA, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKMARKS_JSON, arr.toString())
            .apply()
    }

    fun addBookmark(context: Context, item: FileItem) {
        val current = getBookmarks(context).toMutableList()
        if (current.none { it.path == item.path }) {
            current.add(DrawerBookmarkEntry(item.name, item.path, item.isDirectory))
            saveBookmarks(context, current)
        }
    }

    fun removeBookmark(context: Context, path: String) {
        val updated = getBookmarks(context).filterNot { it.path == path }
        saveBookmarks(context, updated)
    }

    fun getHistory(context: Context): List<DrawerHistoryEntry> {
        val prefs = context.getSharedPreferences(PREF_DRAWER_DATA, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<DrawerHistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DrawerHistoryEntry(
                        title = o.optString("title"),
                        path = o.optString("path"),
                        section = o.optString("section", "Ana bellek"),
                        isDirectory = o.optBoolean("isDirectory", false),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun recordHistory(context: Context, item: FileItem, section: String = "Ana bellek") {
        val current = getHistory(context).filterNot { it.path == item.path }.toMutableList()
        current.add(
            0,
            DrawerHistoryEntry(
                title = item.name,
                path = item.path,
                section = section,
                isDirectory = item.isDirectory,
                timestamp = System.currentTimeMillis()
            )
        )
        val trimmed = current.take(50)
        val arr = JSONArray()
        for (h in trimmed) {
            arr.put(JSONObject().apply {
                put("title", h.title)
                put("path", h.path)
                put("section", h.section)
                put("isDirectory", h.isDirectory)
                put("timestamp", h.timestamp)
            })
        }
        context.getSharedPreferences(PREF_DRAWER_DATA, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREF_DRAWER_DATA, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY_JSON)
            .apply()
    }

    suspend fun getInstalledApps(
        context: Context,
        includeSystem: Boolean = false,
        includeSystemApps: Boolean = includeSystem
    ): List<InstalledAppItem> = withContext(Dispatchers.IO) {
        val showSys = includeSystem || includeSystemApps
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val list = ArrayList<InstalledAppItem>(packages.size)
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (!showSys && isSys) continue
            val label = pm.getApplicationLabel(appInfo).toString()
            var size = 0L
            val baseApk = File(appInfo.sourceDir ?: "")
            if (baseApk.exists()) size += baseApk.length()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appInfo.splitSourceDirs?.forEach { splitPath ->
                    val sf = File(splitPath)
                    if (sf.exists()) size += sf.length()
                }
            }
            val extRoot = rootStorageDir
            val obbDir = File(extRoot, "Android/obb/${pkg.packageName}")
            if (obbDir.exists()) size += computeDirectorySizeBounded(obbDir, 1)
            val mediaDir = File(extRoot, "Android/media/${pkg.packageName}")
            if (mediaDir.exists()) size += computeDirectorySizeBounded(mediaDir, 1)
            val dataDir = File(extRoot, "Android/data/${pkg.packageName}")
            if (dataDir.exists()) size += computeDirectorySizeBounded(dataDir, 1)
            // İkonu arka planda önbelleğe al (120Hz sıfır takılmalı kaydırma için)
            if (ThumbnailCacheManager.getCachedAppIcon(pkg.packageName) == null) {
                try {
                    ThumbnailCacheManager.putCachedAppIcon(pkg.packageName, pm.getApplicationIcon(appInfo))
                } catch (_: Throwable) {
                }
            }
            list.add(
                InstalledAppItem(
                    packageName = pkg.packageName,
                    appName = label,
                    versionName = pkg.versionName ?: "",
                    apkPath = appInfo.sourceDir ?: "",
                    apkSizeBytes = size,
                    isSystemApp = isSys,
                    lastUpdateTime = pkg.lastUpdateTime
                )
            )
        }
        list.sortedByDescending { it.apkSizeBytes }
    }

    fun createNewFolder(parentDir: File, name: String): Boolean {
        return try {
            val ok = File(parentDir, name).mkdirs()
            if (ok) invalidateCaches()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun createNewFile(parentDir: File, name: String): Boolean {
        return try {
            val ok = File(parentDir, name).createNewFile()
            if (ok) invalidateCaches()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun renameFile(file: File, newName: String, context: Context? = null): Boolean {
        return try {
            val dest = File(file.parentFile ?: rootStorageDir, newName)
            val ok = file.renameTo(dest)
            if (ok) {
                invalidateCaches()
                if (context != null) {
                    try {
                        MediaScannerConnection.scanFile(
                            context.applicationContext,
                            arrayOf(file.absolutePath, dest.absolutePath),
                            null,
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun pasteClipboardTo(destDir: File): Int = withContext(Dispatchers.IO) {
        val items = clipboardFiles
        val move = clipboardIsMove
        var count = 0
        for (item in items) {
            val src = File(item.path)
            if (src.exists() && copyOrMoveFile(src, destDir, move)) {
                count++
            }
        }
        clipboardFiles = emptyList()
        if (count > 0) invalidateCaches()
        count
    }

    suspend fun deleteFiles(
        items: List<FileItem>,
        moveToRecycleBin: Boolean = AppSettings.useRecycleBin,
        context: Context? = null
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val deletedPaths = ArrayList<String>()
        for (item in items) {
            val f = File(item.path)
            if (f.exists() && deleteFileOrRecycle(f, moveToRecycleBin)) {
                count++
                deletedPaths.add(f.absolutePath)
            }
        }
        if (count > 0) invalidateCaches()
        if (context != null && deletedPaths.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    deletedPaths.toTypedArray(),
                    null,
                    null
                )
            } catch (_: Throwable) {
            }
        }
        count
    }

    suspend fun copyOrMoveFile(src: File, destDir: File, move: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            val target = File(destDir, src.name)
            if (move && src.renameTo(target)) {
                invalidateCaches()
                return@withContext true
            }
            if (src.isDirectory) {
                src.copyRecursively(target, overwrite = true)
                if (move) src.deleteRecursively()
            } else {
                src.copyTo(target, overwrite = true)
                if (move) src.delete()
            }
            invalidateCaches()
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun deleteFileOrRecycle(file: File, useRecycleBin: Boolean = AppSettings.useRecycleBin): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (useRecycleBin && !file.absolutePath.startsWith(recycleBinDir.absolutePath)) {
                    val dest = File(recycleBinDir, "${System.currentTimeMillis()}_${file.name}")
                    if (file.renameTo(dest)) {
                        invalidateCaches()
                        return@withContext true
                    }
                }
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) invalidateCaches()
                ok
            } catch (_: Throwable) {
                false
            }
        }

    suspend fun compressToZip(files: List<File>, outputZip: File): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                val buf = ByteArray(32 * 1024)
                for (f in files) {
                    if (f.isFile) {
                        zos.putNextEntry(ZipEntry(f.name))
                        FileInputStream(f).use { fis ->
                            var len: Int
                            while (fis.read(buf).also { len = it } > 0) {
                                zos.write(buf, 0, len)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            invalidateCaches()
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun extractZip(zipFile: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                val buf = ByteArray(32 * 1024)
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zis.read(buf).also { len = it } > 0) {
                                fos.write(buf, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            invalidateCaches()
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun computeChecksum(file: File, algorithm: String = "MD5"): String = withContext(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance(algorithm)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(32 * 1024)
                var len: Int
                while (fis.read(buf).also { len = it } > 0) {
                    md.update(buf, 0, len)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            "-"
        }
    }

    fun shareFile(context: Context, item: FileItem) {
        try {
            val uri: Uri = if (item.sourceType == FileSourceType.LOCAL) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.path))
            } else {
                Uri.parse(item.downloadUrl.ifBlank { item.path })
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Paylaş"))
        } catch (_: Throwable) {
        }
    }

    fun openWithExternalChooser(context: Context, item: FileItem, authToken: String = "") {
        try {
            val mime = when {
                item.mimeType.isNotBlank() && !item.mimeType.startsWith("application/vnd.google-apps") -> item.mimeType
                item.isVideoFile() -> "video/*"
                item.isAudioFile() -> "audio/*"
                item.isImageFile() -> "image/*"
                item.extension == "pdf" -> "application/pdf"
                item.extension == "apk" -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
            val uri: Uri = if (item.sourceType == FileSourceType.LOCAL) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.path))
            } else {
                val urlWithToken = if (authToken.isNotBlank() && item.downloadUrl.contains("googleapis.com")) {
                    "${item.downloadUrl}&access_token=$authToken"
                } else {
                    item.downloadUrl.ifBlank { item.path }
                }
                Uri.parse(urlWithToken)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Şununla aç"))
        } catch (_: Throwable) {
        }
    }
}
