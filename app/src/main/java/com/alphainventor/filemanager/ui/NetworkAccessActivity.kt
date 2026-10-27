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

class NetworkAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkAccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.networkAccessRoot, binding.networkToolbar)
        binding.tvFtpStatusInfo.setTextColor(ThemeManager.getPrimaryTextColor(this))
        binding.etFtpPort.setTextColor(ThemeManager.getPrimaryTextColor(this))
        binding.etFtpPassword.setTextColor(ThemeManager.getPrimaryTextColor(this))

        binding.btnNetworkBack.setOnClickListener { finish() }

        binding.cbRandomPassword.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.etFtpPassword.setText(Random.nextInt(1000, 9999).toString())
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
            binding.tvFtpStatusInfo.text = "Hizmet çalışıyor. Bilgisayarınızdan veya başka bir cihazdan şu adresi girin:"
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
