package com.alphainventor.filemanager.remote

import android.content.Context
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.model.RemoteAccount
import com.alphainventor.filemanager.model.RemoteProtocolType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.util.Properties
import java.util.Vector

object RemoteManager {
    private const val PREF_REMOTE = "FTPPrefs"
    private const val KEY_REMOTE_ACCOUNTS = "saved_remote_accounts_v2"
    private val gson = Gson()

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
                    RemoteProtocolType.FTP -> listFtp(account, remotePath)
                    RemoteProtocolType.SFTP -> listSftp(account, remotePath)
                    RemoteProtocolType.SMB, RemoteProtocolType.LAN -> listSmb(account, remotePath)
                    RemoteProtocolType.WEBDAV -> emptyList()
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    private fun listFtp(account: RemoteAccount, path: String): List<FileItem> {
        val client = if (account.ftps) FTPSClient() else FTPClient()
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
        val session = jsch.getSession(account.username, account.host, account.port)
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

    private fun listSmb(account: RemoteAccount, path: String): List<FileItem> {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
        }
        val baseCtx: CIFSContext = BaseContext(PropertyConfiguration(props))
        val authCtx = if (account.anonymous || account.username.isBlank()) {
            baseCtx.withAnonymousCredentials()
        } else {
            baseCtx.withCredentials(NtlmPasswordAuthenticator("", account.username, account.password))
        }
        val cleanPath = path.trimStart('/')
        val smbUrl = "smb://${account.host}:${account.port}/${if (cleanPath.isEmpty()) "" else "$cleanPath/"}"
        val dir = SmbFile(smbUrl, authCtx)
        val children = dir.listFiles() ?: return emptyList()
        return children.map { f ->
            val cleanName = f.name.trimEnd('/')
            FileItem(
                id = f.path,
                name = cleanName,
                path = f.path,
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified(),
                sourceType = FileSourceType.REMOTE,
                accountId = account.id,
                accountEmailOrLabel = "${account.host} (${account.displayName})",
                providerName = "SMB"
            )
        }
    }
}
