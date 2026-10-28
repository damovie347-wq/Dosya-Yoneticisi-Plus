package com.alphainventor.filemanager.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StorageOverview(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val usedPercent: Int
)

data class CategoryStat(
    val totalBytes: Long,
    val count: Int
)

data class StorageAnalysisReport(
    val overview: StorageOverview,
    val imagesBytes: Long,
    val audioBytes: Long,
    val videosBytes: Long,
    val docsBytes: Long,
    val archivesBytes: Long,
    val othersBytes: Long,
    val duplicateGroups: List<List<FileItem>>,
    val largeFiles: List<FileItem>,
    val imagesCount: Int = 0,
    val audioCount: Int = 0,
    val videosCount: Int = 0,
    val docsCount: Int = 0
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

object LocalFileRepository {

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

    fun getStorageOverview(): StorageOverview {
        return try {
            val stat = StatFs(rootStorageDir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = (total - free).coerceAtLeast(0L)
            val pct = if (total > 0) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
            StorageOverview(total, used, free, pct)
        } catch (_: Throwable) {
            StorageOverview(128_000_000_000L, 86_800_000_000L, 41_200_000_000L, 67)
        }
    }

    fun getMainStorageOverview(): StorageOverview = getStorageOverview()

    suspend fun listDirectory(
        dir: File,
        showHidden: Boolean = AppSettings.showHiddenFiles,
        sortBy: Int = AppSettings.sortBy
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val rawFiles = dir.listFiles() ?: return@withContext emptyList()
        val items = mutableListOf<FileItem>()
        for (f in rawFiles) {
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
        val asc = AppSettings.sortAscending
        items.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }.let { comp ->
                when (sortBy) {
                    1 -> if (asc) comp.thenBy { it.size } else comp.thenByDescending { it.size }
                    2 -> if (asc) comp.thenBy { it.lastModified } else comp.thenByDescending { it.lastModified }
                    3 -> comp.thenBy { it.extension }.thenBy { it.name.lowercase() }
                    else -> if (asc) comp.thenBy { it.name.lowercase() } else comp.thenByDescending { it.name.lowercase() }
                }
            }
        )
    }

    suspend fun getCategoryFiles(category: String): List<FileItem> = scanCategoryFiles(category)

    suspend fun scanCategoryFiles(category: String): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        val root = rootStorageDir
        val showHidden = AppSettings.showHiddenFiles

        fun walkDir(dir: File, depth: Int) {
            if (depth > 8) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (!showHidden && f.name.startsWith(".")) continue
                if (f.absolutePath.contains("/Android/data") || f.absolutePath.contains("/Android/obb")) continue
                if (f.isDirectory) {
                    walkDir(f, depth + 1)
                } else {
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

        walkDir(root, 0)
        results.sortedByDescending { it.lastModified }
    }

    suspend fun analyzeStorage(context: Context? = null): StorageAnalysisReport = withContext(Dispatchers.IO) {
        val overview = getStorageOverview()
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

        val sizeMap = HashMap<Long, MutableList<File>>()
        val largeList = mutableListOf<FileItem>()

        fun walk(dir: File, depth: Int) {
            if (depth > 9) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (!f.absolutePath.contains("/Android/data")) walk(f, depth + 1)
                } else {
                    val len = f.length()
                    val item = FileItem(f.absolutePath, f.name, f.absolutePath, false, len, f.lastModified())
                    when {
                        item.isImageFile() -> { imgBytes += len; imgCount++ }
                        item.isAudioFile() -> { audBytes += len; audCount++ }
                        item.isVideoFile() -> { vidBytes += len; vidCount++ }
                        item.isDocumentFile() -> { docBytes += len; docCount++ }
                        item.isArchiveFile() -> arcBytes += len
                        else -> othBytes += len
                    }
                    if (len >= 10L * 1000 * 1000) {
                        largeList.add(item)
                    }
                    if (len >= 64 * 1024L) {
                        sizeMap.getOrPut(len) { mutableListOf() }.add(f)
                    }
                }
            }
        }

        walk(rootStorageDir, 0)

        val duplicateGroups = mutableListOf<List<FileItem>>()
        for ((_, sameSizeFiles) in sizeMap) {
            if (sameSizeFiles.size > 1) {
                val byHash = sameSizeFiles.groupBy { quickPartialHash(it) }
                for ((hash, group) in byHash) {
                    if (hash.isNotEmpty() && group.size > 1) {
                        duplicateGroups.add(
                            group.map { f ->
                                FileItem(f.absolutePath, f.name, f.absolutePath, false, f.length(), f.lastModified())
                            }
                        )
                    }
                }
            }
        }

        StorageAnalysisReport(
            overview = overview,
            imagesBytes = imgBytes,
            audioBytes = audBytes,
            videosBytes = vidBytes,
            docsBytes = docBytes,
            archivesBytes = arcBytes,
            othersBytes = othBytes,
            duplicateGroups = duplicateGroups.sortedByDescending { it.firstOrNull()?.size ?: 0L },
            largeFiles = largeList.sortedByDescending { it.size }.take(100),
            imagesCount = imgCount,
            audioCount = audCount,
            videosCount = vidCount,
            docsCount = docCount
        )
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

    suspend fun getInstalledApps(
        context: Context,
        includeSystem: Boolean = false,
        includeSystemApps: Boolean = includeSystem
    ): List<InstalledAppItem> = withContext(Dispatchers.IO) {
        val showSys = includeSystem || includeSystemApps
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val list = mutableListOf<InstalledAppItem>()
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (!showSys && isSys) continue
            val label = pm.getApplicationLabel(appInfo).toString()
            val apkFile = File(appInfo.sourceDir ?: "")
            val size = if (apkFile.exists()) apkFile.length() else 0L
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
            File(parentDir, name).mkdirs()
        } catch (_: Throwable) {
            false
        }
    }

    fun createNewFile(parentDir: File, name: String): Boolean {
        return try {
            File(parentDir, name).createNewFile()
        } catch (_: Throwable) {
            false
        }
    }

    fun renameFile(file: File, newName: String): Boolean {
        return try {
            val dest = File(file.parentFile ?: rootStorageDir, newName)
            file.renameTo(dest)
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
        count
    }

    suspend fun deleteFiles(items: List<FileItem>, moveToRecycleBin: Boolean = AppSettings.useRecycleBin): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            for (item in items) {
                val f = File(item.path)
                if (f.exists() && deleteFileOrRecycle(f, moveToRecycleBin)) {
                    count++
                }
            }
            count
        }

    suspend fun copyOrMoveFile(src: File, destDir: File, move: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            val target = File(destDir, src.name)
            if (move && src.renameTo(target)) return@withContext true
            if (src.isDirectory) {
                src.copyRecursively(target, overwrite = true)
                if (move) src.deleteRecursively()
            } else {
                src.copyTo(target, overwrite = true)
                if (move) src.delete()
            }
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
                    if (file.renameTo(dest)) return@withContext true
                }
                if (file.isDirectory) file.deleteRecursively() else file.delete()
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
