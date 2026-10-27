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
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
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

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Ana Ekranı (00:00 - 00:06 ve 01:54 - 01:58):
 * - 12 kategori kartı (Yatay/Tablet modunda 6 sütun, Dikey telefon modunda 3/4 sütun)
 * - Ana bellek (3D disk ikonu + mavi doluluk çubuğu), İndirilenler (3D klasör ikonu), Bellek Analizi (Yuvarlak pasta grafiği PieProgressView)
 * - Görüntüler, Ses, Videolar, Belgeler, Uygulamalar, Yeni Dosyalar, Bulut, Uzak, Ağdan erişim (Orijinal bg_desktop_shape squircle + vektör ikon)
 * - Sol Çekmece: Klasör / Favoriler / Geçmiş 3 ikonlu sekme + Ana Ekran, Ana bellek, Geri Dönüşüm Kutusu, İndirilenler, Bulut Hesapları
 * - Çift geri tuşu ile "Çıkmak için tekrar geri düğmesine basın" (01:58)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeGridAdapter: HomeGridAdapter
    private lateinit var drawerAdapter: DrawerNavAdapter

    private var activeDrawerTab: Int = 0
    private var lastBackPressMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbarAndDrawer()
        setupHomeGrid()
        applyCurrentTheme()
        checkAndRequestStoragePermissions()
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        refreshHomeDashboard()
        refreshDrawerEntries()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateGridSpanCount()
    }

    private fun setupToolbarAndDrawer() {
        binding.btnDrawerMenu.setOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Sağ üstteki Taç ikonu: Kişisel reklamsız sürüm bilgisi & Hızlı Tema Değiştirici (Açık / Koyu / AMOLED)
        binding.btnThemeQuickSwitch.setOnClickListener {
            showThemePickerDialog()
        }

        // Sağ üstteki Üç Nokta (⋮) menüsü: Orijinal list_desktop.xml
        binding.btnMoreMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "Tüm Bulut Hesaplarında Ara")
            popup.menu.add(0, 2, 1, getString(R.string.menu_analyze))
            popup.menu.add(0, 3, 2, "Tema Seç (Açık / Koyu / AMOLED)")
            popup.menu.add(0, 4, 3, getString(R.string.menu_refresh))
            popup.menu.add(0, 5, 4, getString(R.string.settings_title))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        startActivity(Intent(this, CloudAccountsActivity::class.java).apply {
                            putExtra("FOCUS_UNIFIED_SEARCH", true)
                        })
                    }
                    2 -> startActivity(Intent(this, StorageAnalysisActivity::class.java))
                    3 -> showThemePickerDialog()
                    4 -> refreshHomeDashboard()
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            }
            popup.show()
        }

        binding.swipeRefreshHome.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_teal))
        binding.swipeRefreshHome.setOnRefreshListener {
            refreshHomeDashboard()
        }

        drawerAdapter = DrawerNavAdapter(emptyList()) { entry ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (entry.id) {
                "HOME" -> { /* Zaten Ana Ekrandayız */ }
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
                    when (val payload = entry.payload) {
                        is CloudAccount -> {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "CLOUD")
                                putExtra("CLOUD_ACCOUNT", payload)
                                putExtra("TITLE", payload.provider.displayName)
                            })
                        }
                        is RemoteAccount -> {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "REMOTE")
                                putExtra("REMOTE_ACCOUNT", payload)
                                putExtra("TITLE", payload.displayName)
                            })
                        }
                    }
                }
            }
        }

        binding.rvDrawerItems.layoutManager = LinearLayoutManager(this)
        binding.rvDrawerItems.adapter = drawerAdapter

        binding.tabDrawerLocations.setOnClickListener {
            activeDrawerTab = 0
            updateDrawerTabIndicators()
            refreshDrawerEntries()
        }
        binding.tabDrawerBookmarks.setOnClickListener {
            activeDrawerTab = 1
            updateDrawerTabIndicators()
            refreshDrawerEntries()
        }
        binding.tabDrawerHistory.setOnClickListener {
            activeDrawerTab = 2
            updateDrawerTabIndicators()
            refreshDrawerEntries()
        }

        binding.btnDrawerThemeFooter.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showThemePickerDialog()
        }
    }

    private fun updateDrawerTabIndicators() {
        binding.indTabLocations.visibility = if (activeDrawerTab == 0) View.VISIBLE else View.INVISIBLE
        binding.indTabBookmarks.visibility = if (activeDrawerTab == 1) View.VISIBLE else View.INVISIBLE
        binding.indTabHistory.visibility = if (activeDrawerTab == 2) View.VISIBLE else View.INVISIBLE
    }

    private fun setupHomeGrid() {
        homeGridAdapter = HomeGridAdapter(emptyList()) { category ->
            onHomeCategoryClicked(category)
        }
        updateGridSpanCount()
        binding.rvHomeCategories.adapter = homeGridAdapter
        binding.rvHomeCategories.setHasFixedSize(false)
    }

    private fun updateGridSpanCount() {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        // Orijinal APK'daki (00:00) gibi yatay/tablet ekranda 6 sütun, dikey telefonda 3 veya 4 sütun
        val spans = when {
            widthDp >= 640f -> 6
            widthDp >= 420f -> 4
            else -> 3
        }
        binding.rvHomeCategories.layoutManager = GridLayoutManager(this, spans)
    }

    private fun onHomeCategoryClicked(item: HomeCategoryItem) {
        when (item.type) {
            HomeCategoryType.MAIN_STORAGE -> {
                openExplorer(LocalFileRepository.rootStorageDir.absolutePath, "Ana bellek")
            }
            HomeCategoryType.SD_CARD, HomeCategoryType.USB_STORAGE -> {
                openExplorer(LocalFileRepository.rootStorageDir.absolutePath, item.title)
            }
            HomeCategoryType.DOWNLOADS -> {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dl.exists()) dl.mkdirs()
                openExplorer(dl.absolutePath, "İndirilenler")
            }
            HomeCategoryType.STORAGE_ANALYSIS -> {
                startActivity(Intent(this, StorageAnalysisActivity::class.java))
            }
            HomeCategoryType.IMAGES -> openCategoryExplorer("IMAGES", "Görüntüler", preferGrid = true)
            HomeCategoryType.AUDIO -> openCategoryExplorer("AUDIO", "Ses", preferGrid = false)
            HomeCategoryType.VIDEOS -> openCategoryExplorer("VIDEOS", "Videolar", preferGrid = true)
            HomeCategoryType.DOCUMENTS -> openCategoryExplorer("DOCUMENTS", "Belgeler", preferGrid = false)
            HomeCategoryType.APPS -> startActivity(Intent(this, AppsManagerActivity::class.java))
            HomeCategoryType.NEW_FILES -> openCategoryExplorer("NEW_FILES", "Yeni Dosyalar", preferGrid = false)
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

    private fun openCategoryExplorer(categoryKey: String, title: String, preferGrid: Boolean = false) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "CATEGORY")
            putExtra("CATEGORY", categoryKey)
            putExtra("TITLE", title)
            putExtra("PREFER_GRID", preferGrid)
        })
    }

    private fun refreshHomeDashboard() {
        val overview = LocalFileRepository.getMainStorageOverview()
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dlFiles = dlDir.listFiles().orEmpty()
        val dlSize = dlFiles.filter { it.isFile }.sumOf { it.length() }

        val cloudCount = CloudManager.getAccounts(this).size
        val remoteCount = RemoteManager.getAccounts(this).size

        // Orijinal APK ikonları (Ana bellek -> icon_main_storage, İndirilenler -> icon_folder_download, Bellek Analizi -> PieProgressView)
        val initialItems = listOf(
            HomeCategoryItem(
                type = HomeCategoryType.MAIN_STORAGE,
                title = getString(R.string.cat_main_storage),
                subtitle = "${FormatUtils.formatSize(overview.usedBytes)} / ${FormatUtils.formatSize(overview.totalBytes)}",
                iconRes = R.drawable.icon_main_storage,
                usedBytes = overview.usedBytes,
                totalBytes = overview.totalBytes,
                showStorageBar = true
            ),
            HomeCategoryItem(
                type = HomeCategoryType.DOWNLOADS,
                title = getString(R.string.cat_downloads),
                subtitle = "${FormatUtils.formatSize(dlSize)} (${dlFiles.size})",
                iconRes = R.drawable.icon_folder_download
            ),
            HomeCategoryItem(
                type = HomeCategoryType.STORAGE_ANALYSIS,
                title = getString(R.string.cat_storage_analysis),
                subtitle = getString(R.string.storage_used_format, overview.usedPercent),
                iconRes = R.drawable.icon_analysis,
                showPieChart = true,
                piePercent = overview.usedPercent
            ),
            HomeCategoryItem(HomeCategoryType.IMAGES, "Görüntüler", "0 B (0)", R.drawable.ic_cat_images),
            HomeCategoryItem(HomeCategoryType.AUDIO, "Ses", "0 B (0)", R.drawable.ic_cat_audio),
            HomeCategoryItem(HomeCategoryType.VIDEOS, "Videolar", "0 B (0)", R.drawable.ic_cat_videos),
            HomeCategoryItem(HomeCategoryType.DOCUMENTS, "Belgeler", "0 B (0)", R.drawable.ic_cat_docs),
            HomeCategoryItem(HomeCategoryType.APPS, "Uygulamalar", "0 B (0)", R.drawable.ic_cat_apps),
            HomeCategoryItem(HomeCategoryType.NEW_FILES, "Yeni Dosyalar", "0 B (0)", R.drawable.ic_cat_new_files),
            HomeCategoryItem(HomeCategoryType.CLOUD, "Bulut", "($cloudCount)", R.drawable.ic_cat_cloud),
            HomeCategoryItem(HomeCategoryType.REMOTE, "Uzak", "($remoteCount)", R.drawable.ic_cat_remote),
            HomeCategoryItem(HomeCategoryType.NETWORK_ACCESS, "Ağdan erişim", "", R.drawable.ic_cat_network)
        )
        homeGridAdapter.updateData(initialItems)

        lifecycleScope.launch {
            val report = LocalFileRepository.analyzeStorage(this@MainActivity)
            val appsCount = withContext(Dispatchers.IO) {
                LocalFileRepository.getInstalledApps(this@MainActivity, includeSystemApps = false)
            }
            val appsBytes = appsCount.sumOf { it.apkSize }

            val newFilesList = LocalFileRepository.getCategoryFiles("NEW_FILES")
            val newFilesBytes = newFilesList.sumOf { it.size }

            val updatedItems = listOf(
                HomeCategoryItem(
                    type = HomeCategoryType.MAIN_STORAGE,
                    title = getString(R.string.cat_main_storage),
                    subtitle = "${FormatUtils.formatSize(overview.usedBytes)} / ${FormatUtils.formatSize(overview.totalBytes)}",
                    iconRes = R.drawable.icon_main_storage,
                    usedBytes = overview.usedBytes,
                    totalBytes = overview.totalBytes,
                    showStorageBar = true
                ),
                HomeCategoryItem(
                    type = HomeCategoryType.DOWNLOADS,
                    title = getString(R.string.cat_downloads),
                    subtitle = "${FormatUtils.formatSize(dlSize)} (${dlFiles.size})",
                    iconRes = R.drawable.icon_folder_download
                ),
                HomeCategoryItem(
                    type = HomeCategoryType.STORAGE_ANALYSIS,
                    title = getString(R.string.cat_storage_analysis),
                    subtitle = getString(R.string.storage_used_format, overview.usedPercent),
                    iconRes = R.drawable.icon_analysis,
                    showPieChart = true,
                    piePercent = overview.usedPercent
                ),
                HomeCategoryItem(
                    HomeCategoryType.IMAGES,
                    "Görüntüler",
                    "${FormatUtils.formatSize(report.imagesBytes)} (${report.imagesCount})",
                    R.drawable.ic_cat_images
                ),
                HomeCategoryItem(
                    HomeCategoryType.AUDIO,
                    "Ses",
                    "${FormatUtils.formatSize(report.audioBytes)} (${report.audioCount})",
                    R.drawable.ic_cat_audio
                ),
                HomeCategoryItem(
                    HomeCategoryType.VIDEOS,
                    "Videolar",
                    "${FormatUtils.formatSize(report.videosBytes)} (${report.videosCount})",
                    R.drawable.ic_cat_videos
                ),
                HomeCategoryItem(
                    HomeCategoryType.DOCUMENTS,
                    "Belgeler",
                    "${FormatUtils.formatSize(report.docsBytes)} (${report.docsCount})",
                    R.drawable.ic_cat_docs
                ),
                HomeCategoryItem(
                    HomeCategoryType.APPS,
                    "Uygulamalar",
                    "${FormatUtils.formatSize(appsBytes)} (${appsCount.size})",
                    R.drawable.ic_cat_apps
                ),
                HomeCategoryItem(
                    HomeCategoryType.NEW_FILES,
                    "Yeni Dosyalar",
                    "${FormatUtils.formatSize(newFilesBytes)} (${newFilesList.size})",
                    R.drawable.ic_cat_new_files
                ),
                HomeCategoryItem(HomeCategoryType.CLOUD, "Bulut", "($cloudCount)", R.drawable.ic_cat_cloud),
                HomeCategoryItem(HomeCategoryType.REMOTE, "Uzak", "($remoteCount)", R.drawable.ic_cat_remote),
                HomeCategoryItem(HomeCategoryType.NETWORK_ACCESS, "Ağdan erişim", "", R.drawable.ic_cat_network)
            )
            homeGridAdapter.updateData(updatedItems)
            binding.swipeRefreshHome.isRefreshing = false
        }
    }

    /**
     * Orijinal Sol Çekmece (00:05 ve 01:55):
     * - Ana Ekran (Kırmızı çatılı ev ikonu: icon_home)
     * - Ana bellek (Disk ikonu: icon_main_storage_s + sağda %64)
     * - Geri Dönüşüm Kutusu (Çöp kovası ikonu: ic_recycle_bin + sağda 0 B)
     * - İndirilenler (Klasör ikonu: icon_folder_download_s)
     * - Videolar / Bulut Hesapları (cloud_icon_googledrive)
     */
    private fun refreshDrawerEntries() {
        val overview = LocalFileRepository.getMainStorageOverview()
        val usedPct = overview.usedPercent
        val recycleBytes = LocalFileRepository.recycleBinDir.listFiles().orEmpty().sumOf { it.length() }

        val entries = mutableListOf(
            DrawerEntry("HOME", "Ana Ekran", iconRes = R.drawable.icon_home),
            DrawerEntry("MAIN_STORAGE", "Ana bellek", rightBadge = "$usedPct%", iconRes = R.drawable.icon_main_storage_s),
            DrawerEntry("RECYCLE_BIN", "Geri Dönüşüm Kutusu", rightBadge = FormatUtils.formatSize(recycleBytes), iconRes = R.drawable.ic_recycle_bin),
            DrawerEntry("DOWNLOADS", "İndirilenler", rightBadge = "⋮", iconRes = R.drawable.icon_folder_download_s),
            DrawerEntry("VIDEOS", "Videolar", rightBadge = "⋮", iconRes = R.drawable.icon_file_video_s)
        )

        val clouds = CloudManager.getAccounts(this)
        for (acc in clouds) {
            val icon = when (acc.provider) {
                com.alphainventor.filemanager.model.CloudProviderType.GOOGLE_DRIVE -> R.drawable.cloud_icon_googledrive
                com.alphainventor.filemanager.model.CloudProviderType.DROPBOX -> R.drawable.cloud_icon_dropbox
                com.alphainventor.filemanager.model.CloudProviderType.ONEDRIVE -> R.drawable.cloud_icon_onedrive
                com.alphainventor.filemanager.model.CloudProviderType.BOX -> R.drawable.cloud_icon_box
                com.alphainventor.filemanager.model.CloudProviderType.NEXTCLOUD -> R.drawable.ic_cloud_nextcloud
                com.alphainventor.filemanager.model.CloudProviderType.YANDEX -> R.drawable.cloud_icon_yandex
            }
            entries.add(
                DrawerEntry(
                    id = "CLOUD_${acc.id}",
                    title = "${acc.provider.displayName} (${acc.emailOrName})",
                    rightBadge = "⋮",
                    iconRes = icon,
                    payload = acc
                )
            )
        }

        val remotes = RemoteManager.getAccounts(this)
        for (rem in remotes) {
            entries.add(
                DrawerEntry(
                    id = "REMOTE_${rem.id}",
                    title = "${rem.protocol.displayName} (${rem.displayName})",
                    rightBadge = "⋮",
                    iconRes = R.drawable.icon_folder_lan_s,
                    payload = rem
                )
            )
        }

        drawerAdapter.updateEntries(entries)
    }

    private fun showThemePickerDialog() {
        val currentMode = ThemeManager.getThemeMode(this)
        val modes = AppThemeMode.entries.toTypedArray()
        val labels = modes.map { it.titleTr }.toTypedArray()
        val checkedIdx = modes.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Tema Seçin")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                val chosen = modes[which]
                ThemeManager.setThemeMode(this, chosen)
                applyCurrentTheme()
                homeGridAdapter.notifyDataSetChanged()
                drawerAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun applyCurrentTheme() {
        val mode = ThemeManager.getThemeMode(this)
        ThemeManager.applyWindowColors(this, binding.mainContentRoot, binding.toolbarMain)
        binding.drawerContainer.setBackgroundColor(ThemeManager.getSurfaceColor(this))
        binding.drawerHeaderTabs.setBackgroundColor(ThemeManager.getSurfaceColor(this))
        binding.tvCurrentThemeBadge.text = when (mode) {
            AppThemeMode.LIGHT -> "TEMA: AÇIK (DÜZENLE)"
            AppThemeMode.DARK -> "TEMA: KOYU (DÜZENLE)"
            AppThemeMode.AMOLED -> "TEMA: AMOLED (DÜZENLE)"
        }
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_title))
                    .setMessage(getString(R.string.perm_desc))
                    .setPositiveButton(getString(R.string.perm_grant)) { _, _ ->
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("Daha Sonra", null)
                    .show()
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 1001)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPressMs = now
            Toast.makeText(this, "Çıkmak için tekrar geri düğmesine basın", Toast.LENGTH_SHORT).show()
        }
    }
}
