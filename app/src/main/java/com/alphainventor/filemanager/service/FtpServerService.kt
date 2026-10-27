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
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        if (isRunning) return
        isRunning = true
        activePort = port
        activeUrl = "ftp://${getLocalIpAddress()}:$port"

        serviceScope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive && isRunning) {
                    val client = serverSocket?.accept() ?: break
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
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                fun reply(line: String) {
                    writer.write("$line
")
                    writer.flush()
                }

                var currentDir = Environment.getExternalStorageDirectory()
                var dataServer: ServerSocket? = null
                reply("220 Dosya Yoneticisi + FTP Sunucusu Hazir")

                while (socket.isConnected && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val parts = line.split(" ", limit = 2)
                    val cmd = parts[0].uppercase(Locale.US)
                    val arg = if (parts.size > 1) parts[1] else ""

                    when (cmd) {
                        "USER" -> reply("331 Kullanici tamam, sifre giriniz")
                        "PASS" -> {
                            if (expectedPass.isEmpty() || arg == expectedPass || arg == "anonymous") {
                                reply("230 Giris basarili")
                            } else {
                                reply("530 Hatali sifre")
                            }
                        }
                        "SYST" -> reply("215 UNIX Type: L8")
                        "FEAT" -> {
                            writer.write("211-Features:
 UTF8
 SIZE
 PASV
211 End
")
                            writer.flush()
                        }
                        "OPTS" -> reply("200 UTF8 aktif")
                        "PWD", "XPWD" -> {
                            val rel = currentDir.absolutePath.removePrefix(
                                Environment.getExternalStorageDirectory().absolutePath
                            ).ifEmpty { "/" }
                            reply("257 "$rel" current directory")
                        }
                        "TYPE" -> reply("200 Type set to $arg")
                        "CWD" -> {
                            val target = if (arg.startsWith("/")) {
                                File(Environment.getExternalStorageDirectory(), arg)
                            } else {
                                File(currentDir, arg)
                            }
                            if (target.exists() && target.isDirectory) {
                                currentDir = target.canonicalFile
                                reply("250 Klasor degistirildi")
                            } else {
                                reply("550 Klasor bulunamadi")
                            }
                        }
                        "CDUP" -> {
                            currentDir = currentDir.parentFile ?: Environment.getExternalStorageDirectory()
                            reply("200 Ust klasore gecildi")
                        }
                        "PASV" -> {
                            dataServer?.close()
                            dataServer = ServerSocket(0)
                            val p = dataServer!!.localPort
                            val ipParts = getLocalIpAddress().split(".")
                            if (ipParts.size == 4) {
                                reply("227 Entering Passive Mode (${ipParts[0]},${ipParts[1]},${ipParts[2]},${ipParts[3]},${p / 256},${p % 256})")
                            } else {
                                reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                            }
                        }
                        "LIST", "NLST" -> {
                            reply("150 Klasor listesi gonderiliyor")
                            dataServer?.accept()?.use { dataSocket ->
                                val out = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                                val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                                val children = currentDir.listFiles().orEmpty()
                                for (f in children) {
                                    if (!showHidden && f.name.startsWith(".")) continue
                                    val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                                    val dateStr = fmt.format(Date(f.lastModified()))
                                    out.write("$perms 1 owner group ${f.length()} $dateStr ${f.name}
")
                                }
                                out.flush()
                            }
                            dataServer?.close()
                            dataServer = null
                            reply("226 Liste tamamlandi")
                        }
                        "RETR" -> {
                            val target = File(currentDir, arg)
                            if (target.exists() && target.isFile) {
                                reply("150 Dosya aktarimi basliyor (${target.length()} bytes)")
                                dataServer?.accept()?.use { dataSocket ->
                                    FileInputStream(target).use { fis ->
                                        fis.copyTo(dataSocket.getOutputStream(), 64 * 1024)
                                    }
                                }
                                reply("226 Dosya aktarimi tamamlandi")
                            } else {
                                reply("550 Dosya bulunamadi")
                            }
                            dataServer?.close()
                            dataServer = null
                        }
                        "STOR" -> {
                            val target = File(currentDir, arg)
                            reply("150 Dosya yukleme hazir")
                            dataServer?.accept()?.use { dataSocket ->
                                FileOutputStream(target).use { fos ->
                                    dataSocket.getInputStream().copyTo(fos, 64 * 1024)
                                }
                            }
                            dataServer?.close()
                            dataServer = null
                            reply("226 Dosya kaydedildi")
                        }
                        "QUIT" -> {
                            reply("221 Gule gule")
                            break
                        }
                        else -> reply("202 Komut tamam")
                    }
                }
                dataServer?.close()
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
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (_: Throwable) {}
            return "192.168.1.100"
        }
    }
}
