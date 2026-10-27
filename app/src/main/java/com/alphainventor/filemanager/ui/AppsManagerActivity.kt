package com.alphainventor.filemanager.ui

import androidx.core.content.ContextCompat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.core.InstalledAppItem
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityAppsManagerBinding
import com.alphainventor.filemanager.databinding.ItemFileRowBinding
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppsManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsManagerBinding
    private val appsAdapter = AppsAdapter()
    private var showSystemApps = false
    private var allApps: List<InstalledAppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.appsRoot, binding.appsToolbar)

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appsAdapter

        binding.btnAppsBack.setOnClickListener { finish() }

        binding.tabDownloadedApps.setOnClickListener {
            showSystemApps = false
            binding.tabDownloadedApps.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
            binding.tabAllApps.setTextColor(ThemeManager.getSecondaryTextColor(this))
            loadApps()
        }
        binding.tabAllApps.setOnClickListener {
            showSystemApps = true
            binding.tabAllApps.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
            binding.tabDownloadedApps.setTextColor(ThemeManager.getSecondaryTextColor(this))
            loadApps()
        }

        binding.etAppsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) {
                    appsAdapter.submitList(allApps)
                } else {
                    appsAdapter.submitList(allApps.filter {
                        it.appName.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
                    })
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
    }

    private fun loadApps() {
        binding.pbAppsLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            allApps = LocalFileRepository.getInstalledApps(this@AppsManagerActivity, showSystemApps)
            appsAdapter.submitList(allApps)
            binding.pbAppsLoading.visibility = View.GONE
        }
    }

    private fun showAppActionsDialog(app: InstalledAppItem) {
        val actions = arrayOf(
            "Uygulamayı Aç",
            "APK Dosyasını İndirilenler'e Yedekle",
            "Uygulama Bilgileri (Sistem Ayarları)",
            "Uygulamayı Kaldır"
        )
        AlertDialog.Builder(this)
            .setTitle("${app.appName} (${app.versionName})")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null) startActivity(launchIntent)
                    }
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val src = File(app.apkPath)
                            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val destFile = File(destDir, "${app.appName}_${app.versionName}.apk")
                            val ok = try {
                                src.copyTo(destFile, overwrite = true)
                                true
                            } catch (_: Throwable) {
                                false
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@AppsManagerActivity,
                                    if (ok) "APK yedeklendi: ${destFile.absolutePath}" else "APK yedeklenemedi",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    2 -> {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${app.packageName}")
                            }
                        )
                    }
                    3 -> {
                        startActivity(
                            Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:${app.packageName}")
                            }
                        )
                    }
                }
            }
            .show()
    }

    inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {
        private val items = mutableListOf<InstalledAppItem>()

        fun submitList(newList: List<InstalledAppItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            with(holder.binding) {
                tvSectionHeader.visibility = View.GONE
                tvIconBadge.visibility = View.GONE
                cbSelect.visibility = View.GONE

                tvFileName.text = item.appName
                tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
                tvFileSubInfo.text = FormatUtils.formatSizeDetailed(item.apkSizeBytes)
                tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
                tvFileDate.text = item.versionName
                rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

                try {
                    ivFileIcon.setImageDrawable(packageManager.getApplicationIcon(item.packageName))
                } catch (_: Throwable) {
                    ivFileIcon.setImageResource(R.drawable.ic_cat_apps)
                }

                fileRowRoot.setOnClickListener { showAppActionsDialog(item) }
            }
        }

        inner class VH(val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
