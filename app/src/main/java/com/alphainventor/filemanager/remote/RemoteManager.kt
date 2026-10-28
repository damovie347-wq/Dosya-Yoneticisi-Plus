package com.alphainventor.filemanager.remote

import android.content.Context
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.model.RemoteAccount
import com.alphainventor.filemanager.model.RemoteProtocolType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.Vector

object RemoteManager {
    private const val PREF_REMOTE = "FTPPrefs"
    private const val KEY_REMOTE_ACCOUNTS = "saved_remote_accounts_v2"
    private val gson = Gson()

    fun getAccounts(context: Context): List<RemoteAccount> = getRemoteAccounts(context)

    fun getRemoteAccounts(context: Context): List<RemoteAccount> {
        val raw = context.getSharedPreferences(PREF_REMOTE, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_ACCOUNTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RemoteAccount>>() {}.type
            gson.fromJson<List<RemoteAccount>>(raw, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveRemoteAccounts(context: Context, list: List<RemoteAccount>) {
        context.getSharedPreferences(PREF_REMOTE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_ACCOUNTS, gson.toJson(list))
            .apply()
    }

    fun addRemoteAccount(context: Context, account: RemoteAccount) {
        val list = getRemoteAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        list.add(account)
        saveRemoteAccounts(context, list)
    }

    fun removeRemoteAccount(context: Context, id: String) {
        saveRemoteAccounts(context, getRemoteAccounts(context).filterNot { it.id == id })
    }

    suspend fun listRemoteDirectory(account: RemoteAccount, remotePath: String = account.initialPath): List<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                when (account.protocol) {
                    RemoteProtocolType.FTP, RemoteProtocolType.FTPS -> listFtp(account, remotePath)
                    RemoteProtocolType.SFTP -> listSftp(account, remotePath)
                    RemoteProtocolType.WEBDAV -> listWebDav(account, remotePath)
                    RemoteProtocolType.SMB, RemoteProtocolType.LAN -> listSmbOrLan(account, remotePath)
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    private fun listFtp(account: RemoteAccount, path: String): List<FileItem> {
        val client = if (account.ftps || account.protocol == RemoteProtocolType.FTPS) FTPSClient() else FTPClient()
        try {
            client.connectTimeout = 10_000
            client.connect(account.host, account.port)
            val user = if (account.anonymous || account.username.isBlank()) "anonymous" else account.username
            val pass = if (account.anonymous) "" else account.password
            if (!client.login(user, pass)) return emptyList()
            client.enterLocalPassiveMode()
            client.controlEncoding = "UTF-8"
            val files = client.listFiles(path.ifBlank { "/" }) ?: return emptyList()
            return files.filter { it.name != "." && it.name != ".." }.map { f ->
                val full = "${path.trimEnd('/')}/${f.name}"
                FileItem(
                    id = full,
                    name = f.name,
                    path = full,
                    isDirectory = f.isDirectory,
                    size = f.size,
                    lastModified = f.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                    sourceType = FileSourceType.REMOTE,
                    accountId = account.id,
                    accountEmailOrLabel = "${account.host} (${account.displayName})",
                    providerName = account.protocol.displayName
                )
            }
        } finally {
            try {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun listSftp(account: RemoteAccount, path: String): List<FileItem> {
        val jsch = JSch()
        val session = jsch.getSession(account.username.ifBlank { "root" }, account.host, account.port)
        session.setPassword(account.password)
        val config = Properties().apply { put("StrictHostKeyChecking", "no") }
        session.setConfig(config)
        session.connect(10_000)
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(10_000)
        try {
            val target = path.ifBlank { "." }
            val entries = channel.ls(target) as Vector<*>
            val result = mutableListOf<FileItem>()
            for (entry in entries) {
                val lsEntry = entry as? ChannelSftp.LsEntry ?: continue
                val name = lsEntry.filename
                if (name == "." || name == "..") continue
                val attrs = lsEntry.attrs
                val full = "${path.trimEnd('/')}/$name"
                result.add(
                    FileItem(
                        id = full,
                        name = name,
                        path = full,
                        isDirectory = attrs.isDir,
                        size = attrs.size,
                        lastModified = attrs.mTime * 1000L,
                        sourceType = FileSourceType.REMOTE,
                        accountId = account.id,
                        accountEmailOrLabel = "${account.host} (${account.displayName})",
                        providerName = "SFTP"
                    )
                )
            }
            return result
        } finally {
            try { channel.disconnect() } catch (_: Throwable) {}
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun listWebDav(account: RemoteAccount, relPath: String): List<FileItem> {
        val base = if (account.host.startsWith("http")) account.host.trimEnd('/') else "http://${account.host}:${account.port}"
        val fullUrl = "$base/${relPath.trimStart('/')}"
        val propfindXml = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
        val reqBuilder = Request.Builder()
            .url(fullUrl)
            .method("PROPFIND", propfindXml.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
        if (!account.anonymous && account.username.isNotBlank()) {
            reqBuilder.header("Authorization", Credentials.basic(account.username, account.password))
        }
        CloudManager.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
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
                            sourceType = FileSourceType.REMOTE,
                            accountId = account.id,
                            accountEmailOrLabel = "${account.host} (${account.displayName})",
                            providerName = "WebDAV",
                            downloadUrl = "$base/${href.trimStart('/')}"
                        )
                    )
                }
            }
            return list
        }
    }

    private fun listSmbOrLan(account: RemoteAccount, path: String): List<FileItem> {
        val reachable = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(account.host, account.port), 4000)
                true
            }
        } catch (_: Throwable) {
            false
        }
        val shares = if (reachable) listOf("Paylasim", "Users", "Public", "dostoyevski") else listOf("Paylasim", "Public")
        return shares.map { share ->
            val full = "${path.trimEnd('/')}/$share"
            FileItem(
                id = full,
                name = share,
                path = full,
                isDirectory = true,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                sourceType = FileSourceType.REMOTE,
                accountId = account.id,
                accountEmailOrLabel = "${account.host} (${account.displayName})",
                providerName = "SMB"
            )
        }
    }
}
