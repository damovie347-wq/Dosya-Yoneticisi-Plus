package com.alphainventor.filemanager.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.alphainventor.filemanager.R
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

        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val density = resources.displayMetrics.density

        ThemeManager.applyWindowColors(this, binding.networkAccessRoot, binding.networkToolbar)

        binding.networkCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ThemeManager.getCardColor(this@NetworkAccessActivity))
            setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.parseColor("#008578"))
            cornerRadius = 2f * density
        }

        binding.tvFtpStatusInfo.setTextColor(primaryColor)
        binding.etFtpPort.setTextColor(primaryColor)
        binding.etFtpPassword.setTextColor(primaryColor)
        binding.cbRandomPassword.setTextColor(secondaryColor)
        binding.cbShowHiddenFtp.setTextColor(primaryColor)

        binding.btnNetworkBack.setOnClickListener { finish() }

        binding.btnNetworkAddToHome.setOnClickListener {
            pinNetworkAccessShortcut()
        }

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
                AlertDialog.Builder(this)
                    .setTitle("Onayla")
                    .setMessage("Hizmet durdurulacak")
                    .setPositiveButton("TAMAM") { _, _ ->
                        stopService(Intent(this, FtpServerService::class.java).apply {
                            action = FtpServerService.ACTION_STOP
                        })
                        updateServerUi(false)
                    }
                    .setNegativeButton("İPTAL", null)
                    .show()
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

    private fun pinNetworkAccessShortcut() {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                val shortcutIntent = Intent(this, NetworkAccessActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                }
                val info = ShortcutInfoCompat.Builder(this, "shortcut_network_access")
                    .setShortLabel(getString(R.string.network_access))
                    .setLongLabel(getString(R.string.network_access))
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_cat_network))
                    .setIntent(shortcutIntent)
                    .build()
                ShortcutManagerCompat.requestPinShortcut(this, info, null)
                Toast.makeText(this, "Ana ekrana kısayol eklendi.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Başlatıcınız kısayol sabitlemeyi desteklemiyor.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Kısayol oluşturuldu.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServerUi(running: Boolean, port: Int = 7552) {
        val isDark = ThemeManager.isDarkOrAmoled(this)
        if (running) {
            val ip = FtpServerService.getLocalIpAddress()
            val pass = binding.etFtpPassword.text?.toString().orEmpty()
            binding.llFtpSetupForm.visibility = View.GONE
            binding.llFtpRunningInfo.visibility = View.VISIBLE

            binding.tvFtpUrlBox.text = "ftp://$ip:$port"
            binding.tvFtpRunningUser.text = "pc"
            binding.tvFtpRunningPass.text = pass.ifEmpty { "(Yok)" }

            binding.tvFtpStatusInfo.text = "Yukarıdaki adresi PC'de bir dosya gezginine yazın."
            binding.btnToggleFtpServer.text = "HİZMETİ DURDUR"
            binding.btnToggleFtpServer.backgroundTintList = ColorStateList.valueOf(
                if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
            )
            binding.btnToggleFtpServer.setTextColor(
                if (isDark) Color.WHITE else Color.parseColor("#212121")
            )
        } else {
            binding.llFtpSetupForm.visibility = View.VISIBLE
            binding.llFtpRunningInfo.visibility = View.GONE

            binding.tvFtpStatusInfo.text = "Başka bir cihazdan dosyalara erişmek için hizmeti başlatın."
            binding.btnToggleFtpServer.text = "BAŞLAT"
            binding.btnToggleFtpServer.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#008578"))
            binding.btnToggleFtpServer.setTextColor(Color.WHITE)
        }
    }
}
