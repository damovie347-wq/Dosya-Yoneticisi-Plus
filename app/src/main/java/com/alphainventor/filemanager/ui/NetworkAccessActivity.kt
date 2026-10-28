package com.alphainventor.filemanager.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.alphainventor.filemanager.databinding.ActivityNetworkAccessBinding
import com.alphainventor.filemanager.service.FtpServerService
import com.alphainventor.filemanager.theme.ThemeManager
import kotlin.random.Random

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Ağdan Erişim (FTP Sunucusu - fragment_server.xml) Ekranı:
 * - AMOLED, Koyu ve Açık temada kart ve metin kontrastını otomatik optimize eder.
 * - Hizmet başlatıldığında ftp://IP:PORT adresini, kullanıcı adını (pc / anonymous) ve şifreyi net gösterir.
 */
class NetworkAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkAccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)

        ThemeManager.applyWindowColors(this, binding.networkAccessRoot, binding.networkToolbar)
        binding.networkCard.setBackgroundColor(ThemeManager.getCardColor(this))
        binding.tvFtpStatusInfo.setTextColor(primaryColor)
        binding.etFtpPort.setTextColor(primaryColor)
        binding.etFtpPassword.setTextColor(primaryColor)
        binding.cbRandomPassword.setTextColor(secondaryColor)
        binding.cbShowHiddenFtp.setTextColor(secondaryColor)

        binding.btnNetworkBack.setOnClickListener { finish() }

        binding.cbRandomPassword.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.etFtpPassword.setText(Random.nextInt(100000, 999999).toString())
                binding.etFtpPassword.isEnabled = false
            } else {
                binding.etFtpPassword.isEnabled = true
            }
        }

        binding.btnToggleFtpServer.setOnClickListener {
            if (FtpServerService.isRunning) {
                stopService(Intent(this, FtpServerService::class.java).apply {
                    action = FtpServerService.ACTION_STOP
                })
                updateServerUi(false)
            } else {
                val port = binding.etFtpPort.text.toString().toIntOrNull() ?: 7552
                val pass = binding.etFtpPassword.text.toString()
                val showHidden = binding.cbShowHiddenFtp.isChecked
                val svcIntent = Intent(this, FtpServerService::class.java).apply {
                    action = FtpServerService.ACTION_START
                    putExtra(FtpServerService.EXTRA_PORT, port)
                    putExtra(FtpServerService.EXTRA_PASSWORD, pass)
                    putExtra(FtpServerService.EXTRA_SHOW_HIDDEN, showHidden)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
                updateServerUi(true, port)
            }
        }

        updateServerUi(FtpServerService.isRunning, FtpServerService.activePort)
    }

    private fun updateServerUi(running: Boolean, port: Int = 7552) {
        if (running) {
            val ip = FtpServerService.getLocalIpAddress()
            val pass = binding.etFtpPassword.text?.toString().orEmpty()
            binding.tvFtpStatusInfo.text = "Hizmet çalışıyor\nKullanıcı adı: pc   •   Şifre: ${pass.ifEmpty { "(Yok)" }}"
            binding.tvFtpUrlBox.visibility = View.VISIBLE
            binding.tvFtpUrlBox.text = "ftp://$ip:$port"
            binding.btnToggleFtpServer.text = "DURDUR"
        } else {
            binding.tvFtpStatusInfo.text = "Başka bir cihazdan dosyalara erişmek için hizmeti başlatın."
            binding.tvFtpUrlBox.visibility = View.GONE
            binding.btnToggleFtpServer.text = "BAŞLAT"
        }
    }
}
