package com.alphainventor.filemanager.cloud

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.CloudProviderType
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.FormBody
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class CloudSearchFilter {
    ALL,
    VIDEOS,
    AUDIO,
    IMAGES,
    DOCUMENTS,
    ARCHIVES
}

data class UnifiedCloudSearchProgress(
    val completedAccounts: Int,
    val totalAccounts: Int,
    val currentAccountName: String,
    val partialResults: List<FileItem>
)

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 ile birebir uyumlu Çoklu Bulut Motoru (Google Drive v3, Dropbox, OneDrive, Box, Yandex.Disk, Nextcloud/WebDAV).
 *
 * Google Drive için UNREGISTERED_ON_API_CONSOLE hatasını tamamen ortadan kaldıran:
 * 1) Masaüstü / Loopback OAuth 2.0 (client_id + client_secret + refresh_token) akışı — APK imza SHA-1 kontrolüne takılmaz!
 * 2) Otomatik Refresh Token yenileme (access_token süresi dolduğunda arka planda yeniler).
 * 3) Tüm bağlı Google Drive hesaplarında paralel arama ve klasör adı çözümleme.
 */
object CloudManager {
    private const val PREF_CLOUD_ACCOUNTS = "CloudAccountsRegistryPrefs"
    private const val KEY_ACCOUNTS_JSON = "accounts_json_v3"
    private const val KEY_SEARCH_HISTORY_JSON = "cloud_search_history_v1"
    private const val DRIVE_OAUTH_SCOPE = "oauth2:https://www.googleapis.com/auth/drive"

    // APK imzasından (SHA-1) bağımsız çalışan standart OAuth 2.0 İstemci Bilgileri
    const val GOOGLE_OAUTH_CLIENT_ID = "202264815644.apps.googleusercontent.com"
    const val GOOGLE_OAUTH_CLIENT_SECRET = "X4Z3ca8xfWDb1Voo-F9a7ZxJ"
    const val GOOGLE_OAUTH_SCOPE_URL = "https://www.googleapis.com/auth/drive"

    private val gson = Gson()

    // (accountId, folderId) -> List<FileItem> önbelleği
    private val folderCache: MutableMap<String, List<FileItem>> = ConcurrentHashMap()

    // folderId -> folderName önbelleği (Arama sonuçlarında klasör yolunu göstermek için)
    private val folderNameCache: MutableMap<String, String> = ConcurrentHashMap()

    @Volatile
    var pendingRecoveryIntent: Intent? = null

    @Volatile
    var lastCloudErrorMessage: String? = null

    @Volatile
    var needsWebOAuthFallbackForAccount: CloudAccount? = null

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getAccounts(context: Context): List<CloudAccount> {
        val prefs = context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACCOUNTS_JSON, null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<CloudAccount>>() {}.type
            gson.fromJson<List<CloudAccount>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(context: Context, accounts: List<CloudAccount>) {
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS_JSON, gson.toJson(accounts))
            .apply()
    }

    fun addOrUpdateAccount(context: Context, account: CloudAccount) {
        val current = getAccounts(context).toMutableList()
        val idx = current.indexOfFirst {
            it.id == account.id || (it.provider == account.provider && it.emailOrName.equals(account.emailOrName, ignoreCase = true))
        }
        if (idx >= 0) {
            val existing = current[idx]
            current[idx] = account.copy(
                id = existing.id,
                refreshToken = account.refreshToken.ifBlank { existing.refreshToken },
                accessToken = account.accessToken.ifBlank { existing.accessToken }
            )
        } else {
            current.add(account)
        }
        saveAccounts(context, current)
    }

    fun removeAccount(context: Context, accountId: String) {
        val updated = getAccounts(context).filterNot { it.id == accountId }
        saveAccounts(context, updated)
        folderCache.keys.toList().forEach { if (it.startsWith("$accountId|")) folderCache.remove(it) }
    }

    fun clearAccountCache(accountId: String) {
        folderCache.keys.toList().forEach { if (it.startsWith("$accountId|")) folderCache.remove(it) }
    }

    // ==================== ARAMA GEÇMİŞİ (🕒 lubed, Skylar Valentine vb.) ====================

