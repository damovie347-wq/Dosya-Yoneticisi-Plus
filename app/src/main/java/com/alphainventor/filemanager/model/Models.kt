package com.alphainventor.filemanager.model

import java.io.Serializable

enum class HomeCategoryType {
    MAIN_STORAGE,
    SD_CARD,
    USB_STORAGE,
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
    val badgeCount: Int = 0,
    val usedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val showStorageBar: Boolean = false,
    val showPieChart: Boolean = false,
    val piePercent: Int = 0
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
    val childCount: Int = -1,
    val mimeType: String = "",
    val sourceType: FileSourceType = FileSourceType.LOCAL,
    val accountId: String = "",
    val accountEmailOrLabel: String = "",
    val providerName: String = "",
    val downloadUrl: String = "",
    val thumbnailUrl: String = "",
    val parentId: String = "",
    val sectionHeader: String = ""
) : Serializable {

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isVideo: Boolean
        get() = extension in VIDEO_EXTENSIONS || mimeType.startsWith("video/")

    val isAudio: Boolean
        get() = extension in AUDIO_EXTENSIONS || mimeType.startsWith("audio/")

    val isImage: Boolean
        get() = extension in IMAGE_EXTENSIONS || mimeType.startsWith("image/")

    val isDocument: Boolean
        get() = extension in DOC_EXTENSIONS || mimeType.startsWith("text/") || mimeType.contains("pdf") || mimeType.contains("document") || mimeType.contains("spreadsheet") || mimeType.contains("presentation")

    val isArchive: Boolean
        get() = extension in ARCHIVE_EXTENSIONS

    val isApk: Boolean
        get() = extension == "apk" || extension == "apks" || extension == "xapk" || mimeType == "application/vnd.android.package-archive"

    fun isVideoFile(): Boolean = isVideo
    fun isAudioFile(): Boolean = isAudio
    fun isImageFile(): Boolean = isImage
    fun isDocumentFile(): Boolean = isDocument
    fun isArchiveFile(): Boolean = isArchive

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
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "torrent"
        )
    }
}

enum class CloudProviderType(val displayName: String, val prefName: String) {
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
    val displayName: String = emailOrName,
    val accessToken: String = "",
    val refreshToken: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val usedQuotaBytes: Long = 0L,
    val totalQuotaBytes: Long = 15L * 1024 * 1024 * 1024,
    val addedTimestamp: Long = System.currentTimeMillis()
) : Serializable {
    val freeQuotaBytes: Long
        get() = (totalQuotaBytes - usedQuotaBytes).coerceAtLeast(0L)
}

enum class RemoteProtocolType(val displayName: String, val defaultPort: Int, val shortTag: String) {
    LAN("Yerel Ağ", 445, "SMB"),
    SMB("SMB", 445, "SMB"),
    FTP("FTP", 21, "FTP"),
    FTPS("FTPS", 990, "FTPS"),
    SFTP("SFTP", 22, "SFTP"),
    WEBDAV("WebDAV", 443, "DAV")
}

data class RemoteAccount(
    val id: String,
    val protocol: RemoteProtocolType,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "anonymous",
    val password: String = "",
    val initialPath: String = "/",
    val displayName: String = host,
    val anonymous: Boolean = false,
    val ftps: Boolean = (protocol == RemoteProtocolType.FTPS)
) : Serializable {
    val isAnonymous: Boolean
        get() = anonymous
}
