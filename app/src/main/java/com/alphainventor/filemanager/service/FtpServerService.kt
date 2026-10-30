package com.alphainventor.filemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alphainventor.filemanager.R
import kotlinx.coroutines.*
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FtpServerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 7552) ?: 7552
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: "123"
        val showHidden = intent?.getBooleanExtra(EXTRA_SHOW_HIDDEN, true) ?: true

        startForeground(NOTIFICATION_ID, buildNotification(port))
        startServer(port, password, showHidden)
        return START_STICKY
    }

    private fun startServer(port: Int, password: String, showHidden: Boolean) {
        if (isRunning && activePort == port) return
        stopServer()
        isRunning = true
        activePort = port
        activeUrl = "ftp://${getLocalIpAddress()}:$port"

        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), 50)
                }
                while (isActive && isRunning) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    client.keepAlive = true
                    launch { handleFtpClient(client, password, showHidden) }
                }
            } catch (_: Throwable) {
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun handleFtpClient(client: Socket, expectedPass: String, showHidden: Boolean) =
        withContext(Dispatchers.IO) {
            val rootDir = Environment.getExternalStorageDirectory()
            var currentDir = rootDir
            var dataServer: ServerSocket? = null
            var activeHost: String? = null
            var activeDataPort: Int = -1
            var restOffset: Long = 0L
            var renameFromFile: File? = null

            fun resolveFtpPath(rawArg: String): File {
                val cleaned = rawArg.trim()
                if (cleaned.isEmpty() || cleaned == "." || cleaned == "./") return currentDir
                val rootPath = rootDir.absolutePath
                return if (cleaned.startsWith(rootPath)) {
                    File(cleaned)
                } else if (cleaned.startsWith("/")) {
                    val rel = cleaned.trimStart('/')
                    if (rel.isEmpty()) rootDir else File(rootDir, rel)
                } else {
                    File(currentDir, cleaned)
                }
            }

            fun openDataSocket(): Socket? {
                val ds = dataServer
                if (ds != null) {
                    ds.soTimeout = 20_000
                    return ds.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 256 * 1024
                        receiveBufferSize = 256 * 1024
                    }
                }
                val h = activeHost
                val p = activeDataPort
                if (!h.isNullOrBlank() && p > 0) {
                    return Socket(h, p).apply {
                        tcpNoDelay = true
                        sendBufferSize = 256 * 1024
                        receiveBufferSize = 256 * 1024
                    }
                }
                return null
            }

            fun closeDataChannel() {
                try {
                    dataServer?.close()
                } catch (_: Throwable) {
                }
                dataServer = null
                activeHost = null
                activeDataPort = -1
            }

            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                    fun reply(line: String) {
                        try {
                            writer.write("$line\r\n")
                            writer.flush()
                        } catch (_: Throwable) {
                        }
                    }

                    val mdtmFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }

                    reply("220 Dosya Yoneticisi + FTP Sunucusu Hazir")

                    while (socket.isConnected && !socket.isClosed) {
                        val rawLine = try {
                            reader.readLine()
                        } catch (_: Throwable) {
                            null
                        } ?: break

                        val parts = rawLine.trim().split(" ", limit = 2)
                        val cmd = parts[0].uppercase(Locale.US)
                        val arg = if (parts.size > 1) parts[1].trim() else ""

                        when (cmd) {
                            "USER" -> reply("331 Kullanici tamam, sifre giriniz")
                            "PASS" -> {
                                if (expectedPass.isEmpty() || arg == expectedPass || arg.equals("anonymous", ignoreCase = true)) {
                                    reply("230 Giris basarili")
                                } else {
                                    reply("530 Hatali sifre")
                                }
                            }
                            "SYST" -> reply("215 UNIX Type: L8")
                            "FEAT" -> {
                                writer.write(
                                    "211-Features:\r\n" +
                                        " UTF8\r\n" +
                                        " SIZE\r\n" +
                                        " MDTM\r\n" +
                                        " REST STREAM\r\n" +
                                        " PASV\r\n" +
                                        " EPSV\r\n" +
                                        " TVFS\r\n" +
                                        " MLST type*;size*;modify*;\r\n" +
                                        " MLSD\r\n" +
                                        "211 End\r\n"
                                )
                                writer.flush()
                            }
                            "OPTS" -> reply("200 UTF8 aktif")
                            "NOOP" -> reply("200 NOOP tamam")
                            "ALLO" -> reply("200 ALLO tamam")
                            "ABOR" -> {
                                closeDataChannel()
                                restOffset = 0L
                                reply("226 ABOR tamam")
                            }
                            "PWD", "XPWD" -> {
                                val rel = currentDir.absolutePath.removePrefix(rootDir.absolutePath)
                                val ftpPath = if (rel.isEmpty()) "/" else if (rel.startsWith("/")) rel else "/$rel"
                                reply("257 \"$ftpPath\" current directory")
                            }
                            "TYPE" -> reply("200 Type set to $arg")
                            "MODE" -> reply("200 Mode tamam")
                            "STRU" -> reply("200 Structure tamam")
                            "CWD", "XCWD" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() && target.isDirectory) {
                                    currentDir = target.canonicalFile
                                    reply("250 Klasor degistirildi")
                                } else {
                                    reply("550 Klasor bulunamadi")
                                }
                            }
                            "CDUP", "XCUP" -> {
                                val parent = currentDir.parentFile
                                currentDir = if (parent != null && parent.absolutePath.startsWith(rootDir.absolutePath)) {
                                    parent
                                } else {
                                    rootDir
                                }
                                reply("200 Ust klasore gecildi")
                            }
                            "SIZE" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() && target.isFile) {
                                    reply("213 ${target.length()}")
                                } else {
                                    reply("550 Dosya bulunamadi")
                                }
                            }
                            "MDTM" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists()) {
                                    reply("213 ${mdtmFormat.format(Date(target.lastModified()))}")
                                } else {
                                    reply("550 Dosya bulunamadi")
                                }
                            }
                            "REST" -> {
                                restOffset = arg.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                                reply("350 Restarting at $restOffset. Send STORE or RETRIEVE")
                            }
                            "PASV" -> {
                                closeDataChannel()
                                dataServer = ServerSocket(0)
                                val p = dataServer!!.localPort
                                val ipParts = getLocalIpAddress().split(".")
                                if (ipParts.size == 4) {
                                    reply("227 Entering Passive Mode (${ipParts[0]},${ipParts[1]},${ipParts[2]},${ipParts[3]},${p / 256},${p % 256})")
                                } else {
                                    reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                                }
                            }
                            "EPSV" -> {
                                closeDataChannel()
                                dataServer = ServerSocket(0)
                                val p = dataServer!!.localPort
                                reply("229 Entering Extended Passive Mode (|||$p|)")
                            }
                            "PORT" -> {
                                closeDataChannel()
                                val nums = arg.split(",").mapNotNull { it.trim().toIntOrNull() }
                                if (nums.size == 6) {
                                    activeHost = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
                                    activeDataPort = (nums[4] * 256) + nums[5]
                                    reply("200 PORT komutu basarili")
                                } else {
                                    reply("501 Gecersiz PORT argumani")
                                }
                            }
                            "LIST", "NLST" -> {
                                val cleanArg = arg.split(" ")
                                    .filterNot { it.startsWith("-") }
                                    .joinToString(" ")
                                    .trim()
                                val listDir = if (cleanArg.isEmpty()) currentDir else resolveFtpPath(cleanArg)
                                val targetDir = if (listDir.exists() && listDir.isDirectory) listDir else currentDir

                                reply("150 Klasor listesi gonderiliyor")
                                try {
                                    openDataSocket()?.use { dataSocket ->
                                        val out = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                                        val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                                        val children = targetDir.listFiles().orEmpty()
                                        for (f in children) {
                                            if (!showHidden && f.name.startsWith(".")) continue
                                            if (cmd == "NLST") {
                                                out.write("${f.name}\r\n")
                                            } else {
                                                val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                                                val dateStr = fmt.format(Date(f.lastModified()))
                                                val len = if (f.isDirectory) 4096L else f.length()
                                                out.write("$perms 1 owner group $len $dateStr ${f.name}\r\n")
                                            }
                                        }
                                        out.flush()
                                    }
                                    reply("226 Liste tamamlandi")
                                } catch (_: Throwable) {
                                    reply("425 Veri baglantisi acilamadi")
                                } finally {
                                    closeDataChannel()
                                }
                            }
                            "MLSD" -> {
                                val targetDir = if (arg.isEmpty()) currentDir else resolveFtpPath(arg)
                                reply("150 MLSD listesi gonderiliyor")
                                try {
                                    openDataSocket()?.use { dataSocket ->
                                        val out = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                                        val children = targetDir.listFiles().orEmpty()
                                        for (f in children) {
                                            if (!showHidden && f.name.startsWith(".")) continue
                                            val typeStr = if (f.isDirectory) "dir" else "file"
                                            val modStr = mdtmFormat.format(Date(f.lastModified()))
                                            val sizeVal = if (f.isDirectory) 0L else f.length()
                                            out.write("type=$typeStr;size=$sizeVal;modify=$modStr; ${f.name}\r\n")
                                        }
                                        out.flush()
                                    }
                                    reply("226 MLSD tamamlandi")
                                } catch (_: Throwable) {
                                    reply("425 Veri baglantisi acilamadi")
                                } finally {
                                    closeDataChannel()
                                }
                            }
                            "MLST" -> {
                                val target = if (arg.isEmpty()) currentDir else resolveFtpPath(arg)
                                if (target.exists()) {
                                    val typeStr = if (target.isDirectory) "dir" else "file"
                                    val modStr = mdtmFormat.format(Date(target.lastModified()))
                                    val sizeVal = if (target.isDirectory) 0L else target.length()
                                    writer.write("250-Listing ${target.name}\r\n type=$typeStr;size=$sizeVal;modify=$modStr; ${target.name}\r\n250 End\r\n")
                                    writer.flush()
                                } else {
                                    reply("550 Dosya bulunamadi")
                                }
                            }
                            "RETR" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() && target.isFile) {
                                    val startPos = restOffset.coerceIn(0L, target.length())
                                    restOffset = 0L
                                    reply("150 Dosya aktarimi basliyor (${target.length() - startPos} bytes)")
                                    try {
                                        openDataSocket()?.use { dataSocket ->
                                            RandomAccessFile(target, "r").use { raf ->
                                                if (startPos > 0L) {
                                                    raf.seek(startPos)
                                                }
                                                val out = BufferedOutputStream(dataSocket.getOutputStream(), 256 * 1024)
                                                val buf = ByteArray(256 * 1024)
                                                while (true) {
                                                    val read = raf.read(buf)
                                                    if (read <= 0) break
                                                    out.write(buf, 0, read)
                                                }
                                                out.flush()
                                            }
                                        }
                                        reply("226 Dosya aktarimi tamamlandi")
                                    } catch (_: Throwable) {
                                        reply("426 Transfer kesildi")
                                    } finally {
                                        closeDataChannel()
                                    }
                                } else {
                                    restOffset = 0L
                                    closeDataChannel()
                                    reply("550 Dosya bulunamadi")
                                }
                            }
                            "STOR", "APPE" -> {
                                val target = resolveFtpPath(arg)
                                target.parentFile?.mkdirs()
                                val offset = restOffset
                                restOffset = 0L
                                reply("150 Dosya yukleme hazir")
                                try {
                                    openDataSocket()?.use { dataSocket ->
                                        RandomAccessFile(target, "rw").use { raf ->
                                            if (cmd == "APPE") {
                                                raf.seek(target.length())
                                            } else if (offset > 0L && offset <= target.length()) {
                                                raf.seek(offset)
                                            } else {
                                                raf.setLength(0L)
                                            }
                                            val input = BufferedInputStream(dataSocket.getInputStream(), 256 * 1024)
                                            val buf = ByteArray(256 * 1024)
                                            while (true) {
                                                val read = input.read(buf)
                                                if (read <= 0) break
                                                raf.write(buf, 0, read)
                                            }
                                        }
                                    }
                                    reply("226 Dosya kaydedildi")
                                } catch (_: Throwable) {
                                    reply("426 Yukleme kesildi")
                                } finally {
                                    closeDataChannel()
                                }
                            }
                            "DELE" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() && target.isFile && target.delete()) {
                                    reply("250 Dosya silindi")
                                } else {
                                    reply("550 Dosya silinemedi")
                                }
                            }
                            "MKD", "XMKD" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() || target.mkdirs()) {
                                    reply("257 \"${target.name}\" klasoru olusturuldu")
                                } else {
                                    reply("550 Klasor olusturulamadi")
                                }
                            }
                            "RMD", "XRMD" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists() && target.isDirectory && target.deleteRecursively()) {
                                    reply("250 Klasor silindi")
                                } else {
                                    reply("550 Klasor silinemedi")
                                }
                            }
                            "RNFR" -> {
                                val target = resolveFtpPath(arg)
                                if (target.exists()) {
                                    renameFromFile = target
                                    reply("350 Yeniden adlandirma icin hedef bekleniyor")
                                } else {
                                    reply("550 Kaynak dosya bulunamadi")
                                }
                            }
                            "RNTO" -> {
                                val src = renameFromFile
                                renameFromFile = null
                                val dst = resolveFtpPath(arg)
                                if (src != null && src.renameTo(dst)) {
                                    reply("250 Yeniden adlandirildi")
                                } else {
                                    reply("550 Yeniden adlandirma basarisiz")
                                }
                            }
                            "STAT" -> reply("211 Dosya Yoneticisi + FTP Sunucusu Hazir")
                            "QUIT" -> {
                                reply("221 Gule gule")
                                break
                            }
                            else -> reply("202 Komut tamam")
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                closeDataChannel()
            }
        }

    private fun stopServer() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    override fun onDestroy() {
        stopServer()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val channelId = "ftp_server_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Ağdan Erişim FTP Sunucusu", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_cat_network)
            .setContentTitle("Dosya Yöneticisi + Ağdan Erişim Aktif")
            .setContentText("Adres: ftp://${getLocalIpAddress()}:$port")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.alphainventor.filemanager.START_FTP"
        const val ACTION_STOP = "com.alphainventor.filemanager.STOP_FTP"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_SHOW_HIDDEN = "extra_show_hidden"
        private const val NOTIFICATION_ID = 7552

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activePort: Int = 7552
            private set

        @Volatile
        var activeUrl: String = "ftp://192.168.1.100:7552"
            private set

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp || intf.isLoopback) continue
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                                return host
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            return "192.168.1.100"
        }
    }
}