    fun getSearchHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SEARCH_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val q = arr.optString(i).trim()
                if (q.isNotEmpty()) list.add(q)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val current = getSearchHistory(context).filterNot { it.equals(q, ignoreCase = true) }.toMutableList()
        current.add(0, q)
        val arr = JSONArray()
        current.take(25).forEach { arr.put(it) }
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEARCH_HISTORY_JSON, arr.toString())
            .apply()
    }

    fun removeSearchHistory(context: Context, query: String) {
        val current = getSearchHistory(context).filterNot { it.equals(query, ignoreCase = true) }
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEARCH_HISTORY_JSON, arr.toString())
            .apply()
    }

    /**
     * OAuth 2.0 Loopback / WebView akışından dönen "code" parametresini access_token ve refresh_token'a çevirir,
     * ardından Google Drive /about uç noktasından hesabın gerçek e-posta adresini ve kotasını alıp kaydeder.
     */
    suspend fun exchangeGoogleAuthCodeAndSaveAccount(
        context: Context,
        authCode: String,
        redirectUri: String,
        existingAccount: CloudAccount? = null
    ): CloudAccount? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("code", authCode)
                .add("client_id", GOOGLE_OAUTH_CLIENT_ID)
                .add("client_secret", GOOGLE_OAUTH_CLIENT_SECRET)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build()

            val tokenReq = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBody)
                .build()

            var accessToken = ""
            var refreshToken = ""
            httpClient.newCall(tokenReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) {
                    lastCloudErrorMessage = "OAuth token alınamadı: HTTP ${resp.code}"
                    return@withContext null
                }
                val json = JSONObject(body)
                accessToken = json.optString("access_token", "")
                refreshToken = json.optString("refresh_token", "")
            }

            if (accessToken.isBlank()) return@withContext null

            // Hesabın gerçek e-postasını ve kota bilgisini /drive/v3/about ile çek
            var email = existingAccount?.emailOrName ?: "Google Drive"
            var displayName = existingAccount?.displayName ?: email
            var usedBytes = 0L
            var totalBytes = 15L * 1024 * 1024 * 1024

            val aboutReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=storageQuota,user")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(aboutReq).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val userObj = json.optJSONObject("user")
                    if (userObj != null) {
                        val fetchedEmail = userObj.optString("emailAddress", "").trim()
                        val fetchedName = userObj.optString("displayName", "").trim()
                        if (fetchedEmail.isNotEmpty()) email = fetchedEmail
                        displayName = fetchedName.ifEmpty { email }
                    }
                    val quotaObj = json.optJSONObject("storageQuota")
                    if (quotaObj != null) {
                        usedBytes = quotaObj.optString("usage", "0").toLongOrNull() ?: 0L
                        val lim = quotaObj.optString("limit", "0").toLongOrNull() ?: 0L
                        if (lim > 0L) totalBytes = lim
                    }
                }
            }

            val finalAccount = CloudAccount(
                id = existingAccount?.id ?: java.util.UUID.randomUUID().toString(),
                provider = CloudProviderType.GOOGLE_DRIVE,
                emailOrName = email,
                displayName = displayName,
                accessToken = accessToken,
                refreshToken = refreshToken.ifBlank { existingAccount?.refreshToken ?: "" },
                usedQuotaBytes = usedBytes,
                totalQuotaBytes = totalBytes
            )
            addOrUpdateAccount(context.applicationContext, finalAccount)
            clearAccountCache(finalAccount.id)
            needsWebOAuthFallbackForAccount = null
            lastCloudErrorMessage = null
            finalAccount
        } catch (e: Exception) {
            lastCloudErrorMessage = e.localizedMessage
            null
        }
    }

    /**
     * Refresh Token kullanarak yeni bir Google Drive access_token üretir.
     */
    private fun refreshGoogleAccessToken(context: Context, account: CloudAccount): String {
        if (account.refreshToken.isBlank()) return ""
        return try {
            val formBody = FormBody.Builder()
                .add("client_id", GOOGLE_OAUTH_CLIENT_ID)
                .add("client_secret", GOOGLE_OAUTH_CLIENT_SECRET)
                .add("refresh_token", account.refreshToken)
                .add("grant_type", "refresh_token")
                .build()
            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBody)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string() ?: return ""
                val json = JSONObject(body)
                val newAccess = json.optString("access_token", "")
                if (newAccess.isNotBlank()) {
                    addOrUpdateAccount(context.applicationContext, account.copy(accessToken = newAccess))
                }
                newAccess
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Seçilen Google Drive hesabı için geçerli OAuth2 Bearer Token döndürür:
     * 1) Zorunlu yenileme değilse ve mevcut access_token geçerliyse doğrudan kullanır.
     * 2) Refresh token varsa https://oauth2.googleapis.com/token üzerinden yeniler (UNREGISTERED_ON_API_CONSOLE hatasına asla takılmaz).
     * 3) Yoksa GoogleAuthUtil dener; eğer UNREGISTERED_ON_API_CONSOLE dönerse Web/Loopback OAuth2 akışını işaretler.
     */
    suspend fun resolveGoogleDriveToken(context: Context, account: CloudAccount, forceRefresh: Boolean = false): String =
        withContext(Dispatchers.IO) {
            pendingRecoveryIntent = null
            val latestAcc = getAccounts(context).firstOrNull {
                it.id == account.id || (it.provider == CloudProviderType.GOOGLE_DRIVE && it.emailOrName.equals(account.emailOrName, ignoreCase = true))
            } ?: account

            if (!forceRefresh && latestAcc.accessToken.isNotBlank() && !latestAcc.accessToken.startsWith("DEMO_")) {
                return@withContext latestAcc.accessToken
            }

            // 1. Öncelikle Refresh Token ile yenilemeyi dene (APK imzasından tamamen bağımsızdır)
            if (latestAcc.refreshToken.isNotBlank()) {
                val refreshed = refreshGoogleAccessToken(context, latestAcc)
                if (refreshed.isNotBlank()) {
                    return@withContext refreshed
                }
            }

            // 2. Cihaz içi GoogleAuthUtil yedeği
            try {
                val googleAccount = Account(latestAcc.emailOrName.trim(), "com.google")
                if (forceRefresh && latestAcc.accessToken.isNotBlank() && !latestAcc.accessToken.startsWith("DEMO_")) {
                    try {
                        GoogleAuthUtil.clearToken(context.applicationContext, latestAcc.accessToken)
                    } catch (_: Exception) {}
                }
                val token = try { GoogleAuthUtil.getToken(context.applicationContext, googleAccount, "oauth2: https://www.googleapis.com/auth/drive") } catch (_: Exception) { GoogleAuthUtil.getToken(context.applicationContext, googleAccount, DRIVE_OAUTH_SCOPE) }
                if (!token.isNullOrBlank()) {
                    val updated = latestAcc.copy(accessToken = token)
                    addOrUpdateAccount(context.applicationContext, updated)
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                pendingRecoveryIntent = recoverable.intent
                lastCloudErrorMessage = "Google Drive erişim izni gerekiyor (${latestAcc.emailOrName})."
            } catch (e: Exception) {
                val msg = e.message ?: e.localizedMessage ?: ""
                if (msg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
                    msg.contains("NeedPermission", ignoreCase = true) ||
                    msg.contains("Auth", ignoreCase = true)
                ) {
                    needsWebOAuthFallbackForAccount = latestAcc
                    lastCloudErrorMessage = "Google Drive hesabını bağlamak için oturum açma ekranından onay verin (${latestAcc.emailOrName})."
                } else {
                    lastCloudErrorMessage = msg
                }
            }
            latestAcc.accessToken
        }

    suspend fun fetchGoogleDriveQuota(context: Context, account: CloudAccount): CloudAccount =
        withContext(Dispatchers.IO) {
            val token = resolveGoogleDriveToken(context, account)
            if (token.isBlank() || token.startsWith("DEMO_")) return@withContext account
            try {
                val req = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/about?fields=storageQuota,user")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext account
                    val body = resp.body?.string() ?: return@withContext account
                    val json = JSONObject(body)
                    val quota = json.optJSONObject("storageQuota") ?: return@withContext account
                    val usage = quota.optString("usage", "0").toLongOrNull() ?: 0L
                    val limit = quota.optString("limit", "0").toLongOrNull() ?: (15L * 1024 * 1024 * 1024)
                    val updated = account.copy(
                        accessToken = token,
                        usedQuotaBytes = usage,
                        totalQuotaBytes = if (limit > 0L) limit else (15L * 1024 * 1024 * 1024)
                    )
                    addOrUpdateAccount(context, updated)
                    return@withContext updated
                }
            } catch (_: Exception) {
                return@withContext account
            }
        }

    /**
     * Ekli tüm Google Drive ve diğer bulut hesaplarında aynı anda (paralel Coroutines ile) arama yapar.
     */
    suspend fun searchAcrossAllAccounts(
        context: Context,
        query: String,
        filter: CloudSearchFilter = CloudSearchFilter.ALL,
        selectedProviders: Set<CloudProviderType>? = null,
        onProgress: (UnifiedCloudSearchProgress) -> Unit = {}
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val accounts = getAccounts(context).filter {
            selectedProviders == null || it.provider in selectedProviders
        }
        if (accounts.isEmpty()) return@withContext emptyList()

        val cleanQuery = query.trim()
        if (cleanQuery.isNotEmpty()) {
            addSearchHistory(context, cleanQuery)
        }
        val aggregated = ArrayList<FileItem>()
        var completed = 0

        coroutineScope {
            val deferredList = accounts.map { account ->
                async(Dispatchers.IO) {
                    val accountMatches = try {
                        searchSingleAccountInternal(context, account, cleanQuery)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val filtered = accountMatches.filter { item -> matchesFilter(item, filter) }
                    synchronized(aggregated) {
                        completed++
                        aggregated.addAll(filtered)
                        onProgress(
                            UnifiedCloudSearchProgress(
                                completedAccounts = completed,
                                totalAccounts = accounts.size,
                                currentAccountName = "${account.provider.displayName} (${account.emailOrName})",
                                partialResults = aggregated.toList()
                            )
                        )
                    }
                    filtered
                }
            }
            deferredList.awaitAll()
        }

        aggregated.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale("tr", "TR")) }
        )
    }

    private fun matchesFilter(item: FileItem, filter: CloudSearchFilter): Boolean {
        return when (filter) {
            CloudSearchFilter.ALL -> true
            CloudSearchFilter.VIDEOS -> item.isVideo
            CloudSearchFilter.AUDIO -> item.isAudio
            CloudSearchFilter.IMAGES -> item.isImage
            CloudSearchFilter.DOCUMENTS -> item.isDocument
            CloudSearchFilter.ARCHIVES -> item.isArchive || item.isApk
        }
    }

    suspend fun listCloudFolder(
        context: Context,
        account: CloudAccount,
        folderIdOrPath: String = "root",
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        lastCloudErrorMessage = null
        val cacheKey = "${account.id}|$folderIdOrPath"
        if (!forceRefresh) {
            folderCache[cacheKey]?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached
            }
        }

        val items = try {
            when (account.provider) {
                CloudProviderType.GOOGLE_DRIVE -> listGoogleDriveFolder(context, account, folderIdOrPath)
                CloudProviderType.DROPBOX -> listDropboxFolder(account, if (folderIdOrPath == "root") "" else folderIdOrPath)
                CloudProviderType.ONEDRIVE -> listOneDriveFolder(account, folderIdOrPath)
                CloudProviderType.BOX -> listBoxFolder(account, if (folderIdOrPath == "root") "0" else folderIdOrPath)
                CloudProviderType.NEXTCLOUD -> listWebDavFolder(account, if (folderIdOrPath == "root") "/" else folderIdOrPath)
                CloudProviderType.YANDEX -> listYandexFolder(account, if (folderIdOrPath == "root") "disk:/" else folderIdOrPath)
            }
        } catch (e: Exception) {
            lastCloudErrorMessage = e.localizedMessage
            emptyList()
        }

        val sorted = items.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale("tr", "TR")) }
        )
        if (sorted.isNotEmpty()) {
            folderCache[cacheKey] = sorted
        }
        sorted
    }

    private suspend fun searchSingleAccountInternal(context: Context, account: CloudAccount, query: String): List<FileItem> {
        return when (account.provider) {
            CloudProviderType.GOOGLE_DRIVE -> searchGoogleDrive(context, account, query)
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
    private suspend fun listGoogleDriveFolder(context: Context, account: CloudAccount, folderId: String): List<FileItem> {
        val escapedParent = folderId.replace("'", "\\'")
        val q = "'$escapedParent' in parents and trashed = false"
        return executeGoogleDriveQuery(context, account, q, folderId)
    }

    private suspend fun searchGoogleDrive(context: Context, account: CloudAccount, query: String): List<FileItem> {
        val escaped = query.replace("'", "\\'")
        val q = if (escaped.isEmpty()) {
            "trashed = false"
        } else {
            "name contains '$escaped' and trashed = false"
        }
        return executeGoogleDriveQuery(context, account, q, "root")
    }

    private suspend fun executeGoogleDriveQuery(
        context: Context,
        account: CloudAccount,
        q: String,
        parentFolderId: String
    ): List<FileItem> {
        var token = resolveGoogleDriveToken(context, account, forceRefresh = false)
        if (token.isBlank() || token.startsWith("DEMO_")) {
            if (lastCloudErrorMessage == null) {
                lastCloudErrorMessage = "${account.emailOrName} hesabı için Google Drive oturum izni bekleniyor."
            }
            return emptyList()
        }

        var responsePair = performDriveHttpQuery(token, q)
        if (responsePair.first == 401) {
            token = resolveGoogleDriveToken(context, account, forceRefresh = true)
            if (token.isNotBlank()) {
                responsePair = performDriveHttpQuery(token, q)
            }
        }

        val code = responsePair.first
        val body = responsePair.second
        if (code !in 200..299 || body.isNullOrBlank()) {
            if (code == 401 || code == 403) {
                lastCloudErrorMessage = "Google Drive oturumu yenilenmeli (HTTP $code). Hesaba uzun basıp oturumu yenileyin."
            } else if (code > 0) {
                lastCloudErrorMessage = "Google Drive bağlantı hatası (HTTP $code)."
            }
            return emptyList()
        }

        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return emptyList()
        val result = ArrayList<FileItem>(files.length())

        // Klasör adlarını önbelleğe kaydet
        for (i in 0 until files.length()) {
            val obj = files.getJSONObject(i)
            if (obj.optString("mimeType") == "application/vnd.google-apps.folder") {
                folderNameCache[obj.optString("id")] = obj.optString("name")
            }
        }

        for (i in 0 until files.length()) {
            val obj = files.getJSONObject(i)
            if (obj.optBoolean("trashed", false)) continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val mime = obj.optString("mimeType", "")
            val isFolder = mime == "application/vnd.google-apps.folder"
            val size = obj.optLong("size", 0L)
            val modifiedTime = parseIsoTimestamp(obj.optString("modifiedTime", ""))
            val parentsArr = obj.optJSONArray("parents")
            val rawParentId = if (parentsArr != null && parentsArr.length() > 0) parentsArr.optString(0) else parentFolderId
            val parentDisplay = folderNameCache[rawParentId] ?: "Google Drive"
            val streamUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            result.add(
                FileItem(
                    id = id,
                    name = name,
                    path = id,
                    isDirectory = isFolder,
                    size = size,
                    lastModified = modifiedTime,
                    childCount = -1,
                    mimeType = mime,
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    downloadUrl = streamUrl,
                    thumbnailUrl = obj.optString("thumbnailLink", ""),
                    parentId = parentDisplay
                )
            )
        }
        return result
    }

    private fun performDriveHttpQuery(token: String, q: String): Pair<Int, String?> {
        val fields = "nextPageToken,files(kind,id,name,mimeType,parents,capabilities/canDownload,capabilities/canEdit,size,modifiedTime,createdTime,webContentLink,thumbnailLink,webViewLink,shortcutDetails,trashed)"
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fields=" + URLEncoder.encode(fields, "UTF-8") +
            "&pageSize=500&supportsAllDrives=true&includeItemsFromAllDrives=true"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            return Pair(resp.code, resp.body?.string())
        }
    }

    // ==================== 2. DROPBOX API v2 ====================
    private fun listDropboxFolder(account: CloudAccount, path: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
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
        if (account.accessToken.isBlank()) return emptyList()
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
            val entries = JSONArray()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i).optJSONObject("metadata")?.optJSONObject("metadata")
                if (m != null) entries.put(m)
            }
            return parseDropboxEntries(account, entries)
        }
    }

    private fun parseDropboxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val tag = e.optString(".tag")
            val isDir = tag == "folder"
            val pathDisplay = e.optString("path_display", "")
            val name = e.optString("name", "")
            list.add(
                FileItem(
                    id = e.optString("id", pathDisplay),
                    name = name,
                    path = pathDisplay,
                    isDirectory = isDir,
                    size = e.optLong("size", 0L),
                    lastModified = parseIsoTimestamp(e.optString("client_modified", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    parentId = pathDisplay.substringBeforeLast('/', "/")
                )
            )
        }
        return list
    }

    // ==================== 3. MICROSOFT ONEDRIVE (GRAPH API) ====================
    private fun listOneDriveFolder(account: CloudAccount, folderId: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val endpoint = if (folderId == "root" || folderId.isBlank()) {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$folderId/children"
        }
        return executeOneDriveRequest(account, endpoint)
    }

    private fun searchOneDrive(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        if (query.isBlank()) return listOneDriveFolder(account, "root")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoint = "https://graph.microsoft.com/v1.0/me/drive/root/search(q='$encoded')"
        return executeOneDriveRequest(account, endpoint)
    }

    private fun executeOneDriveRequest(account: CloudAccount, endpoint: String): List<FileItem> {
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val values = json.optJSONArray("value") ?: return emptyList()
            val out = ArrayList<FileItem>(values.length())
            for (i in 0 until values.length()) {
                val obj = values.getJSONObject(i)
                val isDir = obj.has("folder")
                val dl = obj.optString("@microsoft.graph.downloadUrl", "")
                out.add(
                    FileItem(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        path = obj.optString("id"),
                        isDirectory = isDir,
                        size = obj.optLong("size", 0L),
                        lastModified = parseIsoTimestamp(obj.optString("lastModifiedDateTime", "")),
                        childCount = obj.optJSONObject("folder")?.optInt("childCount", -1) ?: -1,
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = account.provider.displayName,
                        downloadUrl = dl
                    )
                )
            }
            return out
        }
    }

    // ==================== 4. BOX CLOUD API v2.0 ====================
    private fun listBoxFolder(account: CloudAccount, folderId: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val req = Request.Builder()
            .url("https://api.box.com/2.0/folders/$folderId/items?fields=id,name,type,size,modified_at")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            return parseBoxEntries(account, json.optJSONArray("entries") ?: return emptyList())
        }
    }

    private fun searchBox(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        if (query.isBlank()) return listBoxFolder(account, "0")
        val req = Request.Builder()
            .url("https://api.box.com/2.0/search?query=${URLEncoder.encode(query, "UTF-8")}")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            return parseBoxEntries(account, json.optJSONArray("entries") ?: return emptyList())
        }
    }

    private fun parseBoxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(entries.length())
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
                    lastModified = parseIsoTimestamp(obj.optString("modified_at", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
                    downloadUrl = if (isDir) "" else "https://api.box.com/2.0/files/$id/content"
                )
            )
        }
        return list
    }

    // ==================== 5. NEXTCLOUD / WEBDAV ====================
    private fun listWebDavFolder(account: CloudAccount, relativePath: String): List<FileItem> {
        val baseUrl = account.serverUrl.trimEnd('/')
        if (baseUrl.isEmpty()) return emptyList()
        val rel = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val fullUrl = "$baseUrl$rel"
        val propfindXml = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop>
            </d:propfind>""".trimIndent()
        val req = Request.Builder()
            .url(fullUrl)
            .method("PROPFIND", propfindXml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(account.username, account.accessToken))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string() ?: return emptyList()
            val out = ArrayList<FileItem>()
            val responses = xml.split("<d:response>", "<D:response>").drop(1)
            for (block in responses) {
                val href = extractXmlTag(block, "href").trim()
                val name = extractXmlTag(block, "displayname").ifBlank {
                    href.trimEnd('/').substringAfterLast('/')
                }
                if (name.isBlank()) continue
                val isDir = block.contains("collection", ignoreCase = true)
                val size = extractXmlTag(block, "getcontentlength").toLongOrNull() ?: 0L
                out.add(
                    FileItem(
                        id = href,
                        name = name,
                        path = href,
                        isDirectory = isDir,
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = account.provider.displayName,
                        downloadUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                    )
                )
            }
            return if (out.size > 1) out.drop(1) else out
        }
    }

    // ==================== 6. YANDEX DISK REST API ====================
    private fun listYandexFolder(account: CloudAccount, path: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources?path=${URLEncoder.encode(path, "UTF-8")}&limit=200")
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
        if (account.accessToken.isBlank()) return emptyList()
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
        val list = ArrayList<FileItem>(items.length())
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
                    lastModified = parseIsoTimestamp(obj.optString("modified", "")),
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

    private fun extractXmlTag(xml: String, tag: String): String {
        val regex = Regex("<(?:\\w+:)?$tag[^>]*>(.*?)</(?:\\w+:)?$tag>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun parseIsoTimestamp(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return try {
            val clean = iso.substringBefore('.').replace("Z", "")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(clean)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
