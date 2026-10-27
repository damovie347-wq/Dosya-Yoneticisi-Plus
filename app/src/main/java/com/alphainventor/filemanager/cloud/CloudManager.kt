package com.alphainventor.filemanager.cloud

import android.content.Context
import android.util.Base64
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.CloudProviderType
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class CloudSearchFilter {
    ALL, VIDEO, DOCUMENT, ARCHIVE
}

data class UnifiedSearchProgress(
    val completedAccounts: Int,
    val totalAccounts: Int,
    val currentAccountEmail: String,
    val partialResults: List<FileItem>
)

object CloudManager {
    private const val PREF_ACCOUNTS = "GoogleDrivePrefs"
    private const val KEY_ALL_ACCOUNTS = "all_cloud_accounts_v2"
    private const val PREF_CACHE = "CloudMetadataIndexCache"
    private const val KEY_CACHED_ITEMS = "cached_cloud_files_v1"

    private val gson = Gson()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getAccounts(context: Context): List<CloudAccount> {
        val prefs = context.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALL_ACCOUNTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CloudAccount>>() {}.type
            gson.fromJson<List<CloudAccount>>(raw, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveAccounts(context: Context, accounts: List<CloudAccount>) {
        context.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALL_ACCOUNTS, gson.toJson(accounts))
            .apply()
    }

    fun addOrUpdateAccount(context: Context, account: CloudAccount) {
        val current = getAccounts(context).toMutableList()
        val idx = current.indexOfFirst {
            it.id == account.id || (it.provider == account.provider && it.emailOrName.equals(account.emailOrName, ignoreCase = true))
        }
        if (idx >= 0) {
            current[idx] = account
        } else {
            current.add(account)
        }
        saveAccounts(context, current)
    }

    fun removeAccount(context: Context, accountId: String) {
        val filtered = getAccounts(context).filterNot { it.id == accountId }
        saveAccounts(context, filtered)
    }

    fun exportAccountsJson(context: Context): String {
        return gson.toJson(getAccounts(context))
    }

    fun importAccountsJson(context: Context, json: String): Int {
        val type = object : TypeToken<List<CloudAccount>>() {}.type
        val imported: List<CloudAccount> = gson.fromJson(json, type) ?: return 0
        val existing = getAccounts(context).toMutableList()
        var added = 0
        for (acc in imported) {
            if (existing.none { it.provider == acc.provider && it.emailOrName.equals(acc.emailOrName, ignoreCase = true) }) {
                existing.add(acc)
                added++
            }
        }
        saveAccounts(context, existing)
        return added
    }

    // Yerel önbellek indeksi (10+ Google Drive hesabında anlık arama hızlandırması için)
    fun getCachedFiles(context: Context): List<FileItem> {
        val raw = context.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE).getString(KEY_CACHED_ITEMS, null)
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FileItem>>() {}.type
            gson.fromJson<List<FileItem>>(raw, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun appendToCache(context: Context, accountId: String, newItems: List<FileItem>) {
        if (newItems.isEmpty()) return
        val current = getCachedFiles(context)
            .filterNot { it.accountId == accountId && newItems.any { n -> n.id == it.id } }
            .toMutableList()
        current.addAll(newItems)
        // Belleği şişirmemek için maksimum 5000 öğe tut
        val trimmed = if (current.size > 5000) current.takeLast(5000) else current
        context.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_ITEMS, gson.toJson(trimmed))
            .apply()
    }

    /**
     * KULLANICININ ÖZELLİKLE İSTEDİĞİ FONKSİYON:
     * 10, 20 veya daha fazla Google Drive ve diğer bulut hesaplarının tamamında
     * aynı anda (paralel Coroutines ile) dosya adını arar ve hangi hesapta olduğunu çıkarır.
     */
    suspend fun searchAllCloudsParallel(
        context: Context,
        query: String,
        filter: CloudSearchFilter = CloudSearchFilter.ALL,
        onProgress: suspend (UnifiedSearchProgress) -> Unit
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val accounts = getAccounts(context)
        if (accounts.isEmpty()) return@withContext emptyList()

        val aggregated = java.util.Collections.synchronizedList(mutableListOf<FileItem>())
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // 1. Önce yerel önbellekte eşleşenleri anında ekle (0 ms gecikme)
        val cachedMatches = getCachedFiles(context).filter { item ->
            (cleanQuery.isEmpty() || item.name.contains(cleanQuery, ignoreCase = true)) &&
                matchesFilter(item, filter)
        }
        aggregated.addAll(cachedMatches)

        // 2. Tüm Google Drive ve diğer bulut hesaplarını paralel olarak (aynı anda max 8 bağlantı) sorgula
        @OptIn(ExperimentalCoroutinesApi::class)
        val parallelDispatcher = Dispatchers.IO.limitedParallelism(8)

        supervisorScope {
            val jobs = accounts.map { account ->
                async(parallelDispatcher) {
                    val accountMatches = try {
                        searchSingleAccount(account, cleanQuery)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (accountMatches.isNotEmpty()) {
                        appendToCache(context, account.id, accountMatches)
                        synchronized(aggregated) {
                            for (item in accountMatches) {
                                if (matchesFilter(item, filter) &&
                                    aggregated.none { it.accountId == item.accountId && it.id == item.id }
                                ) {
                                    aggregated.add(item)
                                }
                            }
                        }
                    }
                    val done = completedCount.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        onProgress(
                            UnifiedSearchProgress(
                                completedAccounts = done,
                                totalAccounts = accounts.size,
                                currentAccountEmail = account.emailOrName,
                                partialResults = aggregated.toList().sortedByDescending { it.lastModified }
                            )
                        )
                    }
                }
            }
            jobs.awaitAll()
        }

        aggregated.toList().sortedWith(
            compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.lastModified }
        )
    }

