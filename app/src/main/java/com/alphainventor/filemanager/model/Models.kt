package com.alphainventor.filemanager.model

import java.io.Serializable

enum class HomeCategoryType {
    MAIN_STORAGE,
    DOWNLOADS,
    STORAGE_ANALYSIS,
    IMAGES,
    AUDIO,
    VIDEOS,
    DOCUMENTS,
    APPS,
    NEW_FILES,
    CLOUD,
    REMOTE,
    NETWORK_ACCESS
}

data class HomeCategoryItem(
    val type: HomeCategoryType,
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val progressPercent: Int = -1
)

enum class FileSourceType {
    LOCAL,
    CLOUD,
    REMOTE
}

data class FileItem(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val childCount: Int = 0,
    val mimeType: String = "",
    val sourceType: FileSourceType = FileSourceType.LOCAL,
    // Bulut ve Toplu Arama için Hesap Bilgileri
    val accountId: String = "",
    val accountEmailOrLabel: String = "",
    val providerName: String = "",
    val downloadUrl: String = "",
    val parentId: String = "",
    val sectionHeader: String = ""
) : Serializable {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    fun isVideoFile(): Boolean {
        if (mimeType.startsWith("video/")) return true
        return extension in VIDEO_EXTENSIONS
    }

    fun isAudioFile(): Boolean {
        if (mimeType.startsWith("audio/")) return true
        return extension in AUDIO_EXTENSIONS
    }

    fun isImageFile(): Boolean {
        if (mimeType.startsWith("image/")) return true
        return extension in IMAGE_EXTENSIONS
    }

    fun isDocumentFile(): Boolean {
        return extension in DOC_EXTENSIONS ||
            mimeType.contains("pdf") ||
            mimeType.contains("document") ||
            mimeType.contains("spreadsheet") ||
            mimeType.contains("presentation") ||
            mimeType.startsWith("text/")
    }

    fun isArchiveFile(): Boolean {
        return extension in ARCHIVE_EXTENSIONS
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "ts", "m2ts",
            "m4v", "3gp", "3g2", "ogv", "mpg", "mpeg", "vob", "divx", "asf", "rmvb"
        )
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "wma", "eac3", "ac3", "dts", "amr", "mid"
        )
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg", "dng", "raw"
        )
        val DOC_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf",
            "csv", "json", "xml", "html", "epub", "odt", "ods", "odp", "log", "ini"
        )
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "torrent", "apk", "apks", "xapk"
        )
    }
}

enum class CloudProviderType(val displayName: String, val prefFileName: String) {
    DROPBOX("Dropbox", "DropboxPrefs"),
    GOOGLE_DRIVE("Google Drive", "GoogleDrivePrefs"),
    ONEDRIVE("OneDrive", "OneDrivePrefs"),
    BOX("Box", "BoxCloudPrefs"),
    NEXTCLOUD("Nextcloud", "WebDAVPrefs"),
    YANDEX("Yandex", "YandexDiskPrefs")
}

data class CloudAccount(
    val id: String,
    val provider: CloudProviderType,
    val emailOrName: String,
    val accessToken: String,
    val refreshToken: String = "",
    val serverUrl: String = "",     // Nextcloud / WebDAV için
    val username: String = "",      // Nextcloud / Yandex WebDAV için
    val freeQuotaBytes: Long = -1L,
    val totalQuotaBytes: Long = -1L,
    val addedAt: Long = System.currentTimeMillis()
) : Serializable

enum class RemoteProtocolType(val displayName: String, val defaultPort: Int, val badge: String) {
    LAN("Yerel Ağ", 445, "SMB"),
    SMB("SMB", 445, "SMB"),
    FTP("FTP", 21, "FTP"),
    SFTP("SFTP", 22, "SFTP"),
    WEBDAV("WebDAV", 443, "DAV")
}

data class RemoteAccount(
    val id: String,
    val protocol: RemoteProtocolType,
    val host: String,
    val port: Int,
    val displayName: String,
    val username: String,
    val password: String,
    val initialPath: String = "/",
    val anonymous: Boolean = false,
    val ftps: Boolean = false
) : Serializable
