package com.alphainventor.filemanager.cloud

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
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
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
 * Orijinal Dosya Yöneticisi + 3.6.7 ile birebir uyumlu Çoklu Bulut Motoru:
 * 1) Google Drive:
 *    - Cihazdaki "com.google" hesaplarını AccountPicker ile seçer.
 *    - Özel keystore (GitHub Actions personal-release-key.jks) ile imzalanan APK'larda GoogleAuthUtil("oauth2:...")
 *      SHA-1 imza kontrolüne takıldığında, cihazdaki açık Google hesabından AccountManager "weblogin:service=gaia"
 *      (MergeSession) köprüsüyle e-posta ve şifre sormadan doğrudan OAuth2 Access Token + Refresh Token alır!
 * 2) MEGA (mega.nz):
 *    - Doğrudan resmi MEGA JSON-RPC API (us0 + us + ug + uq + f + g) ve düzeltilmiş MPI RSA csid çözümü ile
 *      herhangi bir WebView yönlendirmesine ihtiyaç duymadan çalışır.
 *    - Dosya açma/izleme için "a":"g" + AES-128-CTR akış şifre çözme desteği içerir.
 */
object CloudManager {
    private const val PREF_CLOUD_ACCOUNTS = "CloudAccountsRegistryPrefs"
    private const val KEY_ACCOUNTS_JSON = "accounts_json_v3"
    private const val KEY_SEARCH_HISTORY_JSON = "cloud_search_history_v1"
    private const val KEY_MEGA_WEB_NODES_PREFIX = "mega_web_nodes_v1_"

    private const val DRIVE_OAUTH_SCOPE_ORIG = "oauth2: https://www.googleapis.com/auth/drive"
    private const val DRIVE_OAUTH_SCOPE = "oauth2:https://www.googleapis.com/auth/drive"

    const val GOOGLE_OAUTH_CLIENT_ID = "202264815644.apps.googleusercontent.com"
    const val GOOGLE_OAUTH_CLIENT_SECRET = "X4Z3ca8xfWDb1Voo-F9a7ZxJ"
    const val GOOGLE_OAUTH_SCOPE_URL = "https://www.googleapis.com/auth/drive"

    private val gson = Gson()

    private val folderCache: MutableMap<String, List<FileItem>> = ConcurrentHashMap()
    private val folderNameCache: MutableMap<String, String> = ConcurrentHashMap()
    private val megaAllItemsCache: MutableMap<String, List<FileItem>> = ConcurrentHashMap()
    private val megaNodeKeysCache: MutableMap<String, String> = ConcurrentHashMap()

    @Volatile
    var pendingRecoveryIntent: Intent? = null

    @Volatile
    var pendingWebLoginRecoveryIntent: Intent? = null

    @Volatile
    var lastCloudErrorMessage: String? = null

    @Volatile
    var lastMegaErrorCode: Int = 0

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

