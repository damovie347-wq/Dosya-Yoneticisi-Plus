package com.alphainventor.filemanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityMainBinding
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.HomeCategoryItem
import com.alphainventor.filemanager.model.HomeCategoryType
import com.alphainventor.filemanager.model.RemoteAccount
import com.alphainventor.filemanager.remote.RemoteManager
import com.alphainventor.filemanager.theme.AppThemeMode
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.ui.adapter.DrawerEntry
import com.alphainventor.filemanager.ui.adapter.DrawerNavAdapter
import com.alphainventor.filemanager.ui.adapter.HomeGridAdapter
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeGridAdapter: HomeGridAdapter
    private lateinit var drawerAdapter: DrawerNavAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHomeGrid()
        setupDrawer()
        setupToolbarActions()
        checkStoragePermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        refreshDashboardMetrics()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateGridSpanCount(newConfig.orientation)
    }

    private fun setupHomeGrid() {
        homeGridAdapter = HomeGridAdapter { item ->
            onHomeCategoryClicked(item.type)
        }
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 4
        binding.rvHomeGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
            setHasFixedSize(true)
            adapter = homeGridAdapter
        }
        binding.swipeRefreshHome.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_teal))
        binding.swipeRefreshHome.setOnRefreshListener {
            refreshDashboardMetrics()
        }
    }

    private fun updateGridSpanCount(orientation: Int) {
        val span = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 4
        (binding.rvHomeGrid.layoutManager as? GridLayoutManager)?.spanCount = span
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerNavAdapter { entry ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (entry.id) {
                "HOME" -> {}
                "MAIN_STORAGE" -> openExplorer(LocalFileRepository.rootStorageDir.absolutePath, "Ana bellek")
                "RECYCLE_BIN" -> openExplorer(LocalFileRepository.recycleBinDir.absolutePath, "Geri Dönüşüm Kutusu")
                "NEW_FILES" -> openCategoryExplorer("NEW_FILES", "Yeni Dosyalar")
                "DOCUMENTS" -> openCategoryExplorer("DOCUMENTS", "Belgeler")
                "IMAGES" -> openCategoryExplorer("IMAGES", "Görüntüler")
                "VIDEOS" -> openCategoryExplorer("VIDEOS", "Videolar")
                "AUDIO" -> openCategoryExplorer("AUDIO", "Ses")
                "DOWNLOADS" -> {
                    val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    openExplorer(dl.absolutePath, "İndirilenler")
                }
                else -> {
                    when (val p = entry.payload) {
                        is CloudAccount -> {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "CLOUD")
                                putExtra("CLOUD_ACCOUNT", p)
                            })
                        }
                        is RemoteAccount -> {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "REMOTE")
                                putExtra("REMOTE_ACCOUNT", p)
                            })
                        }
                    }
                }
            }
        }
        binding.rvDrawerItems.layoutManager = LinearLayoutManager(this)
        binding.rvDrawerItems.adapter = drawerAdapter

        binding.btnNavDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.edgeDrawerHandle.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnDrawerThemeFooter.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showThemePickerDialog()
        }
    }

    private fun setupToolbarActions() {
        binding.btnQuickCloudSearch.setOnClickListener {
            startActivity(Intent(this, CloudAccountsActivity::class.java).apply {
                putExtra("FOCUS_UNIFIED_SEARCH", true)
            })
        }
        binding.btnQuickTheme.setOnClickListener {
            showThemePickerDialog()
        }
        binding.btnMoreMenu.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Ayarlar")
            popup.menu.add(0, 2, 1, "Analiz et")
            popup.menu.add(0, 3, 2, "Yenile")
            popup.menu.add(0, 4, 3, "Tema Seç (Açık / Koyu / AMOLED)")
            popup.menu.add(0, 5, 4, "Tüm Bulutlarda Toplu Ara")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> startActivity(Intent(this, StorageAnalysisActivity::class.java))
                    3 -> refreshDashboardMetrics()
                    4 -> showThemePickerDialog()
                    5 -> startActivity(Intent(this, CloudAccountsActivity::class.java).apply {
                        putExtra("FOCUS_UNIFIED_SEARCH", true)
                    })
                }
                true
            }
            popup.show()
        }
    }

    private fun onHomeCategoryClicked(type: HomeCategoryType) {
        when (type) {
            HomeCategoryType.MAIN_STORAGE -> openExplorer(LocalFileRepository.rootStorageDir.absolutePath, "Ana bellek")
            HomeCategoryType.DOWNLOADS -> {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                openExplorer(dl.absolutePath, "İndirilenler")
            }
            HomeCategoryType.STORAGE_ANALYSIS -> startActivity(Intent(this, StorageAnalysisActivity::class.java))
            HomeCategoryType.IMAGES -> openCategoryExplorer("IMAGES", "Görüntüler")
            HomeCategoryType.AUDIO -> openCategoryExplorer("AUDIO", "Ses")
            HomeCategoryType.VIDEOS -> openCategoryExplorer("VIDEOS", "Videolar")
            HomeCategoryType.DOCUMENTS -> openCategoryExplorer("DOCUMENTS", "Belgeler")
            HomeCategoryType.APPS -> startActivity(Intent(this, AppsManagerActivity::class.java))
            HomeCategoryType.NEW_FILES -> openCategoryExplorer("NEW_FILES", "Yeni Dosyalar")
            HomeCategoryType.CLOUD -> startActivity(Intent(this, CloudAccountsActivity::class.java))
            HomeCategoryType.REMOTE -> startActivity(Intent(this, RemoteAccountsActivity::class.java))
            HomeCategoryType.NETWORK_ACCESS -> startActivity(Intent(this, NetworkAccessActivity::class.java))
        }
    }

    private fun openExplorer(path: String, title: String) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "LOCAL")
            putExtra("PATH", path)
            putExtra("TITLE", title)
        })
    }

    private fun openCategoryExplorer(category: String, title: String) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "CATEGORY")
            putExtra("CATEGORY", category)
            putExtra("TITLE", title)
        })
    }

    private fun refreshDashboardMetrics() {
        binding.swipeRefreshHome.isRefreshing = true
        val overview = LocalFileRepository.getStorageOverview()
        val cloudCount = CloudManager.getAccounts(this).size
        val remoteCount = RemoteManager.getRemoteAccounts(this).size
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dlFiles = dlDir.listFiles().orEmpty()
        val dlSize = dlFiles.sumOf { if (it.isFile) it.length() else 0L }

        val initialItems = listOf(
            HomeCategoryItem(
                HomeCategoryType.MAIN_STORAGE,
                "Ana bellek",
                "${FormatUtils.formatSize(overview.usedBytes)} / ${FormatUtils.formatSize(overview.totalBytes)}",
                R.drawable.ic_cat_storage,
                overview.usedPercent
            ),
            HomeCategoryItem(
                HomeCategoryType.DOWNLOADS,
                "İndirilenler",
                "${FormatUtils.formatSize(dlSize)} (${dlFiles.size})",
                R.drawable.ic_cat_downloads
            ),
            HomeCategoryItem(
                HomeCategoryType.STORAGE_ANALYSIS,
                "Bellek Analizi",
                "Kullanılan ${overview.usedPercent}%",
                R.drawable.ic_cat_analysis
            ),
            HomeCategoryItem(HomeCategoryType.IMAGES, "Görüntüler", "Taranıyor...", R.drawable.ic_cat_images),
            HomeCategoryItem(HomeCategoryType.AUDIO, "Ses", "Taranıyor...", R.drawable.ic_cat_audio),
            HomeCategoryItem(HomeCategoryType.VIDEOS, "Videolar", "Taranıyor...", R.drawable.ic_cat_videos),
            HomeCategoryItem(HomeCategoryType.DOCUMENTS, "Belgeler", "Taranıyor...", R.drawable.ic_cat_docs),
            HomeCategoryItem(HomeCategoryType.APPS, "Uygulamalar", "Taranıyor...", R.drawable.ic_cat_apps),
            HomeCategoryItem(HomeCategoryType.NEW_FILES, "Yeni Dosyalar", "Taranıyor...", R.drawable.ic_cat_new_files),
            HomeCategoryItem(HomeCategoryType.CLOUD, "Bulut", "($cloudCount)", R.drawable.ic_cat_cloud),
            HomeCategoryItem(HomeCategoryType.REMOTE, "Uzak", "($remoteCount)", R.drawable.ic_cat_remote),
            HomeCategoryItem(HomeCategoryType.NETWORK_ACCESS, "Ağdan erişim", "", R.drawable.ic_cat_network)
        )
        homeGridAdapter.submitList(initialItems)
        populateDrawerItems(overview.usedPercent)

        lifecycleScope.launch {
            val imgs = LocalFileRepository.scanCategoryFiles("IMAGES")
            val auds = LocalFileRepository.scanCategoryFiles("AUDIO")
            val vids = LocalFileRepository.scanCategoryFiles("VIDEOS")
            val docs = LocalFileRepository.scanCategoryFiles("DOCUMENTS")
            val news = LocalFileRepository.scanCategoryFiles("NEW_FILES")
            val apps = LocalFileRepository.getInstalledApps(this@MainActivity, includeSystem = false)

            val updated = listOf(
                initialItems[0],
                initialItems[1],
                initialItems[2],
                HomeCategoryItem(
                    HomeCategoryType.IMAGES,
                    "Görüntüler",
                    "${FormatUtils.formatSize(imgs.sumOf { it.size })} (${imgs.size})",
                    R.drawable.ic_cat_images
                ),
                HomeCategoryItem(
                    HomeCategoryType.AUDIO,
                    "Ses",
                    "${FormatUtils.formatSize(auds.sumOf { it.size })} (${auds.size})",
                    R.drawable.ic_cat_audio
                ),
                HomeCategoryItem(
                    HomeCategoryType.VIDEOS,
                    "Videolar",
                    "${FormatUtils.formatSize(vids.sumOf { it.size })} (${vids.size})",
                    R.drawable.ic_cat_videos
                ),
                HomeCategoryItem(
                    HomeCategoryType.DOCUMENTS,
                    "Belgeler",
                    "${FormatUtils.formatSize(docs.sumOf { it.size })} (${docs.size})",
                    R.drawable.ic_cat_docs
                ),
                HomeCategoryItem(
                    HomeCategoryType.APPS,
                    "Uygulamalar",
                    "${FormatUtils.formatSize(apps.sumOf { it.apkSizeBytes })} (${apps.size})",
                    R.drawable.ic_cat_apps
                ),
                HomeCategoryItem(
                    HomeCategoryType.NEW_FILES,
                    "Yeni Dosyalar",
                    "${FormatUtils.formatSize(news.sumOf { it.size })} (${news.size})",
                    R.drawable.ic_cat_new_files
                ),
                HomeCategoryItem(HomeCategoryType.CLOUD, "Bulut", "($cloudCount)", R.drawable.ic_cat_cloud),
                HomeCategoryItem(HomeCategoryType.REMOTE, "Uzak", "($remoteCount)", R.drawable.ic_cat_remote),
                HomeCategoryItem(HomeCategoryType.NETWORK_ACCESS, "Ağdan erişim", "", R.drawable.ic_cat_network)
            )
            withContext(Dispatchers.Main) {
                homeGridAdapter.submitList(updated)
                binding.swipeRefreshHome.isRefreshing = false
            }
        }
    }

    private fun populateDrawerItems(usedPct: Int) {
        val entries = mutableListOf(
            DrawerEntry("HOME", "Ana Ekran", iconRes = R.drawable.ic_home_crumb),
            DrawerEntry("MAIN_STORAGE", "Ana bellek", rightBadge = "$usedPct%", iconRes = R.drawable.ic_cat_storage),
            DrawerEntry("RECYCLE_BIN", "Geri Dönüşüm Kutusu", rightBadge = "⋮", iconRes = R.drawable.ic_recycle_bin)
        )
        // Ekli bulut hesaplarını (örn: Google Drive daamfgf@gmail.com) videodaki (01:43) gibi sol çekmeceye ekle
        for (acc in CloudManager.getAccounts(this).take(5)) {
            entries.add(
                DrawerEntry(
                    id = "CLOUD_${acc.id}",
                    title = "${acc.provider.displayName} (${acc.emailOrName})",
                    rightBadge = "⋮",
                    iconRes = R.drawable.ic_cloud_gdrive,
                    payload = acc
                )
            )
        }
        entries.addAll(
            listOf(
                DrawerEntry("NEW_FILES", "Yeni Dosyalar", rightBadge = "⋮", iconRes = R.drawable.ic_cat_new_files),
                DrawerEntry("DOCUMENTS", "Belgeler", rightBadge = "⋮", iconRes = R.drawable.ic_cat_docs),
                DrawerEntry("IMAGES", "Görüntüler", rightBadge = "⋮", iconRes = R.drawable.ic_cat_images),
                DrawerEntry("VIDEOS", "Videolar", rightBadge = "⋮", iconRes = R.drawable.ic_cat_videos),
                DrawerEntry("AUDIO", "Ses", rightBadge = "⋮", iconRes = R.drawable.ic_cat_audio),
                DrawerEntry("DOWNLOADS", "İndirilenler", rightBadge = "⋮", iconRes = R.drawable.ic_cat_downloads)
            )
        )
        for (rem in RemoteManager.getRemoteAccounts(this)) {
            entries.add(
                DrawerEntry(
                    id = "REMOTE_${rem.id}",
                    title = rem.host,
                    subtitle = rem.initialPath,
                    rightBadge = "⋮",
                    iconRes = R.drawable.ic_folder_modern,
                    payload = rem
                )
            )
        }
        drawerAdapter.submitList(entries)
    }

    fun showThemePickerDialog() {
        val modes = AppThemeMode.entries.toTypedArray()
        val labels = modes.map { it.displayName }.toTypedArray()
        val currentIdx = modes.indexOf(ThemeManager.getThemeMode(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Tema Seçin (Açık / Koyu / AMOLED)")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                ThemeManager.setThemeMode(this, modes[which])
                applyCurrentTheme()
                dialog.dismiss()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun applyCurrentTheme() {
        ThemeManager.applyWindowColors(this, binding.rootContainer, binding.topToolbar)
        binding.navDrawerContainer.setBackgroundColor(ThemeManager.getBackgroundColor(this))
        binding.drawerBottomDivider.setBackgroundColor(ThemeManager.getDividerColor(this))
        binding.tvDrawerThemeLabel.text = "TEMA: ${ThemeManager.getThemeMode(this).name}"
        homeGridAdapter.notifyDataSetChanged()
        drawerAdapter.notifyDataSetChanged()
    }

    private fun checkStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Tüm Dosyalara Erişim İzni")
                    .setMessage("Dosya Yöneticisi + uygulamasının tüm klasörleri, videoları ve arşivleri hızlıca yönetebilmesi için Tüm Dosyalara Erişim iznini etkinleştirin.")
                    .setPositiveButton("İzin Ver") { _, _ ->
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (_: Throwable) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("Daha Sonra", null)
                    .show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        }
    }
}
