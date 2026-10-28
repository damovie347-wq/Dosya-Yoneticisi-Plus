package com.alphainventor.filemanager.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.core.InstalledAppItem
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.core.StorageAnalysisReport
import com.alphainventor.filemanager.databinding.ActivityStorageAnalysisBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.viewer.ImageViewerActivity
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bellek Analizi Ekranı (card_analysis_type_size.xml & card_files.xml):
 * - MediaStore + paralel klasör analizi ile <1 saniyede tamamlanır.
 * - Ana bellek ve varsa SD kart arasında üst sekme ile geçiş yapar.
 * - 1) Kategori Dağılımı Kartı (Görüntüler, Ses, Videolar, Belgeler, Uygulamalar, Diğerleri — tıklanınca ilgili kategoriyi açar)
 * - 2) En Çok Yer Kaplayan Klasörler Kartı (Klasör adı, doluluk çubuğu, boyut, tıklayınca klasörü açma ve seçerek silme)
 * - 3) Büyük Dosyalar Kartı (Videolar/dosyalar, tıklayınca oynatma ve SEÇEREK SİL butonu)
 * - 4) Yinelenen Dosyalar Kartı (Kopyaları tek tıkla veya seçerek temizleme)
 * - 5) En Çok Yer Kaplayan Uygulamalar Kartı (Tıklayınca uygulama bilgisi / kaldırma ekranı)
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalysisBinding
    private var currentVolumePath: String = ""
    private var currentVolumeTitle: String = "Ana bellek"
    private var dynamicCardsContainer: LinearLayout? = null
    private var volumeTabsBar: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentVolumePath = intent.getStringExtra("TARGET_PATH") ?: LocalFileRepository.rootStorageDir.absolutePath
        currentVolumeTitle = intent.getStringExtra("TARGET_TITLE") ?: "Ana bellek"

        applyThemeColors()

        binding.btnAnalysisBack.setOnClickListener { finish() }
        binding.btnAnalysisRefresh.setOnClickListener { runAnalysis() }

        setupVolumeTabsIfNeeded()
        runAnalysis()
    }

    private fun applyThemeColors() {
        ThemeManager.applyWindowColors(this, binding.analysisRoot, binding.analysisToolbar)
        val cardColor = ThemeManager.getCardColor(this)
        binding.cardStorageBreakdown.setBackgroundColor(cardColor)
        binding.cardDuplicates.setBackgroundColor(cardColor)
        binding.cardLargeFiles.setBackgroundColor(cardColor)
        binding.tvAnalysisProgressText.setTextColor(ThemeManager.getPrimaryTextColor(this))
    }

    private fun setupVolumeTabsIfNeeded() {
        val removables = LocalFileRepository.getRemovableStorageVolumes(this)
        if (removables.isEmpty()) return

        val parentList = binding.svAnalysisContent.getChildAt(0) as? LinearLayout ?: return
        if (volumeTabsBar == null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * resources.displayMetrics.density).toInt()
                }
                setBackgroundColor(ThemeManager.getCardColor(this@StorageAnalysisActivity))
            }
            parentList.addView(bar, 0)
            volumeTabsBar = bar
        }
        val bar = volumeTabsBar ?: return
        bar.removeAllViews()

        val volumes = mutableListOf("Ana bellek" to LocalFileRepository.rootStorageDir.absolutePath)
        removables.forEach { volumes.add(it.title to it.path) }

        for ((title, path) in volumes) {
            val isSelected = path == currentVolumePath
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = title.uppercase()
                setTextColor(if (isSelected) Color.parseColor("#FF6D00") else ThemeManager.getSecondaryTextColor(this@StorageAnalysisActivity))
                setBackgroundColor(Color.TRANSPARENT)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener {
                    if (currentVolumePath != path) {
                        currentVolumePath = path
                        currentVolumeTitle = title
                        setupVolumeTabsIfNeeded()
                        runAnalysis()
                    }
                }
            }
            bar.addView(btn)
        }
    }

    private fun runAnalysis() {
        binding.llAnalysisLoading.visibility = View.VISIBLE
        binding.svAnalysisContent.visibility = View.GONE
        binding.tvAnalysisProgressText.text = "$currentVolumeTitle analiz ediliyor..."

        lifecycleScope.launch {
            val report = LocalFileRepository.analyzeStorage(
                context = this@StorageAnalysisActivity,
                targetRootPath = currentVolumePath,
                targetTitle = currentVolumeTitle
            )
            binding.llAnalysisLoading.visibility = View.GONE
            binding.svAnalysisContent.visibility = View.VISIBLE
            renderReport(report)
        }
    }

    private fun renderReport(report: StorageAnalysisReport) {
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val cardColor = ThemeManager.getCardColor(this)
        val accentOrange = Color.parseColor("#FF6D00")
        val density = resources.displayMetrics.density

        binding.cardStorageBreakdown.setBackgroundColor(cardColor)
        binding.cardDuplicates.setBackgroundColor(cardColor)
        binding.cardLargeFiles.setBackgroundColor(cardColor)

        binding.tvAnalysisFreeSpace.text =
            "${FormatUtils.formatSize(report.overview.usedBytes)} / ${FormatUtils.formatSize(report.overview.totalBytes)}  (Boş ${FormatUtils.formatSize(report.overview.freeBytes)})"
        binding.tvAnalysisFreeSpace.setTextColor(accentOrange)

        binding.tvAnalysisPercentBig.text = "${report.overview.usedPercent}%"
        binding.tvAnalysisPercentBig.setTextColor(primaryColor)
        binding.pbAnalysisMain.progress = report.overview.usedPercent

        // Kategori Dağılımı Özeti
        binding.tvCategoryBreakdownDetails.setTextColor(primaryColor)
        binding.tvCategoryBreakdownDetails.text =
            "▪ Videolar: ${FormatUtils.formatSize(report.videosBytes)} (${report.videosCount})      ▪ Görüntüler: ${FormatUtils.formatSize(report.imagesBytes)} (${report.imagesCount})\n" +
            "▪ Uygulamalar: ${FormatUtils.formatSize(report.appsBytes)} (${report.installedApps.size})      ▪ Ses: ${FormatUtils.formatSize(report.audioBytes)} (${report.audioCount})\n" +
            "▪ Belgeler: ${FormatUtils.formatSize(report.docsBytes)} (${report.docsCount})      ▪ Diğer / Sistem: ${FormatUtils.formatSize(report.othersBytes)}"

        // Dinamik Kartlar Konteynerini (En Çok Yer Kaplayan Klasörler + Kategoriler + Uygulamalar) hazırla
        val contentRoot = binding.svAnalysisContent.getChildAt(0) as? LinearLayout
        if (contentRoot != null) {
            if (dynamicCardsContainer == null) {
                val dyn = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                // cardStorageBreakdown'ın hemen altına ekle
                val idx = contentRoot.indexOfChild(binding.cardStorageBreakdown)
                contentRoot.addView(dyn, (idx + 1).coerceAtMost(contentRoot.childCount))
                dynamicCardsContainer = dyn
            }
            dynamicCardsContainer?.removeAllViews()
            dynamicCardsContainer?.let { dyn ->
                // Hızlı Kategori Kısayol Butonları
                dyn.addView(buildCategoryQuickButtonsBar(report))
                // KART: En Çok Yer Kaplayan Klasörler (Tüm klasörler)
                dyn.addView(buildTopFoldersCard(report))
                // KART: En Çok Yer Kaplayan Uygulamalar (varsa)
                if (report.installedApps.isNotEmpty()) {
                    dyn.addView(buildTopAppsCard(report))
                }
            }
        }

        // KART: Büyük Dosyalar (> 10 MB)
        binding.llLargeFileItems.removeAllViews()
        val largeTotal = report.largeFiles.sumOf { it.size }
        binding.tvLargeFilesTotalSize.text = FormatUtils.formatSize(largeTotal)
        binding.tvLargeFilesTotalSize.setTextColor(accentOrange)

        if (report.largeFiles.isEmpty()) {
            binding.llLargeFileItems.addView(createEmptyText("10 MB'tan büyük dosya bulunamadı.", secondaryColor))
        } else {
            for (lf in report.largeFiles.take(8)) {
                binding.llLargeFileItems.addView(
                    createInteractiveFileRow(
                        item = lf,
                        rightText = FormatUtils.formatSize(lf.size),
                        subtitle = "/${lf.parentId}",
                        onRowClick = { openAnalyzedItem(lf) },
                        onDeleteClick = { confirmAndDeleteItems(listOf(lf)) }
                    )
                )
            }
            val btnCleanLarge = Button(this).apply {
                text = "TÜM BÜYÜK DOSYALARI GÖR VE SEÇEREK SİL (${report.largeFiles.size})"
                setTextColor(accentOrange)
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    showMultiSelectDeleteDialog(
                        title = "Büyük Dosyaları Seç ve Sil",
                        items = report.largeFiles
                    )
                }
            }
            binding.llLargeFileItems.addView(btnCleanLarge)
        }

        // KART: Yinelenen Dosyalar
        binding.llDuplicateItems.removeAllViews()
        val dupBytes = report.duplicateGroups.sumOf { grp -> grp.drop(1).sumOf { it.size } }
        binding.tvDuplicatesTotalSize.text = FormatUtils.formatSize(dupBytes)
        binding.tvDuplicatesTotalSize.setTextColor(accentOrange)

        if (report.duplicateGroups.isEmpty()) {
            binding.llDuplicateItems.addView(createEmptyText("Yinelenen kopya dosya bulunamadı.", secondaryColor))
        } else {
            val allDuplicateCopies = report.duplicateGroups.flatMap { it.drop(1) }
            for (grp in report.duplicateGroups.take(6)) {
                val first = grp.first()
                val duplicateCopy = grp.last()
                binding.llDuplicateItems.addView(
                    createInteractiveFileRow(
                        item = duplicateCopy,
                        rightText = "${grp.size} kopya • ${FormatUtils.formatSize(first.size)}",
                        subtitle = first.path.substringBeforeLast('/'),
                        onRowClick = { openAnalyzedItem(first) },
                        onDeleteClick = { confirmAndDeleteItems(grp.drop(1)) }
                    )
                )
            }
            val btnCleanDups = Button(this).apply {
                text = "YİNELENEN KOPYALARI SEÇ VE SİL (${allDuplicateCopies.size} kopya)"
                setTextColor(accentOrange)
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    showMultiSelectDeleteDialog(
                        title = "Yinelenen Kopyaları Sil",
                        items = allDuplicateCopies
                    )
                }
            }
            binding.llDuplicateItems.addView(btnCleanDups)
        }
    }

    private fun buildCategoryQuickButtonsBar(report: StorageAnalysisReport): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }

        val cats = listOf(
            Triple("Videolar", "VIDEOS", report.videosBytes),
            Triple("Görüntüler", "IMAGES", report.imagesBytes),
            Triple("Ses", "AUDIO", report.audioBytes),
            Triple("Uygulamalar", "APPS", report.appsBytes)
        )

        for ((label, key, bytes) in cats) {
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * density).toInt()
                }
                text = "$label\n${FormatUtils.formatSize(bytes)}"
                textSize = 11f
                isAllCaps = false
                setTextColor(ThemeManager.getPrimaryTextColor(this@StorageAnalysisActivity))
                setBackgroundColor(ThemeManager.getCardColor(this@StorageAnalysisActivity))
                setOnClickListener {
                    if (key == "APPS") {
                        startActivity(Intent(this@StorageAnalysisActivity, AppsManagerActivity::class.java))
                    } else {
                        startActivity(Intent(this@StorageAnalysisActivity, FileExplorerActivity::class.java).apply {
                            putExtra("MODE", "CATEGORY")
                            putExtra("CATEGORY", key)
                            putExtra("TITLE", label)
                            putExtra("PREFER_GRID", true)
                        })
                    }
                }
            }
            row.addView(btn)
        }
        return row
    }

    private fun buildTopFoldersCard(report: StorageAnalysisReport): View {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val accentOrange = Color.parseColor("#FF6D00")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(ThemeManager.getCardColor(this@StorageAnalysisActivity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
            elevation = 2f * density
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        val tvTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "En Çok Yer Kaplayan Klasörler"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        val totalFoldersSize = report.topFolders.sumOf { it.size }
        val tvTotal = TextView(this).apply {
            text = FormatUtils.formatSize(totalFoldersSize)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentOrange)
        }
        header.addView(tvTitle)
        header.addView(tvTotal)
        card.addView(header)

        val maxFolderSize = (report.topFolders.firstOrNull()?.size ?: 1L).coerceAtLeast(1L)
        for (folder in report.topFolders.take(8)) {
            val pct = ((folder.size * 100L) / maxFolderSize).toInt().coerceIn(2, 100)
            card.addView(
                createInteractiveFileRow(
                    item = folder,
                    rightText = FormatUtils.formatSize(folder.size),
                    subtitle = "${folder.childCount} dosya • %${((folder.size * 100L) / report.overview.usedBytes.coerceAtLeast(1L)).toInt()} yer kaplıyor",
                    progressPct = pct,
                    onRowClick = { openAnalyzedItem(folder) },
                    onDeleteClick = { confirmAndDeleteItems(listOf(folder)) }
                )
            )
        }

        if (report.topFolders.size > 8) {
            val btnMoreFolders = Button(this).apply {
                text = "TÜM KLASÖRLERİ GÖR VE TEMİZLE (${report.topFolders.size})"
                setTextColor(accentOrange)
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    showMultiSelectDeleteDialog(
                        title = "Klasörleri Seç ve Sil",
                        items = report.topFolders
                    )
                }
            }
            card.addView(btnMoreFolders)
        }

        return card
    }

    private fun buildTopAppsCard(report: StorageAnalysisReport): View {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val accentOrange = Color.parseColor("#FF6D00")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(ThemeManager.getCardColor(this@StorageAnalysisActivity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
            elevation = 2f * density
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        val tvTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "En Çok Yer Kaplayan Uygulamalar"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        val tvTotal = TextView(this).apply {
            text = FormatUtils.formatSize(report.appsBytes)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentOrange)
        }
        header.addView(tvTitle)
        header.addView(tvTotal)
        card.addView(header)

        for (app in report.installedApps.take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padV = (8 * density).toInt()
                setPadding(0, padV, 0, padV)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openAppDetails(app)
                }
            }
            val iv = ImageView(this).apply {
                val sz = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (12 * density).toInt()
                }
                try {
                    setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (_: Exception) {
                    setImageResource(R.drawable.ic_file_apk)
                }
            }
            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "${app.appName} (v${app.versionName})"
                textSize = 14f
                setTextColor(primaryColor)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvSize = TextView(this).apply {
                text = FormatUtils.formatSize(app.apkSizeBytes)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentOrange)
            }
            row.addView(iv)
            row.addView(tvName)
            row.addView(tvSize)
            card.addView(row)
        }

        val btnAllApps = Button(this).apply {
            text = "TÜM UYGULAMALARI YÖNET (${report.installedApps.size})"
            setTextColor(accentOrange)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                startActivity(Intent(this@StorageAnalysisActivity, AppsManagerActivity::class.java))
            }
        }
        card.addView(btnAllApps)
        return card
    }

    private fun openAppDetails(app: InstalledAppItem) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${app.packageName}")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(this, AppsManagerActivity::class.java))
        }
    }

    private fun createInteractiveFileRow(
        item: FileItem,
        rightText: String,
        subtitle: String,
        progressPct: Int = -1,
        onRowClick: () -> Unit,
        onDeleteClick: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val accentOrange = Color.parseColor("#FF6D00")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padV = (6 * density).toInt()
            setPadding(0, padV, 0, padV)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onRowClick() }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ivIcon = ImageView(this).apply {
            val sz = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (10 * density).toInt()
            }
            val iconRes = when {
                item.isDirectory -> R.drawable.icon_folder_full_s
                item.isVideo -> R.drawable.icon_file_video_s
                item.isImage -> R.drawable.icon_file_image_s
                item.isAudio -> R.drawable.icon_file_music_s
                item.isArchive -> R.drawable.icon_file_archive_s
                else -> R.drawable.icon_file_document_s
            }
            setImageResource(iconRes)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvName = TextView(this).apply {
            text = item.name
            textSize = 14f
            setTextColor(primaryColor)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }

        val tvSub = TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(secondaryColor)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }

        textCol.addView(tvName)
        textCol.addView(tvSub)

        val tvRight = TextView(this).apply {
            text = rightText
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentOrange)
            val m = (8 * density).toInt()
            setPadding(m, 0, m, 0)
        }

        val btnTrash = ImageView(this).apply {
            val sz = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            setImageResource(R.drawable.ic_delete)
            setColorFilter(secondaryColor)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onDeleteClick() }
        }

        topRow.addView(ivIcon)
        topRow.addView(textCol)
        topRow.addView(tvRight)
        topRow.addView(btnTrash)
        container.addView(topRow)

        if (progressPct >= 0) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (4 * density).toInt()
                ).apply {
                    topMargin = (4 * density).toInt()
                    marginStart = (38 * density).toInt()
                }
                max = 100
                progress = progressPct
                progressDrawable = androidx.core.content.ContextCompat.getDrawable(this@StorageAnalysisActivity, R.drawable.progress_storage_bar)
            }
            container.addView(pb)
        }

        return container
    }

    private fun openAnalyzedItem(item: FileItem) {
        if (item.isDirectory) {
            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                putExtra("MODE", "LOCAL")
                putExtra("PATH", item.path)
                putExtra("TITLE", item.name)
            })
            return
        }
        when {
            item.isVideo -> startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("VIDEO_URL", item.path)
                putExtra("VIDEO_TITLE", item.name)
            })
            item.isAudio -> startActivity(Intent(this, MusicPlayerActivity::class.java).apply {
                putExtra("AUDIO_URL", item.path)
                putExtra("AUDIO_TITLE", item.name)
            })
            item.isImage -> startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("IMAGE_PATH", item.path)
                putExtra("IMAGE_TITLE", item.name)
            })
            else -> LocalFileRepository.openWithExternalChooser(this, item)
        }
    }

    private fun showMultiSelectDeleteDialog(title: String, items: List<FileItem>) {
        if (items.isEmpty()) return
        val displayList = items.take(60)
        val labels = displayList.map { "${it.name} (${FormatUtils.formatSize(it.size)})" }.toTypedArray()
        val checked = BooleanArray(displayList.size) { false }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Seçilenleri Sil") { _, _ ->
                val toDelete = displayList.filterIndexed { index, _ -> checked[index] }
                if (toDelete.isEmpty()) {
                    Toast.makeText(this, "Hiçbir öğe seçilmedi.", Toast.LENGTH_SHORT).show()
                } else {
                    confirmAndDeleteItems(toDelete)
                }
            }
            .setNeutralButton("Tümünü Seç ve Sil") { _, _ ->
                confirmAndDeleteItems(displayList)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmAndDeleteItems(items: List<FileItem>) {
        val totalBytes = items.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Sil (${items.size} öğe • ${FormatUtils.formatSize(totalBytes)})")
            .setMessage("Seçilen ${items.size} öğeyi silip ${FormatUtils.formatSize(totalBytes)} alan açmak istediğinize emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    var deleted = 0
                    for (it in items) {
                        if (LocalFileRepository.deleteFileOrRecycle(File(it.path), useRecycleBin = false)) {
                            deleted++
                        }
                    }
                    Toast.makeText(
                        this@StorageAnalysisActivity,
                        "$deleted öğe silindi (${FormatUtils.formatSize(totalBytes)} alan açıldı).",
                        Toast.LENGTH_SHORT
                    ).show()
                    runAnalysis()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun createEmptyText(text: String, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setPadding(0, 10, 0, 10)
            setTextColor(color)
        }
    }
}