    fun getCachedCloudFolder(accountId: String, folderIdOrPath: String = "root"): List<FileItem>? {
        val list = folderCache["$accountId|$folderIdOrPath"]
        return if (!list.isNullOrEmpty()) list else null
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
                accessToken = account.accessToken.ifBlank { existing.accessToken },
                usedQuotaBytes = if (account.usedQuotaBytes > 0L) account.usedQuotaBytes else existing.usedQuotaBytes,
                totalQuotaBytes = if (account.totalQuotaBytes > 0L) account.totalQuotaBytes else existing.totalQuotaBytes
            )
        } else {
            current.add(account)
        }
        saveAccounts(context, current)
    }

    fun invalidateAccountAccessToken(context: Context, accountId: String) {
        val current = getAccounts(context).toMutableList()
        val idx = current.indexOfFirst { it.id == accountId }
        if (idx >= 0) {
            val oldToken = current[idx].accessToken
            if (oldToken.isNotBlank()) {
                try {
                    GoogleAuthUtil.clearToken(context.applicationContext, oldToken)
                } catch (_: Throwable) {
                }
                try {
                    AccountManager.get(context.applicationContext).invalidateAuthToken("com.google", oldToken)
                } catch (_: Throwable) {
                }
            }
            current[idx] = current[idx].copy(accessToken = "")
            saveAccounts(context, current)
        }
    }

    fun removeAccount(context: Context, accountId: String) {
        val updated = getAccounts(context).filterNot { it.id == accountId }
        saveAccounts(context, updated)
        folderCache.keys.toList().forEach { if (it.startsWith("$accountId|")) folderCache.remove(it) }
        megaAllItemsCache.remove(accountId)
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MEGA_WEB_NODES_PREFIX + accountId)
            .apply()
    }

    fun clearAccountCache(accountId: String) {
        folderCache.keys.toList().forEach { if (it.startsWith("$accountId|")) folderCache.remove(it) }
        megaAllItemsCache.remove(accountId)
    }

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
     * Cihazdaki açık Google hesabından ("com.google") SHA-1 sertifika kısıtlamasına takılmadan
     * tek adımlı otomatik oturum açma (MergeSession) URL'si üretir:
     * - Önce AccountManager.getAuthToken(..., activity, ...) çağrılır: eğer sistem "OK / İzin ver"
     *   (GrantCredentialsPermissionActivity) penceresi gerekiyorsa Android bunu doğrudan Activity
     *   üzerinde gösterir ve kullanıcı OK dediğinde aynı çağrı içinde MergeSession URL'sini döndürür!
     * - Böylece kullanıcıya asla e-posta veya şifre sorulmaz.
     */
    suspend fun tryGetWebLoginMergeSessionUrl(
        context: Context,
        activity: Activity?,
        email: String,
        continueUrl: String
    ): String? = withContext(Dispatchers.IO) {
        pendingWebLoginRecoveryIntent = null
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) return@withContext null

        val appCtx = context.applicationContext
        val am = AccountManager.get(appCtx)
        val googleAccount = try {
            am.getAccountsByType("com.google").firstOrNull {
                it.name.equals(cleanEmail, ignoreCase = true)
            } ?: Account(cleanEmail, "com.google")
        } catch (_: Throwable) {
            Account(cleanEmail, "com.google")
        }

        val encodedContinue = URLEncoder.encode(continueUrl, "UTF-8")
        val scopesToTry = listOf(
            "weblogin:service=gaia&continue=$encodedContinue",
            "weblogin:continue=$encodedContinue"
        )

        // 1. Öncelik: AccountManager.getAuthToken (Activity ile çağrıldığında sistem OK/İzin penceresini
        // kendi içinde açar ve kullanıcı OK dediğinde doğrudan KEY_AUTHTOKEN = MergeSession URL döndürür!)
        for (scope in scopesToTry) {
            try {
                @Suppress("DEPRECATION")
                val future = if (activity != null && !activity.isFinishing) {
                    am.getAuthToken(googleAccount, scope, null, activity, null, null)
                } else {
                    am.getAuthToken(googleAccount, scope, null, false, null, null)
                }
                val bundle = future.getResult(45, TimeUnit.SECONDS)
                val tokenUrl = bundle?.getString(AccountManager.KEY_AUTHTOKEN)
                if (!tokenUrl.isNullOrBlank() && (tokenUrl.startsWith("https://") || tokenUrl.startsWith("http://"))) {
                    return@withContext tokenUrl
                }
                @Suppress("DEPRECATION")
                val keyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle?.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
                } else {
                    bundle?.getParcelable(AccountManager.KEY_INTENT) as? Intent
                }
                if (keyIntent != null && pendingWebLoginRecoveryIntent == null) {
                    pendingWebLoginRecoveryIntent = keyIntent
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Yedek: GoogleAuthUtil.getToken
        for (scope in scopesToTry) {
            try {
                val tokenUrl = GoogleAuthUtil.getToken(appCtx, googleAccount, scope)
                if (!tokenUrl.isNullOrBlank() && (tokenUrl.startsWith("https://") || tokenUrl.startsWith("http://"))) {
                    pendingWebLoginRecoveryIntent = null
                    return@withContext tokenUrl
                }
            } catch (recoverable: UserRecoverableAuthException) {
                if (pendingWebLoginRecoveryIntent == null) {
                    pendingWebLoginRecoveryIntent = recoverable.intent
                }
            } catch (_: Throwable) {
            }
        }

        null
    }

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
                id = existingAccount?.id ?: UUID.randomUUID().toString(),
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
     * 1) Mevcut geçerli access_token varsa doğrudan döndürür.
     * 2) Refresh token varsa https://oauth2.googleapis.com/token üzerinden sessizce yeniler.
     * 3) GoogleAuthUtil.getToken("oauth2: https://www.googleapis.com/auth/drive") dener (yalnızca gerçek
     *    Play Services OAuth Consent ekranıysa pendingRecoveryIntent olarak döndürür; özel keystore nedeniyle
     *    başarısız olan GrantCredentialsPermission ekranına düşmez).
     * 4) Token alınamazsa needsWebOAuthFallbackForAccount işaretlenir ve MergeSession köprüsüyle şifresiz tamamlanır.
     */
    suspend fun resolveGoogleDriveToken(
        context: Context,
        account: CloudAccount,
        forceRefresh: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        pendingRecoveryIntent = null
        needsWebOAuthFallbackForAccount = null

        val latestAcc = getAccounts(context).firstOrNull {
            it.id == account.id || (it.provider == CloudProviderType.GOOGLE_DRIVE && it.emailOrName.equals(account.emailOrName, ignoreCase = true))
        } ?: account

        if (!forceRefresh && latestAcc.accessToken.isNotBlank() && !latestAcc.accessToken.startsWith("DEMO_")) {
            return@withContext latestAcc.accessToken
        }

        if (latestAcc.refreshToken.isNotBlank()) {
            val refreshed = refreshGoogleAccessToken(context, latestAcc)
            if (refreshed.isNotBlank()) {
                return@withContext refreshed
            }
        }

        if (forceRefresh && latestAcc.accessToken.isNotBlank()) {
            invalidateAccountAccessToken(context.applicationContext, latestAcc.id)
        }

        val cleanEmail = latestAcc.emailOrName.trim()
        if (cleanEmail.isBlank()) return@withContext ""

        val appCtx = context.applicationContext
        val am = AccountManager.get(appCtx)
        val googleAccount = try {
            am.getAccountsByType("com.google").firstOrNull {
                it.name.equals(cleanEmail, ignoreCase = true)
            } ?: Account(cleanEmail, "com.google")
        } catch (_: Throwable) {
            Account(cleanEmail, "com.google")
        }

        val scopesToTry = listOf(DRIVE_OAUTH_SCOPE_ORIG, DRIVE_OAUTH_SCOPE)
        for (scopeStr in scopesToTry) {
            try {
                val token = GoogleAuthUtil.getToken(appCtx, googleAccount, scopeStr)
                if (!token.isNullOrBlank()) {
                    val updated = latestAcc.copy(accessToken = token)
                    addOrUpdateAccount(appCtx, updated)
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                val comp = recoverable.intent?.component?.className ?: ""
                if (!comp.contains("GrantCredentialsPermission", ignoreCase = true)) {
                    pendingRecoveryIntent = recoverable.intent
                    return@withContext ""
                }
            } catch (_: Throwable) {
            }
        }

        needsWebOAuthFallbackForAccount = latestAcc.copy(accessToken = "")
        ""
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

    // =========================================================================
    // MEGA (mega.nz) Bulut Motoru (Doğrudan API v1/v2 + AES-128-CTR İndirme)
    // =========================================================================

    suspend fun authenticateMegaAccount(
        context: Context,
        email: String,
        password: String,
        existingAccount: CloudAccount? = null,
        mfaPin: String? = null
    ): CloudAccount? = withContext(Dispatchers.IO) {
        lastCloudErrorMessage = null
        lastMegaErrorCode = 0
        try {
            val cleanEmail = email.trim().lowercase(Locale.ROOT)
            if (cleanEmail.isBlank() || password.isEmpty()) {
                lastCloudErrorMessage = "E-posta ve şifre boş olamaz."
                return@withContext null
            }

            // 1. Adım: "us0" ile hesap sürümünü (v=1 veya v=2) ve tuzu (s) öğren
            val us0Payload = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "us0")
                    put("user", cleanEmail)
                })
            }.toString()

            val us0RespStr = postMegaJsonRpc(us0Payload) ?: run {
                lastCloudErrorMessage = "MEGA sunucusuna ulaşılamadı. İnternet bağlantınızı kontrol edin."
                return@withContext null
            }

            val us0Arr = JSONArray(us0RespStr)
            val us0First = us0Arr.opt(0)
            if (us0First is Number) {
                val code = us0First.toInt()
                lastMegaErrorCode = code
                lastCloudErrorMessage = megaErrorDescription(code)
                return@withContext null
            }
            val us0Obj = us0First as? JSONObject ?: return@withContext null
            val version = us0Obj.optInt("v", 1)

            val passwordAesKey: ByteArray
            val userHashB64: String

            if (version >= 2 && us0Obj.has("s")) {
                val saltBytes = base64UrlDecode(us0Obj.getString("s"))
                val derived32 = pbkdf2HmacSha512(password.toByteArray(Charsets.UTF_8), saltBytes, 100_000, 32)
                passwordAesKey = derived32.copyOfRange(0, 16)
                userHashB64 = base64UrlEncode(derived32.copyOfRange(16, 32))
            } else {
                passwordAesKey = prepareKeyV1(password.toByteArray(Charsets.UTF_8))
                userHashB64 = stringHashV1(cleanEmail, passwordAesKey)
            }

            // 2. Adım: "us" ile oturum aç ve şifreli ana anahtar (k) + RSA özel anahtarını (privk) + csid'yi al
            val usPayload = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "us")
                    put("user", cleanEmail)
                    put("uh", userHashB64)
                    if (!mfaPin.isNullOrBlank()) {
                        put("mfa", mfaPin.trim())
                    }
                })
            }.toString()

            val usRespStr = postMegaJsonRpc(usPayload) ?: run {
                lastCloudErrorMessage = "MEGA oturum yanıtı alınamadı."
                return@withContext null
            }
            val usArr = JSONArray(usRespStr)
            val usFirst = usArr.opt(0)
            if (usFirst is Number) {
                val errCode = usFirst.toInt()
                lastMegaErrorCode = errCode
                lastCloudErrorMessage = megaErrorDescription(errCode)
                return@withContext null
            }
            val usObj = usFirst as? JSONObject
            if (usObj == null) {
                lastCloudErrorMessage = "MEGA oturum verisi çözümlenemedi."
                return@withContext null
            }

            val encMasterKey = base64UrlDecode(usObj.optString("k", ""))
            val masterKey = aesEcbDecrypt(passwordAesKey, encMasterKey)

            var sidCandidates: List<String> = emptyList()
            val encPrivk = base64UrlDecode(usObj.optString("privk", ""))
            val privkDec = aesEcbDecrypt(masterKey, encPrivk)
            val csidEnc = base64UrlDecode(usObj.optString("csid", ""))
            if (csidEnc.isNotEmpty() && privkDec.size >= 128) {
                sidCandidates = decryptMegaRsaSessionIdCandidates(privkDec, csidEnc)
            }

            // Hangi sid adayının geçerli olduğunu "ug" (kullanıcı bilgisi) çağrısıyla doğrula
            var verifiedSid = ""
            for (cand in sidCandidates) {
                if (cand.isBlank()) continue
                val ugPayload = JSONArray().apply {
                    put(JSONObject().apply { put("a", "ug") })
                }.toString()
                val ugResp = postMegaJsonRpc(ugPayload, cand)
                val trimUg = ugResp?.trim().orEmpty()
                if (trimUg.startsWith("[{") && trimUg.contains("\"u\"")) {
                    verifiedSid = cand
                    break
                }
            }
            val sid = verifiedSid.ifBlank { sidCandidates.firstOrNull().orEmpty() }

            // 3. Adım: "uq" ile kota bilgisini al
            var usedBytes = 0L
            var totalBytes = 20L * 1024 * 1024 * 1024
            if (sid.isNotBlank()) {
                val uqPayload = JSONArray().apply {
                    put(JSONObject().apply {
                        put("a", "uq")
                        put("strg", 1)
                    })
                }.toString()
                val uqResp = postMegaJsonRpc(uqPayload, sid)
                if (!uqResp.isNullOrBlank()) {
                    val uqObj = JSONArray(uqResp).optJSONObject(0)
                    if (uqObj != null) {
                        usedBytes = uqObj.optLong("cstrg", 0L)
                        val maxStrg = uqObj.optLong("mstrg", 0L)
                        if (maxStrg > 0L) totalBytes = maxStrg
                    }
                }
            }

            val saved = CloudAccount(
                id = existingAccount?.id ?: UUID.randomUUID().toString(),
                provider = CloudProviderType.MEGA,
                emailOrName = cleanEmail,
                displayName = cleanEmail,
                accessToken = sid,
                refreshToken = base64UrlEncode(masterKey),
                username = cleanEmail,
                usedQuotaBytes = usedBytes,
                totalQuotaBytes = totalBytes
            )
            addOrUpdateAccount(context.applicationContext, saved)
            clearAccountCache(saved.id)
            // İlk klasör ağacını hemen önbelleğe çek
            fetchAllMegaAccountItems(context.applicationContext, saved, forceRefresh = true)
            lastCloudErrorMessage = null
            saved
        } catch (e: Throwable) {
            lastCloudErrorMessage = "MEGA bağlantı hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}"
            null
        }
    }

    fun saveMegaWebSyncedAccountAndNodes(
        context: Context,
        email: String,
        usedBytes: Long,
        totalBytes: Long,
        nodesJsonArrayString: String,
        existingAccount: CloudAccount? = null
    ): CloudAccount {
        val cleanEmail = email.trim().ifBlank { "MEGA Hesabı" }
        val acc = CloudAccount(
            id = existingAccount?.id ?: UUID.randomUUID().toString(),
            provider = CloudProviderType.MEGA,
            emailOrName = cleanEmail,
            displayName = cleanEmail,
            accessToken = existingAccount?.accessToken?.takeIf { it.isNotBlank() } ?: "MEGA_WEB_SYNC",
            refreshToken = existingAccount?.refreshToken ?: "",
            username = cleanEmail,
            usedQuotaBytes = usedBytes.coerceAtLeast(0L),
            totalQuotaBytes = if (totalBytes > 0L) totalBytes else 20L * 1024 * 1024 * 1024
        )
        addOrUpdateAccount(context.applicationContext, acc)
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEGA_WEB_NODES_PREFIX + acc.id, nodesJsonArrayString)
            .apply()
        clearAccountCache(acc.id)
        return acc
    }

    private fun listMegaFolder(context: Context, account: CloudAccount, folderIdOrPath: String, forceRefresh: Boolean = false): List<FileItem> {
        val allNodes = fetchAllMegaAccountItems(context, account, forceRefresh)
        val targetParent = if (folderIdOrPath.isBlank() || folderIdOrPath == "/") "root" else folderIdOrPath
        return allNodes.filter { it.parentId == targetParent }
    }

    private fun searchMegaAccount(context: Context, account: CloudAccount, query: String): List<FileItem> {
        val allNodes = fetchAllMegaAccountItems(context, account, forceRefresh = false)
        if (query.isBlank()) return allNodes
        return allNodes.filter { matchesFileNameSmart(it.name, query) }
    }

    private fun fetchAllMegaAccountItems(context: Context, account: CloudAccount, forceRefresh: Boolean = false): List<FileItem> {
        if (!forceRefresh) {
            megaAllItemsCache[account.id]?.let { if (it.isNotEmpty()) return it }
        }

        val sid = account.accessToken
        val masterKeyB64 = account.refreshToken
        if (sid.isNotBlank() && sid != "MEGA_WEB_SYNC" && masterKeyB64.isNotBlank()) {
            try {
                val masterKey = base64UrlDecode(masterKeyB64)
                val payload = JSONArray().apply {
                    put(JSONObject().apply {
                        put("a", "f")
                        put("c", 1)
                        put("r", 1)
                    })
                }.toString()

                val respStr = postMegaJsonRpc(payload, sid)
                if (!respStr.isNullOrBlank()) {
                    val arr = JSONArray(respStr)
                    val rootObj = arr.optJSONObject(0)
                    val fArr = rootObj?.optJSONArray("f")
                    if (fArr != null) {
                        var cloudDriveRootHandle = ""
                        for (i in 0 until fArr.length()) {
                            val n = fArr.optJSONObject(i) ?: continue
                            if (n.optInt("t", -1) == 2) {
                                cloudDriveRootHandle = n.optString("h", "")
                                break
                            }
                        }

                        val items = ArrayList<FileItem>(fArr.length())
                        for (i in 0 until fArr.length()) {
                            val n = fArr.optJSONObject(i) ?: continue
                            val t = n.optInt("t", -1)
                            if (t != 0 && t != 1) continue
                            val handle = n.optString("h", "")
                            val rawParent = n.optString("p", "")
                            if (handle.isBlank()) continue

                            val kStr = n.optString("k", "")
                            val aStr = n.optString("a", "")
                            val nameAndKey = decryptMegaNodeAttributesAndKey(masterKey, kStr, aStr)
                            val name = nameAndKey?.first ?: "MEGA_$handle"
                            val fullNodeKeyB64 = nameAndKey?.second ?: ""
                            if (fullNodeKeyB64.isNotBlank()) {
                                megaNodeKeysCache["${account.id}|$handle"] = fullNodeKeyB64
                            }

                            val size = n.optLong("s", 0L).coerceAtLeast(0L)
                            val ts = n.optLong("ts", System.currentTimeMillis() / 1000L) * 1000L
                            val parentId = if (rawParent == cloudDriveRootHandle || rawParent.isBlank()) "root" else rawParent

                            items.add(
                                FileItem(
                                    id = handle,
                                    name = name,
                                    path = "/MEGA/$name",
                                    isDirectory = (t == 1),
                                    size = size,
                                    lastModified = ts,
                                    mimeType = if (t == 1) "inode/directory" else "",
                                    sourceType = FileSourceType.CLOUD,
                                    accountId = account.id,
                                    accountEmailOrLabel = account.emailOrName,
                                    providerName = CloudProviderType.MEGA.displayName,
                                    downloadUrl = "mega://$handle/$fullNodeKeyB64",
                                    parentId = parentId
                                )
                            )
                        }
                        if (items.isNotEmpty()) {
                            megaAllItemsCache[account.id] = items
                            return items
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return emptyList()
    }

    /**
     * MEGA üzerindeki bir dosyayı (fotoğraf, video, ses, belge) "a":"g" komutuyla geçici URL'sini alıp
     * AES-128-CTR ile şifresini çözerek yerel önbellek dizinine indirir.
     */
    suspend fun downloadMegaFileToCache(
        context: Context,
        account: CloudAccount,
        item: FileItem
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "mega_open_cache").apply { if (!exists()) mkdirs() }
            val safeName = item.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(cacheDir, "${item.id}_$safeName")
            if (targetFile.exists() && targetFile.length() > 0L && (item.size <= 0L || targetFile.length() == item.size)) {
                return@withContext targetFile
            }

            val sid = account.accessToken
            val handle = item.id
            val fullKeyB64 = megaNodeKeysCache["${account.id}|$handle"]
                ?: item.downloadUrl.removePrefix("mega://$handle/").takeIf { it.isNotBlank() && !it.startsWith("http") }
                ?: ""

            val decNodeKey = if (fullKeyB64.isNotBlank()) base64UrlDecode(fullKeyB64) else ByteArray(0)
            if (decNodeKey.size < 32) {
                lastCloudErrorMessage = "MEGA dosya şifre çözme anahtarı bulunamadı."
                return@withContext null
            }

            val aesKey = ByteArray(16)
            for (i in 0 until 16) {
                aesKey[i] = (decNodeKey[i].toInt() xor decNodeKey[i + 16].toInt()).toByte()
            }
            val iv = ByteArray(16)
            System.arraycopy(decNodeKey, 16, iv, 0, 8)

            val gPayload = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "g")
                    put("g", 1)
                    put("n", handle)
                })
            }.toString()

            val gRespStr = postMegaJsonRpc(gPayload, sid.takeIf { it != "MEGA_WEB_SYNC" }) ?: return@withContext null
            val gObj = JSONArray(gRespStr).optJSONObject(0) ?: return@withContext null
            val dlUrl = gObj.optString("g", "")
            if (dlUrl.isBlank()) return@withContext null

            val req = Request.Builder().url(dlUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStream = resp.body?.byteStream() ?: return@withContext null
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                CipherInputStream(bodyStream, cipher).use { cis ->
                    FileOutputStream(targetFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (cis.read(buf).also { read = it } != -1) {
                            fos.write(buf, 0, read)
                        }
                    }
                }
            }
            targetFile
        } catch (e: Throwable) {
            lastCloudErrorMessage = "MEGA dosya indirme hatası: ${e.localizedMessage}"
            null
        }
    }

    private fun postMegaJsonRpc(jsonBody: String, sid: String? = null): String? {
        val seq = (System.currentTimeMillis() and 0x7FFFFFFFL)
        val url = if (!sid.isNullOrBlank()) {
            "https://g.api.mega.co.nz/cs?id=$seq&sid=$sid"
        } else {
            "https://g.api.mega.co.nz/cs?id=$seq"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "DosyaYoneticisiPlus/3.6.7 Android")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim()
            if (body.isNullOrBlank()) return null
            return body
        }
    }

    private fun megaErrorDescription(code: Int): String {
        return when (code) {
            -2 -> "Geçersiz MEGA isteği (-2)."
            -3 -> "MEGA sunucusu şu an yoğun, lütfen tekrar deneyin (-3)."
            -9 -> "MEGA e-posta adresi veya şifre hatalı (-9)."
            -15 -> "MEGA oturum süresi doldu (-15)."
            -16 -> "Bu MEGA hesabı engellenmiş veya askıya alınmış (-16)."
            -26 -> "MEGA hesabınızda 2 Aşamalı Doğrulama (2FA) açık. Lütfen 6 haneli doğrulama kodunu girin."
            else -> "MEGA giriş hatası (Kod: $code)."
        }
    }

    private fun decryptMegaNodeAttributesAndKey(masterKey: ByteArray, kField: String, aField: String): Pair<String, String>? {
        if (kField.isBlank() || aField.isBlank()) return null
        return try {
            val keyPartB64 = kField.substringAfter(':').substringBefore('/')
            val encNodeKey = base64UrlDecode(keyPartB64)
            if (encNodeKey.isEmpty() || encNodeKey.size % 16 != 0) return null
            val decNodeKey = aesEcbDecrypt(masterKey, encNodeKey)

            val attrKey = if (decNodeKey.size >= 32) {
                val folded = ByteArray(16)
                for (i in 0 until 16) {
                    folded[i] = (decNodeKey[i].toInt() xor decNodeKey[i + 16].toInt()).toByte()
                }
                folded
            } else if (decNodeKey.size >= 16) {
                decNodeKey.copyOfRange(0, 16)
            } else return null

            val encAttr = base64UrlDecode(aField)
            val alignedAttrLen = (encAttr.size / 16) * 16
            if (alignedAttrLen == 0) return null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(attrKey, "AES"), IvParameterSpec(ByteArray(16)))
            val decAttr = cipher.doFinal(encAttr, 0, alignedAttrLen)
            val rawStr = String(decAttr, Charsets.UTF_8)
            if (!rawStr.startsWith("MEGA{")) return null
            val startIdx = rawStr.indexOf('{')
            val endIdx = rawStr.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) return null
            val jsonPart = rawStr.substring(startIdx, endIdx + 1)
            val name = JSONObject(jsonPart).optString("n", "").takeIf { it.isNotBlank() } ?: return null
            name to base64UrlEncode(decNodeKey)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decryptMegaRsaSessionIdCandidates(privkDec: ByteArray, csidBytes: ByteArray): List<String> {
        return try {
            var pos = 0
            fun readMpi(): BigInteger? {
                if (pos + 2 > privkDec.size) return null
                val bitLen = ((privkDec[pos].toInt() and 0xFF) shl 8) or (privkDec[pos + 1].toInt() and 0xFF)
                val byteLen = (bitLen + 7) / 8
                pos += 2
                if (byteLen <= 0 || pos + byteLen > privkDec.size) return null
                val slice = privkDec.copyOfRange(pos, pos + byteLen)
                pos += byteLen
                return BigInteger(1, slice)
            }

            val p = readMpi() ?: return emptyList()
            val q = readMpi() ?: return emptyList()
            val d = readMpi() ?: return emptyList()
            val n = p.multiply(q)

            val csidBig = if (csidBytes.size > 2) {
                val bitLen = ((csidBytes[0].toInt() and 0xFF) shl 8) or (csidBytes[1].toInt() and 0xFF)
                val byteLen = (bitLen + 7) / 8
                val actualEnd = (2 + byteLen).coerceAtMost(csidBytes.size)
                if (byteLen in 32..(csidBytes.size)) {
                    BigInteger(1, csidBytes.copyOfRange(2, actualEnd))
                } else {
                    BigInteger(1, csidBytes)
                }
            } else {
                BigInteger(1, csidBytes)
            }

            val m = csidBig.modPow(d, n)
            var rawBytes = m.toByteArray()
            if (rawBytes.size > 1 && rawBytes[0] == 0.toByte()) {
                rawBytes = rawBytes.copyOfRange(1, rawBytes.size)
            }

            val candidates = LinkedHashSet<String>()
            if (rawBytes.size >= 43) {
                candidates.add(base64UrlEncode(rawBytes.copyOfRange(0, 43)))
            }
            val zeroPrefixed = ByteArray(rawBytes.size + 1)
            System.arraycopy(rawBytes, 0, zeroPrefixed, 1, rawBytes.size)
            if (zeroPrefixed.size >= 43) {
                candidates.add(base64UrlEncode(zeroPrefixed.copyOfRange(0, 43)))
            }
            val modulusByteLen = (n.bitLength() + 7) / 8
            if (modulusByteLen >= 43 && rawBytes.size < modulusByteLen) {
                val padded = ByteArray(modulusByteLen)
                System.arraycopy(rawBytes, 0, padded, modulusByteLen - rawBytes.size, rawBytes.size)
                candidates.add(base64UrlEncode(padded.copyOfRange(0, 43)))
            }
            candidates.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun prepareKeyV1(passwordBytes: ByteArray): ByteArray {
        val paddedLen = ((passwordBytes.size + 15) / 16) * 16
        val padded = if (paddedLen > 0) passwordBytes.copyOf(paddedLen) else ByteArray(16)
        var pkey = byteArrayOf(
            0x93.toByte(), 0xC4.toByte(), 0x67.toByte(), 0xE3.toByte(),
            0x7D.toByte(), 0xB0.toByte(), 0xC7.toByte(), 0xA4.toByte(),
            0xD1.toByte(), 0xBE.toByte(), 0x3F.toByte(), 0x81.toByte(),
            0x01.toByte(), 0x52.toByte(), 0xCB.toByte(), 0x56.toByte()
        )
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        for (r in 0 until 65536) {
            for (idx in padded.indices step 16) {
                val keyChunk = padded.copyOfRange(idx, idx + 16)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyChunk, "AES"))
                pkey = cipher.doFinal(pkey)
            }
        }
        return pkey
    }

    private fun stringHashV1(email: String, aesKey: ByteArray): String {
        val emailBytes = email.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(16)
        for (i in emailBytes.indices) {
            buf[i % 16] = (buf[i % 16].toInt() xor emailBytes[i].toInt()).toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        var hash = buf
        for (r in 0 until 16384) {
            hash = cipher.doFinal(hash)
        }
        val out8 = ByteArray(8)
        System.arraycopy(hash, 0, out8, 0, 4)
        System.arraycopy(hash, 8, out8, 4, 4)
        return base64UrlEncode(out8)
    }

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val output = ByteArray(blocks * hLen)
        val blockIndexBytes = ByteArray(4)

        for (b in 1..blocks) {
            ByteBuffer.wrap(blockIndexBytes).putInt(b)
            mac.update(salt)
            val u = mac.doFinal(blockIndexBytes)
            val t = u.clone()
            var prev = u
            for (i in 2..iterations) {
                prev = mac.doFinal(prev)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor prev[k].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, output, (b - 1) * hLen, hLen)
        }
        return output.copyOfRange(0, dkLen)
    }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val alignedLen = (data.size / 16) * 16
        if (alignedLen <= 0) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data, 0, alignedLen)
    }

    private fun base64UrlDecode(str: String): ByteArray {
        if (str.isBlank()) return ByteArray(0)
        var clean = str.trim().replace('-', '+').replace('_', '/')
        while (clean.length % 4 != 0) clean += "="
        return Base64.decode(clean, Base64.DEFAULT)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    // =========================================================================
    // Çoklu Bulut Arama & Klasör Listeleme
    // =========================================================================

    private fun normalizeTurkishForSearch(text: String): String {
        return text
            .replace('İ', 'i')
            .replace('I', 'i')
            .replace('ı', 'i')
            .lowercase(Locale("tr", "TR"))
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
    }

    fun matchesFileNameSmart(fileName: String, rawQuery: String): Boolean {
        val q = rawQuery.trim()
        if (q.isEmpty()) return true
        if (fileName.contains(q, ignoreCase = true)) return true
        val normName = normalizeTurkishForSearch(fileName)
        val normQuery = normalizeTurkishForSearch(q)
        if (normName.contains(normQuery)) return true
        val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { normName.contains(it) }
    }

    suspend fun searchAcrossAllAccounts(
        context: Context,
        query: String,
        filter: CloudSearchFilter = CloudSearchFilter.ALL,
        onProgress: ((UnifiedCloudSearchProgress) -> Unit)? = null
    ): List<FileItem> = coroutineScope {
        val accounts = getAccounts(context)
        if (accounts.isEmpty()) return@coroutineScope emptyList()

        lastCloudErrorMessage = null
        val cleanQuery = query.trim()
        if (cleanQuery.isNotEmpty()) {
            addSearchHistory(context, cleanQuery)
        }

        val aggregated = ConcurrentHashMap.newKeySet<FileItem>()
        var completed = 0

        val deferredList = accounts.map { acc ->
            async(Dispatchers.IO) {
                val accountMatches = try {
                    searchSingleAccount(context, acc, cleanQuery)
                } catch (_: Exception) {
                    emptyList()
                }
                val filtered = accountMatches.filter { item -> matchesFilter(item, filter) }
                aggregated.addAll(filtered)
                synchronized(this@coroutineScope) {
                    completed++
                    val sortedPartial = aggregated.sortedWith(
                        compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() }
                    )
                    onProgress?.invoke(
                        UnifiedCloudSearchProgress(
                            completedAccounts = completed,
                            totalAccounts = accounts.size,
                            currentAccountName = "${acc.provider.displayName} (${acc.emailOrName})",
                            partialResults = sortedPartial
                        )
                    )
                }
                filtered
            }
        }

        deferredList.awaitAll()
        aggregated.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun matchesFilter(item: FileItem, filter: CloudSearchFilter): Boolean {
        return when (filter) {
            CloudSearchFilter.ALL -> true
            CloudSearchFilter.VIDEOS -> item.isVideo
            CloudSearchFilter.AUDIO -> item.isAudio
            CloudSearchFilter.IMAGES -> item.isImage
            CloudSearchFilter.DOCUMENTS -> item.isDocument
            CloudSearchFilter.ARCHIVES -> item.isArchive
        }
    }

    suspend fun listCloudFolder(
        context: Context,
        account: CloudAccount,
        folderIdOrPath: String = "root",
        forceRefresh: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val cacheKey = "${account.id}|$folderIdOrPath"
        if (!forceRefresh) {
            folderCache[cacheKey]?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached
            }
        }

        val items = try {
            when (account.provider) {
                CloudProviderType.GOOGLE_DRIVE -> listGoogleDriveFolder(context, account, folderIdOrPath)
                CloudProviderType.MEGA -> listMegaFolder(context, account, folderIdOrPath, forceRefresh)
                CloudProviderType.DROPBOX -> listDropboxFolder(account, if (folderIdOrPath == "root") "" else folderIdOrPath)
                CloudProviderType.ONEDRIVE -> listOneDriveFolder(account, folderIdOrPath)
                CloudProviderType.BOX -> listBoxFolder(account, if (folderIdOrPath == "root") "0" else folderIdOrPath)
                CloudProviderType.NEXTCLOUD -> listWebDavCloud(account, if (folderIdOrPath == "root") "/" else folderIdOrPath)
                CloudProviderType.YANDEX -> listYandexDiskFolder(account, if (folderIdOrPath == "root") "disk:/" else folderIdOrPath)
            }
        } catch (e: Exception) {
            lastCloudErrorMessage = e.localizedMessage
            emptyList()
        }

        val sorted = items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        if (sorted.isNotEmpty()) {
            folderCache[cacheKey] = sorted
            for (f in sorted) {
                if (f.isDirectory) {
                    folderNameCache["${account.id}|${f.id}"] = f.name
                }
            }
        }
        sorted
    }

    private suspend fun searchSingleAccount(context: Context, account: CloudAccount, query: String): List<FileItem> {
        return when (account.provider) {
            CloudProviderType.GOOGLE_DRIVE -> searchGoogleDrive(context, account, query)
            CloudProviderType.MEGA -> searchMegaAccount(context, account, query)
            CloudProviderType.DROPBOX -> searchDropbox(account, query)
            CloudProviderType.ONEDRIVE -> searchOneDrive(account, query)
            CloudProviderType.BOX -> searchBox(account, query)
            CloudProviderType.NEXTCLOUD -> listWebDavCloud(account, "/").filter { matchesFileNameSmart(it.name, query) }
            CloudProviderType.YANDEX -> searchYandexDisk(account, query)
        }
    }

    private suspend fun listGoogleDriveFolder(context: Context, account: CloudAccount, folderId: String): List<FileItem> {
        val escapedParent = folderId.replace("'", "\\'")
        val q = "'$escapedParent' in parents and trashed = false"
        return executeGoogleDriveQuery(context, account, q)
    }

    private suspend fun searchGoogleDrive(context: Context, account: CloudAccount, query: String): List<FileItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return executeGoogleDriveQuery(context, account, "trashed = false", pageSize = 250)
        }
        val escaped = trimmed.replace("\\", "\\\\").replace("'", "\\'")
        val directMatches = executeGoogleDriveQuery(
            context = context,
            account = account,
            rawQuery = "name contains '$escaped' and trashed = false",
            pageSize = 200
        )
        val allAccountFiles = executeGoogleDriveQuery(
            context = context,
            account = account,
            rawQuery = "trashed = false",
            pageSize = 400
        )
        val combined = LinkedHashMap<String, FileItem>()
        for (item in directMatches) combined[item.id] = item
        for (item in allAccountFiles) {
            if (matchesFileNameSmart(item.name, trimmed)) {
                combined[item.id] = item
            }
        }
        return combined.values.toList()
    }

    private suspend fun executeGoogleDriveQuery(
        context: Context,
        account: CloudAccount,
        rawQuery: String,
        pageSize: Int = 500
    ): List<FileItem> {
        lastCloudErrorMessage = null
        var token = resolveGoogleDriveToken(context, account, forceRefresh = false)
        if (token.isBlank()) {
            lastCloudErrorMessage = "${account.emailOrName} hesabı için Google Drive oturum izni bekleniyor."
            return emptyList()
        }

        var responsePair = performDriveFilesHttpGet(token, rawQuery, pageSize)
        if (responsePair.first == 401) {
            token = resolveGoogleDriveToken(context, account, forceRefresh = true)
            if (token.isNotBlank()) {
                responsePair = performDriveFilesHttpGet(token, rawQuery, pageSize)
            }
        }

        val code = responsePair.first
        val body = responsePair.second
        if (code !in 200..299 || body.isNullOrBlank()) {
            if (code == 401 || code == 403) {
                invalidateAccountAccessToken(context.applicationContext, account.id)
                needsWebOAuthFallbackForAccount = account.copy(accessToken = "")
                lastCloudErrorMessage = "Google Drive oturum izni yenilenmeli (HTTP $code)."
            } else if (code > 0) {
                lastCloudErrorMessage = "Google Drive sunucu yanıtı: HTTP $code"
            }
            return emptyList()
        }

        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return emptyList()
        val result = ArrayList<FileItem>(files.length())

        for (i in 0 until files.length()) {
            val obj = files.optJSONObject(i) ?: continue
            if (obj.optString("mimeType") == "application/vnd.google-apps.folder") {
                folderNameCache["${account.id}|${obj.optString("id")}"] = obj.optString("name")
            }
        }

        for (i in 0 until files.length()) {
            val obj = files.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val mime = obj.optString("mimeType", "")
            val isFolder = mime == "application/vnd.google-apps.folder"
            val size = obj.optLong("size", 0L)
            val modifiedTime = parseIsoTimestamp(obj.optString("modifiedTime", ""))
            val parentsArr = obj.optJSONArray("parents")
            val rawParentId = if (parentsArr != null && parentsArr.length() > 0) parentsArr.optString(0, "root") else "root"
            val parentDisplay = folderNameCache["${account.id}|$rawParentId"] ?: "Drive"
            val streamUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            val thumb = obj.optString("thumbnailLink", "")

            result.add(
                FileItem(
                    id = id,
                    name = name,
                    path = "/GoogleDrive/${account.emailOrName}/$name",
                    isDirectory = isFolder,
                    size = size,
                    lastModified = modifiedTime,
                    mimeType = mime,
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = CloudProviderType.GOOGLE_DRIVE.displayName,
                    downloadUrl = streamUrl,
                    thumbnailUrl = thumb,
                    parentId = parentDisplay
                )
            )
        }
        return result
    }

    private fun performDriveFilesHttpGet(token: String, rawQuery: String, pageSize: Int): Pair<Int, String?> {
        return try {
            val fields = "files(id,name,mimeType,size,modifiedTime,thumbnailLink,parents)"
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?q=" + URLEncoder.encode(rawQuery, "UTF-8") +
                "&fields=" + URLEncoder.encode(fields, "UTF-8") +
                "&pageSize=$pageSize&supportsAllDrives=true&includeItemsFromAllDrives=true"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.code to resp.body?.string()
            }
        } catch (e: Exception) {
            lastCloudErrorMessage = "Ağ hatası: ${e.localizedMessage}"
            -1 to null
        }
    }

    // =========================================================================
    // Dropbox / OneDrive / Box / WebDAV / Yandex Disk
    // =========================================================================

    private fun listDropboxFolder(account: CloudAccount, path: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val payload = JSONObject().apply {
            put("path", path)
            put("recursive", false)
            put("include_media_info", true)
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
        val payload = JSONObject().apply { put("query", query) }.toString()
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
                val meta = matches.optJSONObject(i)?.optJSONObject("metadata")?.optJSONObject("metadata")
                if (meta != null) entries.put(meta)
            }
            return parseDropboxEntries(account, entries)
        }
    }

    private fun parseDropboxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(entries.length())
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val tag = obj.optString(".tag")
            val isDir = tag == "folder"
            val pathDisplay = obj.optString("path_display", "")
            list.add(
                FileItem(
                    id = obj.optString("id", pathDisplay),
                    name = obj.optString("name"),
                    path = pathDisplay,
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTimestamp(obj.optString("client_modified", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = CloudProviderType.DROPBOX.displayName,
                    downloadUrl = pathDisplay
                )
            )
        }
        return list
    }

    private fun listOneDriveFolder(account: CloudAccount, folderId: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val endpoint = if (folderId == "root") {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$folderId/children"
        }
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val values = json.optJSONArray("value") ?: return emptyList()
            return parseOneDriveValues(account, values)
        }
    }

    private fun searchOneDrive(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive/root/search(q='$encoded')")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val values = json.optJSONArray("value") ?: return emptyList()
            return parseOneDriveValues(account, values)
        }
    }

    private fun parseOneDriveValues(account: CloudAccount, values: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(values.length())
        for (i in 0 until values.length()) {
            val obj = values.optJSONObject(i) ?: continue
            val isDir = obj.has("folder")
            val id = obj.optString("id")
            val dl = obj.optString("@microsoft.graph.downloadUrl", "")
            list.add(
                FileItem(
                    id = id,
                    name = obj.optString("name"),
                    path = "/OneDrive/${obj.optString("name")}",
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTimestamp(obj.optString("lastModifiedDateTime", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = CloudProviderType.ONEDRIVE.displayName,
                    downloadUrl = dl
                )
            )
        }
        return list
    }

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
            val entries = json.optJSONArray("entries") ?: return emptyList()
            return parseBoxEntries(account, entries)
        }
    }

    private fun searchBox(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
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
        val list = ArrayList<FileItem>(entries.length())
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val isDir = obj.optString("type") == "folder"
            val id = obj.optString("id")
            list.add(
                FileItem(
                    id = id,
                    name = obj.optString("name"),
                    path = "/Box/${obj.optString("name")}",
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTimestamp(obj.optString("modified_at", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = CloudProviderType.BOX.displayName,
                    downloadUrl = "https://api.box.com/2.0/files/$id/content"
                )
            )
        }
        return list
    }

    private fun listWebDavCloud(account: CloudAccount, relativePath: String): List<FileItem> {
        val baseUrl = account.serverUrl.trimEnd('/')
        if (baseUrl.isBlank()) return emptyList()
        val rel = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val fullUrl = "$baseUrl$rel"
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
            val out = ArrayList<FileItem>()
            val responses = xml.split("<d:response>", "<D:response>").drop(1)
            for (block in responses) {
                val href = extractXmlTag(block, "href").trim()
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isEmpty()) continue
                val isDir = block.contains("collection", ignoreCase = true)
                val size = extractXmlTag(block, "getcontentlength").toLongOrNull() ?: 0L
                out.add(
                    FileItem(
                        id = href,
                        name = Uri.decode(name),
                        path = href,
                        isDirectory = isDir,
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = account.provider.displayName,
                        downloadUrl = "$baseUrl$href"
                    )
                )
            }
            return out
        }
    }

    private fun listYandexDiskFolder(account: CloudAccount, path: String): List<FileItem> {
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

    private fun searchYandexDisk(account: CloudAccount, query: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources/files?limit=500")
            .header("Authorization", "OAuth ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val items = json.optJSONArray("items") ?: return emptyList()
            return parseYandexItems(account, items).filter { matchesFileNameSmart(it.name, query) }
        }
    }

    private fun parseYandexItems(account: CloudAccount, items: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(items.length())
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
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
                    providerName = CloudProviderType.YANDEX.displayName,
                    downloadUrl = obj.optString("file", p)
                )
            )
        }
        return list
    }

    private fun extractXmlTag(xml: String, tagLocalName: String): String {
        val regex = Regex("<(?:\\w+:)?$tagLocalName[^>]*>(.*?)</(?:\\w+:)?$tagLocalName>", RegexOption.IGNORE_CASE)
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
