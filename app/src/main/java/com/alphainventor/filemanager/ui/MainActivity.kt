package com.alphainventor.filemanager.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
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
import com.alphainventor.filemanager.core.DrawerBookmarkEntry
import com.alphainventor.filemanager.core.DrawerHistoryEntry
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.core.RemovableStorageVolume
import com.alphainventor.filemanager.databinding.ActivityMainBinding
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.FileItem
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
import com.alphainventor.filemanager.viewer.ImageViewerActivity
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.TextEditorActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Ana Ekranı:
 * - Dikey telefonda 3 sütunlu büyük ve net simgeler (74dp), yatay/tablette 6 sütun
 * - Ana bellek + Takılıysa SD kart (101 GB / 128 GB) + İndirilenler + Geri Dönüşüm Kutusu + Bellek Analizi
 * - Görüntüler, Ses, Videolar, Belgeler, Uygulamalar, Yeni Dosyalar, Bulut, Uzak, Ağdan erişim
 * - Sol Çekmece (☰):
 *   1) Konumlar (📁): Ana Ekran, Ana bellek, SD kart, Geri Dönüşüm Kutusu, İndirilenler, Videolar, Bulut Hesapları
 *   2) Yer İmleri / Favoriler (★): Kamera, İndirilenler, Filmler, Müzik, Resimler + eklenen favoriler ve altta "DÜZENLE"
 *   3) Geçmiş (🕒): Son açılan videolar, resimler, klasörler ve altta "SİL"
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeGridAdapter: HomeGridAdapter
    private lateinit var drawerAdapter: DrawerNavAdapter

    private var activeDrawerTab: Int = 0
    private var lastBackPressMs: Long = 0L

    private val dashCachePrefs by lazy {
        getSharedPreferences("HomeDashboardQuickCache", Context.MODE_PRIVATE)
    }

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
                refreshDrawerEntries()
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        binding.btnThemeQuickSwitch.setOnClickListener {
            showThemePickerDialog()
        }

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
            handleDrawerEntryClick(entry)
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
            when (activeDrawerTab) {
                0 -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    showThemePickerDialog()
                }
                1 -> {
                    showManageBookmarksDialog()
                }
                2 -> {
                    LocalFileRepository.clearHistory(this)
                    refreshDrawerEntries()
                    Toast.makeText(this, "Geçmiş temizlendi.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleDrawerEntryClick(entry: DrawerEntry) {
        when (entry.id) {
            "HOME" -> { /* Ana Ekrandayız */ }
            "MAIN_STORAGE" -> openExplorer(LocalFileRepository.rootStorageDir.absolutePath, "Ana bellek")
            "RECYCLE_BIN" -> openExplorer(LocalFileRepository.recycleBinDir.absolutePath, "Geri Dönüşüm Kutusu")
            "NEW_FILES" -> openCategoryExplorer("NEW_FILES", "Yeni Dosyalar")
            "DOCUMENTS" -> openCategoryExplorer("DOCUMENTS", "Belgeler")
            "IMAGES" -> openCategoryExplorer("IMAGES", "Görüntüler", preferGrid = true)
            "VIDEOS" -> openCategoryExplorer("VIDEOS", "Videolar", preferGrid = true)
            "AUDIO" -> openCategoryExplorer("AUDIO", "Ses", preferGrid = true)
            "DOWNLOADS" -> {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                openExplorer(dl.absolutePath, "İndirilenler")
            }
            "EMPTY_HISTORY", "EMPTY_BOOKMARKS" -> { }
            else -> {
                when (val payload = entry.payload) {
                    is RemovableStorageVolume -> {
                        openExplorer(payload.path, payload.title)
                    }
                    is DrawerBookmarkEntry -> {
                        val f = File(payload.path)
                        if (!f.exists()) f.mkdirs()
                        if (payload.isDirectory || f.isDirectory) {
                            openExplorer(payload.path, payload.title)
                        } else {
                            openLocalFileDirectly(f)
                        }
                    }
                    is DrawerHistoryEntry -> {
                        val f = File(payload.path)
                        if (payload.isDirectory || f.isDirectory) {
                            openExplorer(payload.path, payload.title)
                        } else if (f.exists()) {
                            openLocalFileDirectly(f)
                        } else {
                            Toast.makeText(this, "Dosya bulunamadı: ${payload.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
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

    private fun openLocalFileDirectly(f: File) {
        val fi = FileItem(
            id = f.absolutePath,
            name = f.name,
            path = f.absolutePath,
            isDirectory = f.isDirectory,
            size = f.length(),
            lastModified = f.lastModified()
        )
        when {
            fi.isVideo -> startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("VIDEO_URL", f.absolutePath)
                putExtra("VIDEO_TITLE", f.name)
            })
            fi.isAudio -> startActivity(Intent(this, MusicPlayerActivity::class.java).apply {
                putExtra("AUDIO_URL", f.absolutePath)
                putExtra("AUDIO_TITLE", f.name)
            })
            fi.isImage -> startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("IMAGE_PATH", f.absolutePath)
                putExtra("IMAGE_TITLE", f.name)
            })
            else -> startActivity(Intent(this, TextEditorActivity::class.java).apply {
                putExtra("FILE_PATH", f.absolutePath)
                putExtra("FILE_TITLE", f.name)
            })
        }
    }

    private fun updateDrawerTabIndicators() {
        val activeColor = Color.parseColor("#26A69A")
        val inactiveColor = ThemeManager.getSecondaryTextColor(this)

        binding.indTabLocations.visibility = if (activeDrawerTab == 0) View.VISIBLE else View.INVISIBLE
        binding.indTabBookmarks.visibility = if (activeDrawerTab == 1) View.VISIBLE else View.INVISIBLE
        binding.indTabHistory.visibility = if (activeDrawerTab == 2) View.VISIBLE else View.INVISIBLE

        binding.ivTabLocations.setColorFilter(if (activeDrawerTab == 0) activeColor else inactiveColor)
        binding.ivTabBookmarks.setColorFilter(if (activeDrawerTab == 1) activeColor else inactiveColor)
        binding.ivTabHistory.setColorFilter(if (activeDrawerTab == 2) activeColor else inactiveColor)

        when (activeDrawerTab) {
            0 -> {
                binding.ivDrawerFooterIcon.visibility = View.VISIBLE
                val mode = ThemeManager.getThemeMode(this)
                binding.tvCurrentThemeBadge.text = when (mode) {
                    AppThemeMode.LIGHT -> "TEMA: AÇIK (DÜZENLE)"
                    AppThemeMode.DARK -> "TEMA: KOYU (DÜZENLE)"
                    AppThemeMode.AMOLED -> "TEMA: AMOLED (DÜZENLE)"
                }
            }
            1 -> {
                binding.ivDrawerFooterIcon.visibility = View.GONE
                binding.tvCurrentThemeBadge.text = "DÜZENLE"
            }
            2 -> {
                binding.ivDrawerFooterIcon.visibility = View.GONE
                binding.tvCurrentThemeBadge.text = "SİL"
            }
        }
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
        // Orijinal APK'daki (istediğimiz gibi...mp4 00:01) gibi dikey telefonda 3 sütun, yatay/tablette 6 sütun
        val spans = when {
            widthDp >= 580f -> 6
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
                val targetPath = item.customPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
                openExplorer(targetPath, item.title)
            }
            HomeCategoryType.DOWNLOADS -> {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dl.exists()) dl.mkdirs()
                openExplorer(dl.absolutePath, "İndirilenler")
            }
            HomeCategoryType.RECYCLE_BIN -> {
                openExplorer(LocalFileRepository.recycleBinDir.absolutePath, "Geri Dönüşüm Kutusu")
            }
            HomeCategoryType.STORAGE_ANALYSIS -> {
                startActivity(Intent(this, StorageAnalysisActivity::class.java))
            }
            HomeCategoryType.IMAGES -> openCategoryExplorer("IMAGES", "Görüntüler", preferGrid = true)
            HomeCategoryType.AUDIO -> openCategoryExplorer("AUDIO", "Ses", preferGrid = true)
            HomeCategoryType.VIDEOS -> openCategoryExplorer("VIDEOS", "Videolar", preferGrid = true)
            HomeCategoryType.DOCUMENTS -> openCategoryExplorer("DOCUMENTS", "Belgeler", preferGrid = true)
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

    private fun openCategoryExplorer(categoryKey: String, title: String, preferGrid: Boolean = true) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "CATEGORY")
            putExtra("CATEGORY", categoryKey)
            putExtra("TITLE", title)
            putExtra("PREFER_GRID", preferGrid)
        })
    }

    private fun buildHomeItemsList(
        overview: com.alphainventor.filemanager.core.StorageOverview,
        removableVolumes: List<RemovableStorageVolume>,
        dlSize: Long,
        dlCount: Int,
        recycleSize: Long,
        recycleCount: Int,
        imgSub: String,
        audSub: String,
        vidSub: String,
        docSub: String,
        appSub: String,
        newSub: String,
        cloudCount: Int,
        remoteCount: Int
    ): List<HomeCategoryItem> {
        val list = ArrayList<HomeCategoryItem>()

        // 1. Ana bellek
        list.add(
            HomeCategoryItem(
                type = HomeCategoryType.MAIN_STORAGE,
                title = getString(R.string.cat_main_storage),
                subtitle = "${FormatUtils.formatSize(overview.usedBytes)} / ${FormatUtils.formatSize(overview.totalBytes)}",
                iconRes = R.drawable.icon_main_storage,
                usedBytes = overview.usedBytes,
                totalBytes = overview.totalBytes,
                showStorageBar = true,
                customPath = LocalFileRepository.rootStorageDir.absolutePath
            )
        )

        // 2. Takılıysa SD kart / USB bellek (videoda 00:01'de Ana bellek'in hemen yanında)
        for (vol in removableVolumes) {
            list.add(
                HomeCategoryItem(
                    type = if (vol.isUsb) HomeCategoryType.USB_STORAGE else HomeCategoryType.SD_CARD,
                    title = vol.title,
                    subtitle = "${FormatUtils.formatSize(vol.overview.usedBytes)} / ${FormatUtils.formatSize(vol.overview.totalBytes)}",
                    iconRes = if (vol.isUsb) R.drawable.icon_usb_storage else R.drawable.icon_sd,
                    usedBytes = vol.overview.usedBytes,
                    totalBytes = vol.overview.totalBytes,
                    showStorageBar = true,
                    customPath = vol.path
                )
            )
        }

        // 3. İndirilenler
        list.add(
            HomeCategoryItem(
                type = HomeCategoryType.DOWNLOADS,
                title = getString(R.string.cat_downloads),
                subtitle = "${FormatUtils.formatSize(dlSize)} ($dlCount)",
                iconRes = R.drawable.icon_folder_download
            )
        )

        // 4. Geri Dönüşüm Kutusu (videoda 00:01'de İndirilenler'in yanında)
        list.add(
            HomeCategoryItem(
                type = HomeCategoryType.RECYCLE_BIN,
                title = "Geri Dönüşüm Kutusu",
                subtitle = if (recycleCount > 0) "${FormatUtils.formatSize(recycleSize)} ($recycleCount)" else FormatUtils.formatSize(recycleSize),
                iconRes = R.drawable.ic_recycle_bin
            )
        )

        // 5. Bellek Analizi
        list.add(
            HomeCategoryItem(
                type = HomeCategoryType.STORAGE_ANALYSIS,
                title = getString(R.string.cat_storage_analysis),
                subtitle = getString(R.string.storage_used_format, overview.usedPercent),
                iconRes = R.drawable.icon_analysis,
                showPieChart = true,
                piePercent = overview.usedPercent
            )
        )

        // 6..14 Medya ve Ağ Kategorileri
        list.add(HomeCategoryItem(HomeCategoryType.IMAGES, "Görüntüler", imgSub, R.drawable.ic_cat_images))
        list.add(HomeCategoryItem(HomeCategoryType.AUDIO, "Ses", audSub, R.drawable.ic_cat_audio))
        list.add(HomeCategoryItem(HomeCategoryType.VIDEOS, "Videolar", vidSub, R.drawable.ic_cat_videos))
        list.add(HomeCategoryItem(HomeCategoryType.DOCUMENTS, "Belgeler", docSub, R.drawable.ic_cat_docs))
        list.add(HomeCategoryItem(HomeCategoryType.APPS, "Uygulamalar", appSub, R.drawable.ic_cat_apps))
        list.add(HomeCategoryItem(HomeCategoryType.NEW_FILES, "Yeni Dosyalar", newSub, R.drawable.ic_cat_new_files))
        list.add(HomeCategoryItem(HomeCategoryType.CLOUD, "Bulut", "($cloudCount)", R.drawable.ic_cat_cloud))
        list.add(HomeCategoryItem(HomeCategoryType.REMOTE, "Uzak", "($remoteCount)", R.drawable.ic_cat_remote))
        list.add(HomeCategoryItem(HomeCategoryType.NETWORK_ACCESS, "Ağdan erişim", "", R.drawable.ic_cat_network))

        return list
    }

    private fun refreshHomeDashboard() {
        val overview = LocalFileRepository.getMainStorageOverview()
        val removableVolumes = LocalFileRepository.getRemovableStorageVolumes(this)
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dlFiles = dlDir.listFiles().orEmpty()
        val dlSize = dlFiles.filter { it.isFile }.sumOf { it.length() }

        val recycleFiles = LocalFileRepository.recycleBinDir.listFiles().orEmpty()
        val recycleSize = recycleFiles.sumOf { it.length() }

        val cloudCount = CloudManager.getAccounts(this).size
        val remoteCount = RemoteManager.getAccounts(this).size

        // Önbellekteki son değerleri ilk karede hemen göster (0 B (0) yanıp sönmesini önler)
        val cachedItems = buildHomeItemsList(
            overview = overview,
            removableVolumes = removableVolumes,
            dlSize = dlSize,
            dlCount = dlFiles.size,
            recycleSize = recycleSize,
            recycleCount = recycleFiles.size,
            imgSub = dashCachePrefs.getString("img", "0 B (0)") ?: "0 B (0)",
            audSub = dashCachePrefs.getString("aud", "0 B (0)") ?: "0 B (0)",
            vidSub = dashCachePrefs.getString("vid", "0 B (0)") ?: "0 B (0)",
            docSub = dashCachePrefs.getString("doc", "0 B (0)") ?: "0 B (0)",
            appSub = dashCachePrefs.getString("app", "0 B (0)") ?: "0 B (0)",
            newSub = dashCachePrefs.getString("new", "0 B (0)") ?: "0 B (0)",
            cloudCount = cloudCount,
            remoteCount = remoteCount
        )
        homeGridAdapter.updateData(cachedItems)

        // MediaStore üzerinden <100ms içinde gerçek sayıları ve boyutları güncelle
        lifecycleScope.launch {
            val quickStats = LocalFileRepository.getQuickDashboardStats(this@MainActivity)
            val appsList = withContext(Dispatchers.IO) {
                LocalFileRepository.getInstalledApps(this@MainActivity, includeSystemApps = false)
            }
            val appsBytes = appsList.sumOf { it.apkSize }

            val imgSub = "${FormatUtils.formatSize(quickStats.images.totalBytes)} (${quickStats.images.count})"
            val audSub = "${FormatUtils.formatSize(quickStats.audio.totalBytes)} (${quickStats.audio.count})"
            val vidSub = "${FormatUtils.formatSize(quickStats.videos.totalBytes)} (${quickStats.videos.count})"
            val docSub = "${FormatUtils.formatSize(quickStats.docs.totalBytes)} (${quickStats.docs.count})"
            val appSub = "${FormatUtils.formatSize(appsBytes)} (${appsList.size})"
            val newSub = "${FormatUtils.formatSize(quickStats.newFiles.totalBytes)} (${quickStats.newFiles.count})"

            dashCachePrefs.edit()
                .putString("img", imgSub)
                .putString("aud", audSub)
                .putString("vid", vidSub)
                .putString("doc", docSub)
                .putString("app", appSub)
                .putString("new", newSub)
                .apply()

            val updatedItems = buildHomeItemsList(
                overview = overview,
                removableVolumes = LocalFileRepository.getRemovableStorageVolumes(this@MainActivity),
                dlSize = dlSize,
                dlCount = dlFiles.size,
                recycleSize = recycleSize,
                recycleCount = recycleFiles.size,
                imgSub = imgSub,
                audSub = audSub,
                vidSub = vidSub,
                docSub = docSub,
                appSub = appSub,
                newSub = newSub,
                cloudCount = cloudCount,
                remoteCount = remoteCount
            )
            homeGridAdapter.updateData(updatedItems)
            binding.swipeRefreshHome.isRefreshing = false
        }
    }

    /**
     * Sol Çekmece (☰) 3 Sekme İçeriği:
     * - activeDrawerTab == 0: Konumlar (Ana Ekran, Ana bellek, SD kart, Geri Dönüşüm Kutusu, İndirilenler, Videolar, Bulut Hesapları)
     * - activeDrawerTab == 1: Yer İmleri / Favoriler (★) + altta DÜZENLE
     * - activeDrawerTab == 2: Geçmiş (🕒) + altta SİL
     */
    private fun refreshDrawerEntries() {
        updateDrawerTabIndicators()
        when (activeDrawerTab) {
            0 -> {
                val overview = LocalFileRepository.getMainStorageOverview()
                val usedPct = overview.usedPercent
                val recycleBytes = LocalFileRepository.recycleBinDir.listFiles().orEmpty().sumOf { it.length() }

                val entries = mutableListOf(
                    DrawerEntry("HOME", "Ana Ekran", iconRes = R.drawable.icon_home),
                    DrawerEntry("MAIN_STORAGE", "Ana bellek", rightBadge = "$usedPct%", iconRes = R.drawable.icon_main_storage_s)
                )

                // Takılı SD kartları ekle
                for (vol in LocalFileRepository.getRemovableStorageVolumes(this)) {
                    entries.add(
                        DrawerEntry(
                            id = "SD_${vol.path}",
                            title = vol.title,
                            rightBadge = "${vol.overview.usedPercent}%",
                            iconRes = if (vol.isUsb) R.drawable.icon_usb_storage_s else R.drawable.icon_sd_s,
                            payload = vol
                        )
                    )
                }

                entries.add(DrawerEntry("RECYCLE_BIN", "Geri Dönüşüm Kutusu", rightBadge = FormatUtils.formatSize(recycleBytes), iconRes = R.drawable.ic_recycle_bin))
                entries.add(DrawerEntry("VIDEOS", "Videolar", rightBadge = "⋮", iconRes = R.drawable.icon_file_video_s))
                entries.add(DrawerEntry("DOWNLOADS", "İndirilenler", rightBadge = "⋮", iconRes = R.drawable.icon_folder_download_s))

                val clouds = CloudManager.getAccounts(this)
                for (acc in clouds) {
                    val icon = when (acc.provider) {
                        com.alphainventor.filemanager.model.CloudProviderType.GOOGLE_DRIVE -> R.drawable.cloud_icon_googledrive
                        com.alphainventor.filemanager.model.CloudProviderType.MEGA -> R.drawable.ic_cloud_mega
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
            1 -> {
                val bookmarks = LocalFileRepository.getBookmarks(this)
                val entries = if (bookmarks.isEmpty()) {
                    listOf(DrawerEntry("EMPTY_BOOKMARKS", "Henüz yer imi yok", iconRes = R.drawable.icon_folder_empty_s))
                } else {
                    bookmarks.mapIndexed { idx, bm ->
                        val lower = bm.title.lowercase()
                        val icon = when {
                            lower.contains("kamera") || lower.contains("camera") -> R.drawable.icon_folder_camera_s
                            lower.contains("indir") || lower.contains("download") -> R.drawable.icon_folder_download_s
                            !bm.isDirectory -> R.drawable.icon_file_document_s
                            else -> R.drawable.icon_folder_full_s
                        }
                        DrawerEntry(
                            id = "BM_$idx",
                            title = bm.title,
                            subtitle = bm.path,
                            rightBadge = "⋮",
                            iconRes = icon,
                            payload = bm
                        )
                    }
                }
                drawerAdapter.updateEntries(entries)
            }
            2 -> {
                val history = LocalFileRepository.getHistory(this)
                val entries = if (history.isEmpty()) {
                    listOf(DrawerEntry("EMPTY_HISTORY", "Geçmiş kaydı yok", iconRes = R.drawable.ic_history_clock))
                } else {
                    history.mapIndexed { idx, h ->
                        val ext = h.title.substringAfterLast('.', "").lowercase()
                        val icon = when {
                            h.isDirectory -> R.drawable.icon_folder_full_s
                            ext in FileItem.VIDEO_EXTENSIONS -> R.drawable.icon_file_video_s
                            ext in FileItem.IMAGE_EXTENSIONS -> R.drawable.icon_file_image_s
                            ext in FileItem.AUDIO_EXTENSIONS -> R.drawable.icon_file_music_s
                            else -> R.drawable.icon_file_document_s
                        }
                        DrawerEntry(
                            id = "HIST_$idx",
                            title = h.title,
                            subtitle = h.section,
                            rightBadge = FormatUtils.formatDate(h.timestamp),
                            iconRes = icon,
                            payload = h
                        )
                    }
                }
                drawerAdapter.updateEntries(entries)
            }
        }
    }

    private fun showManageBookmarksDialog() {
        val bookmarks = LocalFileRepository.getBookmarks(this)
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "Herhangi bir klasöre uzun basıp 'Yer İmi Ekle' diyerek favori ekleyebilirsiniz.", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = bookmarks.map { "${it.title} (${it.path.substringAfterLast('/')})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Yer İmlerini Düzenle (Silmek için dokunun)")
            .setItems(titles) { _, which ->
                val target = bookmarks[which]
                LocalFileRepository.removeBookmark(this, target.path)
                refreshDrawerEntries()
                Toast.makeText(this, "Yer imi kaldırıldı: ${target.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showThemePickerDialog() {
        val currentMode = ThemeManager.getThemeMode(this)
        val modes = AppThemeMode.values()
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
        ThemeManager.applyWindowColors(this, binding.mainContentRoot, binding.toolbarMain, binding.drawerContainer)
        binding.drawerContainer.setBackgroundColor(ThemeManager.getSurfaceColor(this))
        binding.drawerHeaderTabs.setBackgroundColor(ThemeManager.getCardColor(this))
        updateDrawerTabIndicators()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val mediaPerms = arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, mediaPerms, 1002)
                }
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