    private fun matchesFilter(item: FileItem, filter: CloudSearchFilter): Boolean {
        return when (filter) {
            CloudSearchFilter.ALL -> true
            CloudSearchFilter.VIDEO -> item.isVideoFile()
            CloudSearchFilter.DOCUMENT -> item.isDocumentFile()
            CloudSearchFilter.ARCHIVE -> item.isArchiveFile()
        }
    }

    /**
     * Tek bir bulut hesabının içindeki klasörü listeler (00:37'deki Google Drive klasör gezintisi).
     */
    suspend fun listCloudFolder(
        context: Context,
        account: CloudAccount,
        folderIdOrPath: String = "root"
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val items = when (account.provider) {
            CloudProviderType.GOOGLE_DRIVE -> listGoogleDriveFolder(account, folderIdOrPath.ifBlank { "root" })
            CloudProviderType.DROPBOX -> listDropboxFolder(account, if (folderIdOrPath == "root") "" else folderIdOrPath)
            CloudProviderType.ONEDRIVE -> listOneDriveFolder(account, folderIdOrPath)
            CloudProviderType.BOX -> listBoxFolder(account, if (folderIdOrPath == "root") "0" else folderIdOrPath)
            CloudProviderType.NEXTCLOUD -> listWebDavFolder(account, if (folderIdOrPath == "root") "/" else folderIdOrPath)
            CloudProviderType.YANDEX -> listYandexFolder(account, if (folderIdOrPath == "root") "disk:/" else folderIdOrPath)
        }
        appendToCache(context, account.id, items)
        items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun searchSingleAccount(account: CloudAccount, query: String): List<FileItem> {
        return when (account.provider) {
            CloudProviderType.GOOGLE_DRIVE -> searchGoogleDrive(account, query)
            CloudProviderType.DROPBOX -> searchDropbox(account, query)
            CloudProviderType.ONEDRIVE -> searchOneDrive(account, query)
            CloudProviderType.BOX -> searchBox(account, query)
            CloudProviderType.NEXTCLOUD -> listWebDavFolder(account, "/").filter {
                query.isEmpty() || it.name.contains(query, ignoreCase = true)
            }
            CloudProviderType.YANDEX -> searchYandex(account, query)
        }
    }

    // ==================== 1. GOOGLE DRIVE API v3 ====================
    // Orijinal APK'daki (classes.dex [31384]) birebir sorgu yapısı:
    // nextPageToken, files(kind,id,name,mimeType,parents,capabilities/canDownload,capabilities/canEdit,size,modifiedTime,createdTime,webContentLink,thumbnailLink,webViewLink,shortcutDetails,trashed)
    private fun listGoogleDriveFolder(account: CloudAccount, folderId: String): List<FileItem> {
        if (account.accessToken.startsWith("DEMO_")) {
            return generateDemoDriveFiles(account, folderId)
        }
        val escapedParent = folderId.replace("'", "\\'")
        val q = "'$escapedParent' in parents and trashed = false"
        return executeGoogleDriveQuery(account, q)
    }

    private fun searchGoogleDrive(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.startsWith("DEMO_")) {
            return generateDemoDriveFiles(account, "root", searchMode = true).filter {
                query.isEmpty() || it.name.contains(query, ignoreCase = true)
            }
        }
        val escaped = query.replace("'", "\\'")
        val q = if (escaped.isEmpty()) {
            "trashed = false"
        } else {
            "name contains '$escaped' and trashed = false"
        }
        return executeGoogleDriveQuery(account, q)
    }

