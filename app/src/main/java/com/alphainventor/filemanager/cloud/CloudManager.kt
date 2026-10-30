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
import com.google.android.gms.auth.GoogleAuthException
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
 *    - Orijinal APK'daki (Lcom/alphainventor/filemanager/file/r; & Lax/q7/a;) gibi cihazdaki "com.google"
 *      hesaplarını AccountPicker ile seçer ve GoogleAuthUtil.getToken("oauth2: https://www.googleapis.com/auth/drive")
 *      üzerinden doğrudan OAuth2 erişim belirteci alır.
 *    - İlk kullanımda UserRecoverableAuthException dönerse yerel Google Play Hizmetleri izin penceresini açar.
 *    - HTTP 401 durumunda GoogleAuthUtil.clearToken ile eski belirteci temizleyip yenisini alır.
 *    - Asla tarayıcıya veya manuel e-posta/şifre ekranına düşmez.
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

    // Orijinal APK'daki (Lax/q7/a;->a()) scope formatı ve alternatifleri
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

    suspend fun tryGetWebLoginMergeSessionUrl(
        context: Context,
        activity: Activity?,
        email: String,
        continueUrl: String
    ): String? = withContext(Dispatchers.IO) {
        pendingWebLoginRecoveryIntent = null
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
     * Orijinal APK'daki (Lcom/alphainventor/filemanager/file/r; & Lax/q7/a;) Google Drive OAuth2 akışı:
     * 1) Mevcut geçerli access_token varsa doğrudan döndürür.
     * 2) Cihazdaki "com.google" hesabından GoogleAuthUtil.getToken("oauth2: https://www.googleapis.com/auth/drive")
     *    ile doğrudan belirteç alır.
     * 3) İlk kez erişiliyorsa UserRecoverableAuthException (veya AccountManager KEY_INTENT) yakalanır
     *    ve pendingRecoveryIntent içine konur; Activity yerel Google izin penceresini açar.
     * 4) Asla tarayıcıya veya manuel e-posta/şifre ekranına düşmez.
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

        if (forceRefresh && latestAcc.accessToken.isNotBlank()) {
            try {
                GoogleAuthUtil.clearToken(context.applicationContext, latestAcc.accessToken)
            } catch (_: Throwable) {
            }
            try {
                AccountManager.get(context.applicationContext).invalidateAuthToken("com.google", latestAcc.accessToken)
            } catch (_: Throwable) {
            }
            invalidateAccountAccessToken(context.applicationContext, latestAcc.id)
        }

        if (latestAcc.refreshToken.isNotBlank()) {
            val refreshed = refreshGoogleAccessToken(context, latestAcc)
            if (refreshed.isNotBlank()) {
                return@withContext refreshed
            }
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

        // 1. Öncelik: Orijinal APK'daki GoogleAuthUtil.getToken çağrısı
        val scopesToTry = listOf(
            DRIVE_OAUTH_SCOPE_ORIG,
            DRIVE_OAUTH_SCOPE,
            "$DRIVE_OAUTH_SCOPE https://www.googleapis.com/auth/userinfo.email"
        )
        for (scopeStr in scopesToTry) {
            try {
                val token = GoogleAuthUtil.getToken(appCtx, googleAccount, scopeStr)
                if (!token.isNullOrBlank()) {
                    val updated = latestAcc.copy(accessToken = token)
                    addOrUpdateAccount(appCtx, updated)
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                val intent = recoverable.intent
                if (intent != null) {
                    pendingRecoveryIntent = intent
                    return@withContext ""
                }
            } catch (_: GoogleAuthException) {
            } catch (_: Throwable) {
            }
        }

        // 2. Yedek: Android AccountManager.getAuthToken (yerel izin ekranı KEY_INTENT dahil)
        for (authTokenType in scopesToTry) {
            try {
                val future = am.getAuthToken(googleAccount, authTokenType, null, false, null, null)
                val bundle = future.getResult(12, TimeUnit.SECONDS)
                val token = bundle?.getString(AccountManager.KEY_AUTHTOKEN).orEmpty()
                if (token.isNotBlank()) {
                    val updated = latestAcc.copy(accessToken = token)
                    addOrUpdateAccount(appCtx, updated)
                    return@withContext token
                }
                @Suppress("DEPRECATION")
                val keyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle?.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
                } else {
                    bundle?.getParcelable(AccountManager.KEY_INTENT) as? Intent
                }
                if (keyIntent != null) {
                    pendingRecoveryIntent = keyIntent
                    return@withContext ""
                }
            } catch (_: Throwable) {
            }
        }

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
        lastMegaErrorCode = 0
        lastCloudErrorMessage = null

        try {
            val cleanEmail = email.trim().lowercase(Locale.US)
            if (cleanEmail.isEmpty() || password.isEmpty()) {
                lastCloudErrorMessage = "MEGA e-posta ve şifresi boş olamaz."
                return@withContext null
            }

            // 1. us0: Hesap sürümü (v1 / v2) ve salt (s) sorgula
            val us0Payload = JSONArray().put(
                JSONObject().apply {
                    put("a", "us0")
                    put("user", cleanEmail)
                }
            ).toString()

            val us0RespStr = postMegaApi(us0Payload, null) ?: run {
                lastCloudErrorMessage = "MEGA sunucusuna bağlanılamadı. İnternet bağlantınızı kontrol edin."
                return@withContext null
            }

            val us0Arr = JSONArray(us0RespStr)
            val us0First = us0Arr.opt(0)
            if (us0First is Number) {
                val code = us0First.toInt()
                lastMegaErrorCode = code
                lastCloudErrorMessage = "MEGA kullanıcı sorgusu hatası (Kod: $code)."
                return@withContext null
            }
            val us0Obj = us0Arr.optJSONObject(0) ?: return@withContext null
            val version = us0Obj.optInt("v", 1)

            val pwKey: ByteArray
            val userHashB64: String

            if (version >= 2 && us0Obj.has("s")) {
                val saltBytes = megaBase64Decode(us0Obj.optString("s"))
                val derived32 = pbkdf2HmacSha512(password.toByteArray(Charsets.UTF_8), saltBytes, 100_000, 32)
                pwKey = derived32.copyOfRange(0, 16)
                userHashB64 = megaBase64Encode(derived32.copyOfRange(16, 32))
            } else {
                pwKey = prepareMegaV1PasswordKey(password)
                userHashB64 = computeMegaV1StringHash(cleanEmail, pwKey)
            }

            // 2. us: Oturum aç ve master key (k) + RSA csid al
            val usPayload = JSONArray().put(
                JSONObject().apply {
                    put("a", "us")
                    put("user", cleanEmail)
                    put("uh", userHashB64)
                    if (!mfaPin.isNullOrBlank()) {
                        put("mfa", mfaPin.trim())
                    }
                }
            ).toString()

            val usRespStr = postMegaApi(usPayload, null) ?: run {
                lastCloudErrorMessage = "MEGA oturum açma yanıtı alınamadı."
                return@withContext null
            }
            val usArr = JSONArray(usRespStr)
            val usFirst = usArr.opt(0)
            if (usFirst is Number) {
                val errCode = usFirst.toInt()
                lastMegaErrorCode = errCode
                lastCloudErrorMessage = when (errCode) {
                    -9 -> "MEGA e-posta veya şifre hatalı."
                    -16 -> "MEGA hesabınız engellenmiş veya e-posta onayı bekliyor."
                    -26 -> "MEGA hesabınızda 2FA (İki Faktörlü Doğrulama) açık. Lütfen 6 haneli doğrulama kodunu girin."
                    else -> "MEGA giriş hatası (Kod: $errCode)."
                }
                return@withContext null
            }

            val usObj = usArr.optJSONObject(0) ?: return@withContext null
            val encMasterKey = megaBase64Decode(usObj.optString("k"))
            val masterKey = aesEcbDecrypt(pwKey, encMasterKey).copyOfRange(0, 16)

            val sidCandidates = if (usObj.has("csid") && usObj.has("privk")) {
                val encPrivk = megaBase64Decode(usObj.optString("privk"))
                val privkDec = aesEcbDecrypt(masterKey, encPrivk)
                val csidEnc = megaBase64Decode(usObj.optString("csid"))
                decryptMegaRsaSessionIdCandidates(privkDec, csidEnc)
            } else {
                emptyList()
            }
            if (sidCandidates.isEmpty()) {
                lastCloudErrorMessage = "MEGA oturum anahtarı (SID) çözülemedi."
                return@withContext null
            }

            // Aday SID'leri "ug" çağrısıyla doğrula (ilk baytı 0x00 olan SID'ler dahil %100 uyumluluk)
            var verifiedSid: String? = null
            for (candidate in sidCandidates) {
                val ugResp = postMegaApi("""[{"a":"ug"}]""", candidate) ?: continue
                val trimUg = ugResp.trim()
                if (trimUg.startsWith("[{")) {
                    verifiedSid = candidate
                    break
                }
            }
            val sid = verifiedSid ?: sidCandidates.first()

            // 3. Kota bilgisini (uq) sorgula
            var usedBytes = 0L
            var totalBytes = 20L * 1024 * 1024 * 1024
            try {
                val uqPayload = JSONArray().put(JSONObject().apply {
                    put("a", "uq")
                    put("strg", 1)
                }).toString()
                val uqResp = postMegaApi(uqPayload, sid)
                if (!uqResp.isNullOrBlank() && uqResp.trim().startsWith("[{")) {
                    val uqObj = JSONArray(uqResp).optJSONObject(0)
                    if (uqObj != null) {
                        usedBytes = uqObj.optLong("cstrg", 0L)
                        val maxStrg = uqObj.optLong("mstrg", 0L)
                        if (maxStrg > 0L) totalBytes = maxStrg
                    }
                }
            } catch (_: Exception) {
            }

            val saved = CloudAccount(
                id = existingAccount?.id ?: UUID.randomUUID().toString(),
                provider = CloudProviderType.MEGA,
                emailOrName = cleanEmail,
                displayName = cleanEmail,
                accessToken = sid,
                refreshToken = megaBase64Encode(masterKey),
                usedQuotaBytes = usedBytes,
                totalQuotaBytes = totalBytes
            )
            addOrUpdateAccount(context.applicationContext, saved)
            clearAccountCache(saved.id)
            lastCloudErrorMessage = null
            saved
        } catch (e: Exception) {
            lastCloudErrorMessage = "MEGA bağlantı hatası: ${e.localizedMessage}"
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
        val cleanEmail = email.trim().ifEmpty { "MEGA Hesabı" }
        val acc = CloudAccount(
            id = existingAccount?.id ?: UUID.randomUUID().toString(),
            provider = CloudProviderType.MEGA,
            emailOrName = cleanEmail,
            displayName = cleanEmail,
            accessToken = "MEGA_WEB_SYNC",
            usedQuotaBytes = usedBytes.coerceAtLeast(0L),
            totalQuotaBytes = if (totalBytes > 0L) totalBytes else (20L * 1024 * 1024 * 1024)
        )
        addOrUpdateAccount(context.applicationContext, acc)
        context.getSharedPreferences(PREF_CLOUD_ACCOUNTS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEGA_WEB_NODES_PREFIX + acc.id, nodesJsonArrayString)
            .apply()
        clearAccountCache(acc.id)
        return acc
    }

    private fun listMegaFolder(context: Context, account: CloudAccount, folderId: String): List<FileItem> {
        val allNodes = fetchAllMegaNodesForAccount(context, account)
        if (allNodes.isEmpty()) return emptyList()
        val targetParent = if (folderId.isBlank() || folderId == "root") "root" else folderId
        return allNodes.filter { it.parentId == targetParent }
    }

    private fun searchMega(context: Context, account: CloudAccount, query: String): List<FileItem> {
        val allNodes = fetchAllMegaNodesForAccount(context, account)
        if (query.isBlank()) return allNodes.filter { it.parentId == "root" }
        return allNodes.filter { matchesSmartSearchQuery(it.name, query) }
    }

    private fun fetchAllMegaNodesForAccount(context: Context, account: CloudAccount): List<FileItem> {
        megaAllItemsCache[account.id]?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }

        val sid = account.accessToken
        val masterKeyB64 = account.refreshToken
        if (sid.isBlank() || sid == "MEGA_WEB_SYNC" || masterKeyB64.isBlank()) {
            return emptyList()
        }

        return try {
            val masterKey = megaBase64Decode(masterKeyB64).copyOfRange(0, 16)
            val payload = JSONArray().put(
                JSONObject().apply {
                    put("a", "f")
                    put("c", 1)
                }
            ).toString()
            val respStr = postMegaApi(payload, sid) ?: return emptyList()
            val arr = JSONArray(respStr)
            val rootObj = arr.optJSONObject(0) ?: return emptyList()
            val fArr = rootObj.optJSONArray("f") ?: return emptyList()

            var cloudDriveRootHandle = ""
            for (i in 0 until fArr.length()) {
                val n = fArr.getJSONObject(i)
                if (n.optInt("t", -1) == 2) {
                    cloudDriveRootHandle = n.optString("h", "")
                    break
                }
            }

            val items = ArrayList<FileItem>(fArr.length())
            for (i in 0 until fArr.length()) {
                val n = fArr.getJSONObject(i)
                val t = n.optInt("t", -1)
                if (t != 0 && t != 1) continue
                val handle = n.optString("h", "")
                val rawParent = n.optString("p", "")
                val parentId = if (rawParent == cloudDriveRootHandle || rawParent.isBlank()) "root" else rawParent
                val kStr = n.optString("k", "")
                val aStr = n.optString("a", "")
                val nameAndKey = decryptMegaNodeNameAndKey(masterKey, kStr, aStr, isFolder = (t == 1)) ?: continue
                val name = nameAndKey.first
                val fullNodeKeyB64 = nameAndKey.second
                if (name.isBlank()) continue
                if (fullNodeKeyB64.isNotBlank()) {
                    megaNodeKeysCache["${account.id}:$handle"] = fullNodeKeyB64
                }
                val size = n.optLong("s", 0L)
                val ts = n.optLong("ts", System.currentTimeMillis() / 1000L) * 1000L

                items.add(
                    FileItem(
                        id = handle,
                        name = name,
                        path = handle,
                        isDirectory = (t == 1),
                        size = size,
                        lastModified = ts,
                        sourceType = FileSourceType.CLOUD,
                        accountId = account.id,
                        accountEmailOrLabel = account.emailOrName,
                        providerName = CloudProviderType.MEGA.displayName,
                        downloadUrl = if (t == 0) "mega://$handle/$fullNodeKeyB64" else "",
                        parentId = parentId
                    )
                )
            }
            megaAllItemsCache[account.id] = items
            items
        } catch (e: Exception) {
            lastCloudErrorMessage = "MEGA klasör listesi alınamadı: ${e.localizedMessage}"
            emptyList()
        }
    }

    /**
     * MEGA üzerindeki bir dosyayı ("a":"g" ile geçici indirme URL'si alıp AES-128-CTR ile şifresini çözerek)
     * yerel önbelleğe indirir; böylece fotoğraf, video ve belgeler doğrudan açılır.
     */
    suspend fun downloadMegaFileToCache(
        context: Context,
        account: CloudAccount,
        item: FileItem
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "mega_open_cache").apply { if (!exists()) mkdirs() }
            val safeName = item.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "mega_file" }
            val targetFile = File(cacheDir, "${item.id}_$safeName")
            if (targetFile.exists() && targetFile.length() > 0L && (item.size <= 0L || targetFile.length() == item.size)) {
                return@withContext targetFile
            }

            if (megaAllItemsCache[account.id].isNullOrEmpty()) {
                fetchAllMegaNodesForAccount(context, account)
            }

            val fullKeyB64 = megaNodeKeysCache["${account.id}:${item.id}"]
                ?: item.downloadUrl.removePrefix("mega://${item.id}/").takeIf { it.isNotBlank() && !it.startsWith("http") }
                ?: return@withContext null

            val decNodeKey = megaBase64Decode(fullKeyB64)
            if (decNodeKey.size < 24) return@withContext null

            val aesKey = ByteArray(16)
            for (i in 0 until 16) {
                aesKey[i] = (decNodeKey[i].toInt() xor decNodeKey[i + 16].toInt()).toByte()
            }
            val iv = ByteArray(16)
            System.arraycopy(decNodeKey, 16, iv, 0, 8)

            val gPayload = JSONArray().put(
                JSONObject().apply {
                    put("a", "g")
                    put("g", 1)
                    put("n", item.id)
                }
            ).toString()
            val gRespStr = postMegaApi(gPayload, account.accessToken) ?: return@withContext null
            if (!gRespStr.trim().startsWith("[{")) return@withContext null
            val gObj = JSONArray(gRespStr.trim()).optJSONObject(0) ?: return@withContext null
            val dlUrl = gObj.optString("g", "")
            if (dlUrl.isBlank()) return@withContext null

            val req = Request.Builder().url(dlUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStream = resp.body?.byteStream() ?: return@withContext null
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                CipherInputStream(bodyStream, cipher).use { decIn ->
                    FileOutputStream(targetFile).use { fos ->
                        decIn.copyTo(fos, bufferSize = 32 * 1024)
                    }
                }
            }
            if (targetFile.exists() && targetFile.length() > 0L) targetFile else null
        } catch (_: Exception) {
            null
        }
    }

    private fun postMegaApi(jsonPayload: String, sid: String?): String? {
        for (attempt in 0 until 4) {
            try {
                val seq = (System.currentTimeMillis() and 0xFFFFF) + attempt
                val url = if (!sid.isNullOrBlank()) {
                    "https://g.api.mega.co.nz/cs?id=$seq&sid=$sid"
                } else {
                    "https://g.api.mega.co.nz/cs?id=$seq"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0")
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim()
                        if (!body.isNullOrBlank()) {
                            if (body == "-3" || body == "[-3]") {
                                Thread.sleep(300L * (attempt + 1))
                            } else {
                                return body
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    Thread.sleep(250L * (attempt + 1))
                } catch (_: InterruptedException) {
                }
            }
        }
        return null
    }

    private fun decryptMegaNodeNameAndKey(
        masterKey: ByteArray,
        kStr: String,
        aStr: String,
        isFolder: Boolean
    ): Pair<String, String>? {
        if (kStr.isBlank() || aStr.isBlank()) return null
        return try {
            var decNodeKey: ByteArray? = null
            for (seg in kStr.split('/')) {
                val keyPartB64 = seg.substringAfter(':', "")
                if (keyPartB64.isBlank()) continue
                val encNodeKey = megaBase64Decode(keyPartB64)
                if (encNodeKey.size == 16 || encNodeKey.size == 32) {
                    decNodeKey = aesEcbDecrypt(masterKey, encNodeKey)
                    break
                }
            }
            val nodeKey = decNodeKey ?: return null
            val attrKey = ByteArray(16)
            if (!isFolder && nodeKey.size >= 32) {
                for (i in 0 until 16) {
                    attrKey[i] = (nodeKey[i].toInt() xor nodeKey[i + 16].toInt()).toByte()
                }
            } else {
                System.arraycopy(nodeKey, 0, attrKey, 0, 16)
            }
            val encAttr = megaBase64Decode(aStr)
            val alignedAttrLen = (encAttr.size / 16) * 16
            if (alignedAttrLen == 0) return null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(attrKey, "AES"), IvParameterSpec(ByteArray(16)))
            val decAttr = cipher.doFinal(encAttr.copyOfRange(0, alignedAttrLen))
            val rawStr = String(decAttr, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            if (rawStr.startsWith("MEGA{")) {
                val startIdx = rawStr.indexOf('{')
                val endIdx = rawStr.lastIndexOf('}')
                if (startIdx >= 0 && endIdx > startIdx) {
                    val jsonPart = rawStr.substring(startIdx, endIdx + 1)
                    val name = JSONObject(jsonPart).optString("n", "")
                    if (name.isNotBlank()) {
                        return Pair(name, megaBase64Encode(nodeKey))
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * MEGA RSA (p, q, d, u) MPI özel anahtarı ile csid şifresini çözer.
     * csidEnc başındaki 2-byte MPI bit-uzunluğu başlığını her zaman atlar ve hem standart
     * hem de başında 0x00 bulunan 43-byte oturum kimliği (SID) adaylarını üretir.
     */
    private fun decryptMegaRsaSessionIdCandidates(privkDec: ByteArray, csidEnc: ByteArray): List<String> {
        return try {
            var pos = 0
            fun readMpi(buf: ByteArray): BigInteger {
                if (pos + 2 > buf.size) return BigInteger.ZERO
                val bitLen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                val byteLen = (bitLen + 7) / 8
                pos += 2
                val end = (pos + byteLen).coerceAtMost(buf.size)
                if (end <= pos) return BigInteger.ZERO
                val slice = buf.copyOfRange(pos, end)
                pos = end
                return BigInteger(1, slice)
            }
            val p = readMpi(privkDec)
            val q = readMpi(privkDec)
            val d = readMpi(privkDec)
            val n = p.multiply(q)
            if (n == BigInteger.ZERO || csidEnc.size <= 2) return emptyList()

            val bitLen = ((csidEnc[0].toInt() and 0xFF) shl 8) or (csidEnc[1].toInt() and 0xFF)
            val byteLen = (bitLen + 7) / 8
            val actualEnd = (2 + byteLen).coerceAtMost(csidEnc.size)
            val csidBig = if (actualEnd > 2) {
                BigInteger(1, csidEnc.copyOfRange(2, actualEnd))
            } else {
                BigInteger(1, csidEnc.copyOfRange(2, csidEnc.size))
            }

            val m = csidBig.modPow(d, n)
            var rawBytes = m.toByteArray()
            val unsignedLen = (m.bitLength() + 7) / 8
            if (rawBytes.size > unsignedLen && rawBytes.isNotEmpty() && rawBytes[0].toInt() == 0) {
                rawBytes = rawBytes.copyOfRange(1, rawBytes.size)
            }

            val candidates = LinkedHashSet<String>()
            if (rawBytes.size >= 43) {
                candidates.add(megaBase64Encode(rawBytes.copyOfRange(0, 43)))
            }
            val zeroPrefixed = ByteArray(rawBytes.size + 1)
            System.arraycopy(rawBytes, 0, zeroPrefixed, 1, rawBytes.size)
            if (zeroPrefixed.size >= 43) {
                candidates.add(megaBase64Encode(zeroPrefixed.copyOfRange(0, 43)))
            }
            if (rawBytes.size >= 44) {
                candidates.add(megaBase64Encode(rawBytes.copyOfRange(1, 44)))
            }
            if (rawBytes.size >= 45) {
                candidates.add(megaBase64Encode(rawBytes.copyOfRange(2, 45)))
            }
            candidates.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun prepareMegaV1PasswordKey(password: String): ByteArray {
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        val paddedLen = ((pwdBytes.size + 15) / 16) * 16
        val padded = pwdBytes.copyOf(paddedLen.coerceAtLeast(16))
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

    private fun computeMegaV1StringHash(email: String, pwKey: ByteArray): String {
        val emailBytes = email.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(16)
        for (i in emailBytes.indices) {
            buf[i % 16] = (buf[i % 16].toInt() xor emailBytes[i].toInt()).toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pwKey, "AES"))
        var hash = buf
        for (r in 0 until 16384) {
            hash = cipher.doFinal(hash)
        }
        val out8 = ByteArray(8)
        System.arraycopy(hash, 0, out8, 0, 4)
        System.arraycopy(hash, 8, out8, 4, 4)
        return megaBase64Encode(out8)
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
        if (alignedLen == 0) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"))
        return cipher.doFinal(data.copyOfRange(0, alignedLen))
    }

    private fun megaBase64Decode(str: String): ByteArray {
        var clean = str.trim().replace('-', '+').replace('_', '/')
        while (clean.length % 4 != 0) clean += "="
        return Base64.decode(clean, Base64.DEFAULT)
    }

    private fun megaBase64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // =========================================================================
    // Çoklu Bulut Arama ve Klasör Listeleme
    // =========================================================================

    private fun normalizeForSmartSearch(text: String): String {
        return text
            .lowercase(Locale("tr", "TR"))
            .lowercase(Locale.US)
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
    }

    fun matchesSmartSearchQuery(fileName: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (fileName.contains(q, ignoreCase = true)) return true
        val normName = normalizeForSmartSearch(fileName)
        val normQuery = normalizeForSmartSearch(q)
        if (normName.contains(normQuery)) return true
        val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { token -> normName.contains(token) }
    }

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
                CloudProviderType.MEGA -> listMegaFolder(context, account, folderIdOrPath)
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
            CloudProviderType.MEGA -> searchMega(context, account, query)
            CloudProviderType.DROPBOX -> searchDropbox(account, query)
            CloudProviderType.ONEDRIVE -> searchOneDrive(account, query)
            CloudProviderType.BOX -> searchBox(account, query)
            CloudProviderType.NEXTCLOUD -> listWebDavFolder(account, "/").filter {
                matchesSmartSearchQuery(it.name, query)
            }
            CloudProviderType.YANDEX -> searchYandex(account, query)
        }
    }

    private suspend fun listGoogleDriveFolder(context: Context, account: CloudAccount, folderId: String): List<FileItem> {
        val escapedParent = folderId.replace("'", "\\'")
        val q = "'$escapedParent' in parents and trashed = false"
        return executeGoogleDriveQuery(context, account, q, folderId)
    }

    private suspend fun searchGoogleDrive(context: Context, account: CloudAccount, query: String): List<FileItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return executeGoogleDriveQuery(context, account, "trashed = false", "root")
        }
        val escaped = trimmed.replace("'", "\\'")
        val directMatches = executeGoogleDriveQuery(
            context,
            account,
            "name contains '$escaped' and trashed = false",
            "root"
        )
        val allAccountFiles = executeGoogleDriveQuery(
            context,
            account,
            "trashed = false",
            "root"
        )
        val combined = LinkedHashMap<String, FileItem>()
        for (item in directMatches) {
            combined[item.id] = item
        }
        for (item in allAccountFiles) {
            if (matchesSmartSearchQuery(item.name, trimmed)) {
                combined[item.id] = item
            }
        }
        return combined.values.toList()
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
        if (responsePair.first == 401 || responsePair.first == 403) {
            token = resolveGoogleDriveToken(context, account, forceRefresh = true)
            if (token.isNotBlank()) {
                responsePair = performDriveHttpQuery(token, q)
            }
        }

        val code = responsePair.first
        val body = responsePair.second
        if (code !in 200..299 || body.isNullOrBlank()) {
            if (code == 401 || code == 403) {
                invalidateAccountAccessToken(context.applicationContext, account.id)
                lastCloudErrorMessage = "Google Drive erişim izni yenilenmeli (HTTP $code)."
            } else if (code > 0) {
                lastCloudErrorMessage = "Google Drive bağlantı hatası (HTTP $code)."
            }
            return emptyList()
        }

        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return emptyList()
        val result = ArrayList<FileItem>(files.length())

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
            "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            return Pair(resp.code, resp.body?.string())
        }
    }

    private fun listDropboxFolder(account: CloudAccount, path: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val payload = JSONObject().apply {
            put("path", path)
            put("recursive", false)
            put("include_media_info", true)
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/list_folder")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
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
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/search_v2")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val matches = json.optJSONArray("matches") ?: return emptyList()
            val entries = JSONArray()
            for (i in 0 until matches.length()) {
                val meta = matches.getJSONObject(i).optJSONObject("metadata")?.optJSONObject("metadata")
                if (meta != null) entries.put(meta)
            }
            return parseDropboxEntries(account, entries)
        }
    }

    private fun parseDropboxEntries(account: CloudAccount, entries: JSONArray): List<FileItem> {
        val list = ArrayList<FileItem>(entries.length())
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val tag = obj.optString(".tag")
            val isDir = tag == "folder"
            val pathDisplay = obj.optString("path_display", obj.optString("name"))
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
                    providerName = account.provider.displayName,
                    downloadUrl = "https://content.dropboxapi.com/2/files/download",
                    parentId = pathDisplay.substringBeforeLast('/', "Dropbox").ifEmpty { "Dropbox" }
                )
            )
        }
        return list
    }

    private fun listOneDriveFolder(account: CloudAccount, folderId: String): List<FileItem> {
        if (account.accessToken.isBlank()) return emptyList()
        val endpoint = if (folderId == "root" || folderId.isBlank()) {
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
        if (query.isBlank()) return listOneDriveFolder(account, "root")
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
            val obj = values.getJSONObject(i)
            val isDir = obj.has("folder")
            val id = obj.optString("id")
            val dl = obj.optString("@microsoft.graph.downloadUrl", "")
            list.add(
                FileItem(
                    id = id,
                    name = obj.optString("name"),
                    path = id,
                    isDirectory = isDir,
                    size = obj.optLong("size", 0L),
                    lastModified = parseIsoTimestamp(obj.optString("lastModifiedDateTime", "")),
                    sourceType = FileSourceType.CLOUD,
                    accountId = account.id,
                    accountEmailOrLabel = account.emailOrName,
                    providerName = account.provider.displayName,
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
        if (query.isBlank()) return listBoxFolder(account, "0")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://api.box.com/2.0/search?query=$encoded")
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
            val obj = entries.getJSONObject(i)
            val isDir = obj.optString("type") == "folder"
            val id = obj.optString("id")
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
                    downloadUrl = "https://api.box.com/2.0/files/$id/content"
                )
            )
        }
        return list
    }

    private fun listWebDavFolder(account: CloudAccount, relativePath: String): List<FileItem> {
        val baseUrl = account.serverUrl.trimEnd('/')
        if (baseUrl.isBlank()) return emptyList()
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
            .url("https://cloud-api.yandex.net/v1/disk/resources/files?limit=500")
            .header("Authorization", "OAuth ${account.accessToken}")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body?.string() ?: return emptyList())
            val items = json.optJSONArray("items") ?: return emptyList()
            return parseYandexItems(account, items).filter {
                matchesSmartSearchQuery(it.name, query)
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