    private fun executeGoogleDriveQuery(account: CloudAccount, q: String): List<FileItem> {
        val encodedQ = URLEncoder.encode(q, "UTF-8")
        val fields = URLEncoder.encode(
            "nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,webContentLink,trashed)",
            "UTF-8"
        )
        val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQ&fields=$fields&pageSize=200&supportsAllDrives=true&includeItemsFromAllDrives=true"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val files = json.optJSONArray("files") ?: return emptyList()
            val list = mutableListOf<FileItem>()
            for (i in 0 until files.length()) {
                val obj = files.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val mime = obj.optString("mimeType", "")
                val isDir = mime == "application/vnd.google-apps.folder"
                val size = obj.optLong("size", 0L)
                val modTime = parseIsoTime(obj.optString("modifiedTime"))
                val parentsArr = obj.optJSONArray("parents")
                val parentId = if (parentsArr != null && parentsArr.length() > 0) parentsArr.optString(0) else "root"
                val streamUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                list.add(
                    FileItem(
                        id = id,
                        name = name,
                        path = id,
                        isDirectory = isDir,
                        size = size,
                        lastModified = modTime,
                        mimeType = mime,
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = account.provider.displayName,
                        downloadUrl = streamUrl,
                        parentId = parentId
                    )
                )
            }
            return list
        }
    }

    // ==================== 2. DROPBOX API v2 ====================
    private fun listDropboxFolder(account: CloudAccount, path: String): List<FileItem> {
        val payload = JSONObject().apply {
            put("path", path)
            put("recursive", false)
            put("include_media_info", false)
        }.toString()
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/list_folder")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val entries = json.optJSONArray("entries") ?: return emptyList()
            return parseDropboxEntries(account, entries)
        }
    }

    private fun searchDropbox(account: CloudAccount, query: String): List<FileItem> {
        if (query.isBlank()) return listDropboxFolder(account, "")
        val payload = JSONObject().apply {
            put("query", query)
        }.toString()
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/search_v2")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val matches = json.optJSONArray("matches") ?: return emptyList()
            val metadataArr = JSONArray()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i).optJSONObject("metadata")?.optJSONObject("metadata")
                if (m != null) metadataArr.put(m)
            }
            return parseDropboxEntries(account, metadataArr)
        }
    }

    private fun parseDropboxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = mutableListOf<FileItem>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val tag = e.optString(".tag")
            val pathDisplay = e.optString("path_display", "")
            val name = e.optString("name", "")
            list.add(
                FileItem(
                    id = e.optString("id", pathDisplay),
                    name = name,
                    path = pathDisplay,
                    isDirectory = tag == "folder",
                    size = e.optLong("size", 0L),
                    lastModified = parseIsoTime(e.optString("client_modified")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    downloadUrl = "https://content.dropboxapi.com/2/files/download"
                )
            )
        }
        return list
    }

    // ==================== 3. MICROSOFT ONEDRIVE GRAPH v1.0 ====================
    private fun listOneDriveFolder(account: CloudAccount, folderId: String): List<FileItem> {
        val endpoint = if (folderId == "root" || folderId.isBlank()) {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$folderId/children"
        }
        return executeOneDriveGet(account, endpoint)
    }

    private fun searchOneDrive(account: CloudAccount, query: String): List<FileItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoint = "https://graph.microsoft.com/v1.0/me/drive/root/search(q='$encoded')"
        return executeOneDriveGet(account, endpoint)
    }

    private fun executeOneDriveGet(account: CloudAccount, url: String): List<FileItem> {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val values = json.optJSONArray("value") ?: return emptyList()
            val result = mutableListOf<FileItem>()
            for (i in 0 until values.length()) {
                val obj = values.getJSONObject(i)
                val isDir = obj.has("folder")
                val dl = obj.optString("@microsoft.graph.downloadUrl", "")
                result.add(
                    FileItem(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        path = obj.optString("id"),
                        isDirectory = isDir,
                        size = obj.optLong("size", 0L),
                        lastModified = parseIsoTime(obj.optString("lastModifiedDateTime")),
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = account.provider.displayName,
                        downloadUrl = dl
                    )
                )
            }
            return result
        }
    }

    // ==================== 4. BOX API v2.0 ====================
    private fun listBoxFolder(account: CloudAccount, folderId: String): List<FileItem> {
        val req = Request.Builder()
            .url("https://api.box.com/2.0/folders/$folderId/items?fields=id,name,type,size,modified_at")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val entries = json.optJSONArray("entries") ?: return emptyList()
            return parseBoxEntries(account, entries)
        }
    }

    private fun searchBox(account: CloudAccount, query: String): List<FileItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://api.box.com/2.0/search?query=$encoded&fields=id,name,type,size,modified_at")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val entries = json.optJSONArray("entries") ?: return emptyList()
            return parseBoxEntries(account, entries)
        }
    }

    private fun parseBoxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = mutableListOf<FileItem>()
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val id = obj.optString("id")
            val isDir = obj.optString("type") == "folder"
            list.add(
                FileItem(
                    id = id,
                    name = obj.optString("name"),
                    path = id,
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTime(obj.optString("modified_at")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    downloadUrl = "https://api.box.com/2.0/files/$id/content"
                )
            )
        }
        return list
    }

    // ==================== 5. NEXTCLOUD / WEBDAV ====================
    private fun listWebDavFolder(account: CloudAccount, relPath: String): List<FileItem> {
        val base = account.serverUrl.trimEnd('/')
        val fullUrl = "$base/${relPath.trimStart('/')}"
        val propfindXml = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
        val req = Request.Builder()
            .url(fullUrl)
            .method("PROPFIND", propfindXml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(account.username, account.accessToken))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string() ?: return emptyList()
            val hrefRegex = Regex("<[^:>]*:?href>([^<]+)</[^:>]*:?href>", RegexOption.IGNORE_CASE)
            val list = mutableListOf<FileItem>()
            for (m in hrefRegex.findAll(xml).drop(1)) {
                val href = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").trimEnd('/')
                val name = href.substringAfterLast('/')
                if (name.isNotBlank()) {
                    val isDir = m.groupValues[1].endsWith("/")
                    list.add(
                        FileItem(
                            id = href,
                            name = name,
                            path = href,
                            isDirectory = isDir,
                            size = 0L,
                            lastModified = System.currentTimeMillis(),
                            sourceType = FileSourceType.CLOUD,
                            accountId = account.id,
                            accountEmailOrLabel = account.emailOrName,
                            providerName = account.provider.displayName,
                            downloadUrl = "$base/${href.trimStart('/')}"
                        )
                    )
                }
            }
            return list
        }
    }

    // ==================== 6. YANDEX DISK REST API ====================
    private fun listYandexFolder(account: CloudAccount, path: String): List<FileItem> {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources?path=$encoded&limit=200")
            .header("Authorization", "OAuth ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val items = json.optJSONObject("_embedded")?.optJSONArray("items") ?: return emptyList()
            return parseYandexItems(account, items)
        }
    }

    private fun searchYandex(account: CloudAccount, query: String): List<FileItem> {
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources/files?limit=200")
            .header("Authorization", "OAuth ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val items = json.optJSONArray("items") ?: return emptyList()
            return parseYandexItems(account, items).filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            }
        }
    }

    private fun parseYandexItems(account: CloudAccount, items: JSONArray): List<FileItem> {
        val list = mutableListOf<FileItem>()
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            val isDir = obj.optString("type") == "dir"
            val p = obj.optString("path")
            list.add(
                FileItem(
                    id = p,
                    name = obj.optString("name"),
                    path = p,
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTime(obj.optString("modified")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    downloadUrl = obj.optString("file", "")
                )
            )
        }
        return list
    }

    // Videodaki (00:37 - 00:54) klasör ve medya yapısını test/önizleme modunda birebir gösteren yardımcı
    private fun generateDemoDriveFiles(account: CloudAccount, folderId: String, searchMode: Boolean = false): List<FileItem> {
        val now = System.currentTimeMillis()
        val rootItems = listOf(
            FileItem("fld_ace", "Ace_Videolar", "fld_ace", true, 0L, now - 86400000L * 40, 2, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("fld_colab", "Colab Notebooks", "fld_colab", true, 0L, now - 86400000L * 90, 12, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("fld_mercy", "Mercy", "fld_mercy", true, 0L, now - 86400000L * 35, 3, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("fld_svt", "svt", "fld_svt", true, 0L, now - 86400000L * 18, 9, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("fld_torrent", "Torrent", "fld_torrent", true, 0L, now - 86400000L * 120, 1, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("fld_yt", "Youtube_Videolari", "fld_yt", true, 0L, now - 86400000L * 60, 2, accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("file_doc1", "Adsız doküman", "file_doc1", false, 24_500L, now - 86400000L * 10, mimeType = "application/vnd.google-apps.document", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("file_sheet1", "Adsız e-tablo", "file_sheet1", false, 18_200L, now - 86400000L * 10, mimeType = "application/vnd.google-apps.spreadsheet", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("file_intime", "In.Time.2011.720p.AMZN.WEB-DL.DDP5.1.AV1-Av1Core.mkv", "file_intime", false, 577L * 1000 * 1000, now - 86400000L * 20, mimeType = "video/x-matroska", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root"),
            FileItem("file_bone", "The.Bone.Collector.1999.720p.AMZN.WEB-DL.DDP5.1.AV1-Av1Core.mkv", "file_bone", false, 479L * 1000 * 1000, now - 86400000L * 20, mimeType = "video/x-matroska", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "root")
        )
        val ytItems = listOf(
            FileItem("file_yt1", "CEZALI ŞİŞE ÇEVİRMECE!!! [lhDuJY7EbFc].webm", "file_yt1", false, 20_140_000L, now - 86400000L * 60, mimeType = "video/webm", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "fld_yt"),
            FileItem("file_yt2", "Su balonu cezalı taş,kağıt,makas [q-F3tpbb6q8].webm", "file_yt2", false, 15_550_000L, now - 86400000L * 60, mimeType = "video/webm", accountId = account.id, accountEmailOrLabel = account.emailOrName, providerName = "Google Drive", sourceType = FileSourceType.CLOUD, parentId = "fld_yt")
        )
        if (searchMode) return rootItems + ytItems
        return when (folderId) {
            "fld_yt" -> ytItems
            else -> rootItems
        }
    }

    private fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val clean = iso.substringBefore('.').replace("Z", "")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(clean)?.time ?: System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
}
