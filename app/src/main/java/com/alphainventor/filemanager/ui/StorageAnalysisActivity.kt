package com.alphainventor.filemanager.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.core.InstalledAppItem
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.core.StorageAnalysisReport
import com.alphainventor.filemanager.databinding.ActivityStorageAnalysisBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import com.alphainventor.filemanager.viewer.ImageViewerActivity
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.Stack

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bellek Analizi Ekranı (Video 5: 00:04 - 00:36):
 * 1) Ana Kartlar Görünümü (card_analysis_type_size.xml & card_files.xml):
 *    - Ana bellek (Boş 45,67 GB | 64% | Görüntüler, Ses, Videolar, Belgeler, Arşivler, Diğerleri | DAHA FAZLA)
 *    - Uygulamalar (22,11 GB | En büyük uygulamalar | DAHA FAZLA)
 *    - Büyük dosyalar (28,19 GB | 10 MB'den büyük dosyalar | DAHA FAZLA)
 *    - Ön bellek dosyaları (SİL)
 *    - Yinelenen dosyalar (SİL | DAHA FAZLA)
 * 2) "DAHA FAZLA" veya klasörlere tıklanınca açılan Orijinal Klasör İçi Boyut Gezgini (Video 5: 00:09 - 00:23):
 *    - Klasörlerin ve dosyaların arkasında orijinal yeşil/mavi yüzdelik doluluk çubuğu (clip_storage_total & clip_current_folder)
 *      ve sağda "12,07%", "6,33%", "4,43%" yüzdesi yer alır.
 *    - Herhangi bir klasöre (Download -> 1DMP -> Compressed -> Alien...) tıkladığınızda sıra sıra içine girip
 *      alt klasörlerin boyutlarını ve videoları gösterir!
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalysisBinding
    private var currentVolumePath: String = ""
    private var currentVolumeTitle: String = "Ana bellek"
    private var currentReport: StorageAnalysisReport? = null

    // 0 = Ana Analiz Kartları, 1 = Klasör Boyutu Sıralı Gezgin (Video 5 00:09-00:23), 2 = Tüm Büyük Dosyalar Listesi (Video 5 00:30-00:33)
    private var viewState: Int = 0
    private val drillDownStack = Stack<String>()

    private var drillDownContainer: LinearLayout? = null
    private var drillDownBreadcrumbs: LinearLayout? = null
    private var drillDownHsv: HorizontalScrollView? = null
    private var drillDownRecycler: RecyclerView? = null
    private var drillDownAdapter: DrillDownSizeAdapter? = null
    private var drillDownBottomDeleteBar: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentVolumePath = intent.getStringExtra("TARGET_PATH") ?: LocalFileRepository.rootStorageDir.absolutePath
        currentVolumeTitle = intent.getStringExtra("TARGET_TITLE") ?: "Ana bellek"

        applyThemeColors()
        setupDrillDownOverlay()

        binding.btnAnalysisBack.setOnClickListener {
            handleBackNavigation()
        }
        binding.btnAnalysisRefresh.setOnClickListener {
            runAnalysis()
        }

        runAnalysis()
    }

    private fun applyThemeColors() {
        ThemeManager.applyWindowColors(this, binding.analysisRoot, binding.analysisToolbar)
        binding.tvAnalysisProgressText.setTextColor(ThemeManager.getPrimaryTextColor(this))
    }

    private fun setupDrillDownOverlay() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            visibility = View.GONE
            setBackgroundColor(ThemeManager.getBackgroundColor(this@StorageAnalysisActivity))
        }

        // Üst Breadcrumb Barı: [◔ Pie] > [💾 Sürücü] > Download > 1DMP > Compressed
        val pathBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            )
            val pH = (10 * density).toInt()
            setPadding(pH, 0, pH, 0)
            setBackgroundColor(ThemeManager.getSurfaceColor(this@StorageAnalysisActivity))
        }

        val ivPieCrumb = ImageView(this).apply {
            val sz = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setImageResource(R.drawable.icon_analysis)
            setOnClickListener {
                showMainCardsState()
            }
        }

        val ivDiv1 = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (18 * density).toInt()).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
            setImageResource(R.drawable.ic_pathbar_divider)
        }

        val ivStorageCrumb = ImageView(this).apply {
            val sz = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (4 * density).toInt()
            }
            setImageResource(R.drawable.icon_main_storage_s)
            setOnClickListener {
                if (viewState == 1 && drillDownStack.size > 1) {
                    while (drillDownStack.size > 1) drillDownStack.pop()
                    loadDrillDownDirectory(drillDownStack.peek())
                }
            }
        }

        val hsv = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isHorizontalScrollBarEnabled = false
        }
        val crumbsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        hsv.addView(crumbsRow)

        val ivUpBtn = ImageView(this).apply {
            val sz = (34 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            val pad = (5 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setImageResource(R.drawable.ic_pathbar_up)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { handleBackNavigation() }
        }

        pathBar.addView(ivPieCrumb)
        pathBar.addView(ivDiv1)
        pathBar.addView(ivStorageCrumb)
        pathBar.addView(hsv)
        pathBar.addView(ivUpBtn)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt())
            setBackgroundColor(ThemeManager.getDividerColor(this@StorageAnalysisActivity))
        }

        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@StorageAnalysisActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
        }

        val btnDeleteSelected = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (50 * density).toInt()
            )
            text = "SEÇİLENLERİ SİL"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#008578"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setOnClickListener {
                val sel = drillDownAdapter?.getSelectedItems().orEmpty()
                if (sel.isNotEmpty()) {
                    confirmAndDeleteItems(sel)
                }
            }
        }

        container.addView(pathBar)
        container.addView(divider)
        container.addView(rv)
        container.addView(btnDeleteSelected)

        binding.analysisRoot.addView(container)

        drillDownContainer = container
        drillDownBreadcrumbs = crumbsRow
        drillDownHsv = hsv
        drillDownRecycler = rv
        drillDownBottomDeleteBar = btnDeleteSelected

        drillDownAdapter = DrillDownSizeAdapter(
            onItemClick = { item ->
                if (item.isDirectory) {
                    drillDownStack.push(item.path)
                    loadDrillDownDirectory(item.path)
                } else {
                    openAnalyzedItem(item)
                }
            },
            onItemLongClick = { item ->
                drillDownAdapter?.toggleSelection(item)
                updateDrillDownSelectionBar()
            },
            onSelectionChanged = {
                updateDrillDownSelectionBar()
            }
        )
        rv.adapter = drillDownAdapter
    }

    private fun updateDrillDownSelectionBar() {
        val sel = drillDownAdapter?.getSelectedItems().orEmpty()
        if (sel.isNotEmpty()) {
            val totalSz = sel.sumOf { it.size }
            drillDownBottomDeleteBar?.visibility = View.VISIBLE
            drillDownBottomDeleteBar?.text = "SİL (${sel.size} ÖĞE • ${FormatUtils.formatSize(totalSz)})"
        } else {
            drillDownBottomDeleteBar?.visibility = View.GONE
        }
    }

    private fun runAnalysis() {
        binding.llAnalysisLoading.visibility = View.VISIBLE
        binding.svAnalysisContent.visibility = View.GONE
        drillDownContainer?.visibility = View.GONE
        binding.tvAnalysisProgressText.text = "$currentVolumeTitle analiz ediliyor..."

        lifecycleScope.launch {
            val report = LocalFileRepository.analyzeStorage(
                context = this@StorageAnalysisActivity,
                targetRootPath = currentVolumePath,
                targetTitle = currentVolumeTitle
            )
            currentReport = report
            binding.llAnalysisLoading.visibility = View.GONE
            if (viewState == 1 && drillDownStack.isNotEmpty()) {
                drillDownContainer?.visibility = View.VISIBLE
                loadDrillDownDirectory(drillDownStack.peek())
            } else if (viewState == 2) {
                openLargeFilesListScreen(report)
            } else {
                showMainCardsState()
            }
        }
    }

    private fun showMainCardsState() {
        viewState = 0
        drillDownStack.clear()
        drillDownAdapter?.clearSelection()
        updateDrillDownSelectionBar()
        binding.tvAnalysisTitle.text = "Bellek Analizi"
        drillDownContainer?.visibility = View.GONE
        binding.svAnalysisContent.visibility = View.VISIBLE
        currentReport?.let { buildOriginalAnalysisCards(it) }
    }

    /**
     * Video 5'teki (00:09 - 00:23) Orijinal Klasör Boyutu Sıralı Gezgin Ekranını Açar:
     * Download -> 1DMP -> Compressed -> Alien (Saga)... şeklinde tüm alt klasörlere sıra sıra girer!
     */
    private fun openFolderDrillDownState(startFolderPath: String) {
        val rep = currentReport ?: return
        viewState = 1
        drillDownAdapter?.showCheckboxesAlways = false
        drillDownAdapter?.clearSelection()
        updateDrillDownSelectionBar()

        if (drillDownStack.isEmpty()) {
            drillDownStack.push(rep.rootPath)
        }
        if (startFolderPath != rep.rootPath && (drillDownStack.isEmpty() || drillDownStack.peek() != startFolderPath)) {
            drillDownStack.push(startFolderPath)
        }

        binding.tvAnalysisTitle.text = currentVolumeTitle
        binding.svAnalysisContent.visibility = View.GONE
        drillDownContainer?.visibility = View.VISIBLE
        loadDrillDownDirectory(drillDownStack.peek())
    }

    private fun loadDrillDownDirectory(folderPath: String) {
        val rep = currentReport ?: return
        renderDrillDownBreadcrumbs(folderPath, rep.rootPath)

        lifecycleScope.launch {
            val items = LocalFileRepository.getFolderSizeDrillDownItems(folderPath, rep)
            val parentTotal = (rep.folderSizeMap[folderPath] ?: items.sumOf { it.size }).coerceAtLeast(1L)
            val storageTotal = rep.overview.totalBytes.coerceAtLeast(1L)
            drillDownAdapter?.submitDrillDownList(
                newList = items,
                parentTotalBytes = parentTotal,
                volumeTotalBytes = storageTotal,
                showRelativePath = false
            )
        }
    }

    /**
     * Video 5'teki (00:30 - 00:33) Orijinal "Büyük dosyalar" Tam Liste Ekranı
     */
    private fun openLargeFilesListScreen(report: StorageAnalysisReport) {
        viewState = 2
        drillDownStack.clear()
        drillDownAdapter?.showCheckboxesAlways = true
        drillDownAdapter?.clearSelection()
        updateDrillDownSelectionBar()

        binding.tvAnalysisTitle.text = "Büyük dosyalar"
        binding.svAnalysisContent.visibility = View.GONE
        drillDownContainer?.visibility = View.VISIBLE

        drillDownBreadcrumbs?.removeAllViews()
        val tvCrumb = TextView(this).apply {
            text = "›  Büyük dosyalar"
            textSize = 14f
            setTextColor(ThemeManager.getPrimaryTextColor(this@StorageAnalysisActivity))
            setPadding(8, 0, 8, 0)
        }
        drillDownBreadcrumbs?.addView(tvCrumb)

        val maxFileSz = (report.largeFiles.firstOrNull()?.size ?: 1L).coerceAtLeast(1L)
        drillDownAdapter?.submitDrillDownList(
            newList = report.largeFiles,
            parentTotalBytes = maxFileSz,
            volumeTotalBytes = report.overview.totalBytes.coerceAtLeast(1L),
            showRelativePath = true
        )
    }

    private fun renderDrillDownBreadcrumbs(folderPath: String, rootPath: String) {
        val row = drillDownBreadcrumbs ?: return
        row.removeAllViews()

        val rel = folderPath.removePrefix(rootPath).trim('/')
        if (rel.isEmpty()) return

        val parts = rel.split('/').filter { it.isNotEmpty() }
        var acc = rootPath
        parts.forEachIndexed { idx, seg ->
            acc = "$acc/$seg"
            val targetPath = acc
            val tv = TextView(this).apply {
                text = "›  $seg"
                textSize = 14f
                setTextColor(
                    if (idx == parts.lastIndex) ThemeManager.getPrimaryTextColor(this@StorageAnalysisActivity)
                    else ThemeManager.getSecondaryTextColor(this@StorageAnalysisActivity)
                )
                setPadding(8, 0, 8, 0)
                setOnClickListener {
                    while (drillDownStack.size > 1 && drillDownStack.peek() != targetPath) {
                        drillDownStack.pop()
                    }
                    loadDrillDownDirectory(drillDownStack.peek())
                }
            }
            row.addView(tv)
        }
        drillDownHsv?.post {
            drillDownHsv?.fullScroll(View.FOCUS_RIGHT)
        }
    }

    /**
     * Video 5'teki (00:04 - 00:08 ve 00:28 - 00:36) Orijinal Bellek Analizi Kartlarını Birebir Oluşturur:
     * - card_analysis_type_size.xml
     * - card_files.xml (Uygulamalar, Büyük dosyalar, Ön bellek dosyaları, Yinelenen dosyalar)
     */
    private fun buildOriginalAnalysisCards(report: StorageAnalysisReport) {
        val contentRoot = binding.svAnalysisContent.getChildAt(0) as? LinearLayout ?: return
        contentRoot.removeAllViews()

        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val cardColor = ThemeManager.getCardColor(this)
        val dividerColor = ThemeManager.getDividerColor(this)
        val tealAccent = Color.parseColor("#008578")

        // Varsa Ana bellek / SD kart sekme çubuğu
        val removables = LocalFileRepository.getRemovableStorageVolumes(this)
        if (removables.isNotEmpty()) {
            val tabBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(cardColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt()
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            val allVols = mutableListOf("Ana bellek" to LocalFileRepository.rootStorageDir.absolutePath)
            removables.forEach { allVols.add(it.title to it.path) }
            for ((title, path) in allVols) {
                val selected = path == currentVolumePath
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    text = title
                    isAllCaps = false
                    setTextColor(if (selected) tealAccent else secondaryColor)
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener {
                        if (currentVolumePath != path) {
                            currentVolumePath = path
                            currentVolumeTitle = title
                            runAnalysis()
                        }
                    }
                }
                tabBar.addView(btn)
            }
            contentRoot.addView(tabBar)
        }

        fun createCardShell(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cardColor)
                val pH = (14 * density).toInt()
                val pTop = (10 * density).toInt()
                setPadding(pH, pTop, pH, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
                elevation = 2f * density
            }
        }

        fun addCardTitleRow(card: LinearLayout, title: String, rightValue: String, subtitle: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BASELINE
                val pV = (4 * density).toInt()
                setPadding(0, pV, 0, pV)
            }
            val tvTitle = TextView(this).apply {
                text = title
                textSize = 17.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryColor)
            }
            val tvVal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * density).toInt()
                }
                text = rightValue
                textSize = 17.5f
                setTextColor(tealAccent)
            }
            row.addView(tvTitle)
            row.addView(tvVal)
            card.addView(row)

            if (subtitle.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = subtitle
                    textSize = 12.5f
                    setTextColor(secondaryColor)
                    setPadding(0, 0, 0, (6 * density).toInt())
                })
            }
        }

        fun addCardBottomButtons(
            card: LinearLayout,
            leftBtnText: String? = null,
            onLeftClick: (() -> Unit)? = null,
            rightBtnText: String = "DAHA FAZLA",
            onRightClick: () -> Unit
        ) {
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply {
                    topMargin = (6 * density).toInt()
                }
                setBackgroundColor(dividerColor)
            })
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt()
                )
            }
            if (leftBtnText != null && onLeftClick != null) {
                val btnLeft = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    text = leftBtnText
                    textSize = 13.5f
                    setTextColor(primaryColor)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { onLeftClick() }
                }
                btnRow.addView(btnLeft)
            }
            val btnRight = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                text = rightBtnText
                textSize = 13.5f
                setTextColor(primaryColor)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onRightClick() }
            }
            btnRow.addView(btnRight)
            card.addView(btnRow)
        }

        // ==================== KART 1: Ana bellek Boş XX GB (card_analysis_type_size.xml) ====================
        val card1 = createCardShell()
        addCardTitleRow(card1, report.rootTitle, "Boş ${FormatUtils.formatSizeDetailed(report.overview.freeBytes)}")

        val tvPct = TextView(this).apply {
            text = "${report.overview.usedPercent}%"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
        }
        card1.addView(tvPct)

        val pbMain = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            max = 100
            progress = report.overview.usedPercent
            progressDrawable = ContextCompat.getDrawable(this@StorageAnalysisActivity, R.drawable.progress_storage_bar)
        }
        card1.addView(pbMain)

        // 2 sütun x 3 satır Kategori Özeti: Görüntüler | Ses / Videolar | Belgeler / Arşivler | Diğerleri
        val catRowsData = listOf(
            Triple("Görüntüler", R.drawable.v_shape_image, report.imagesBytes) to Triple("Ses", R.drawable.ic_cat_audio, report.audioBytes),
            Triple("Videolar", R.drawable.v_shape_video, report.videosBytes) to Triple("Belgeler", R.drawable.ic_cat_docs, report.docsBytes),
            Triple("Arşivler", R.drawable.icon_file_archive_s, report.archivesBytes) to Triple("Diğerleri", R.drawable.v_shape_folder_dir, report.othersBytes)
        )

        for ((leftCat, rightCat) in catRowsData) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pV = (5 * density).toInt()
                setPadding(0, pV, 0, pV)
            }

            fun createCatCell(label: String, iconRes: Int, bytes: Long): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (8 * density).toInt()
                    }
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        when (label) {
                            "Görüntüler" -> openCategory("IMAGES", "Görüntüler")
                            "Ses" -> openCategory("AUDIO", "Ses")
                            "Videolar" -> openCategory("VIDEOS", "Videolar")
                            "Belgeler" -> openCategory("DOCUMENTS", "Belgeler")
                            else -> openFolderDrillDownState(report.rootPath)
                        }
                    }

                    val iv = ImageView(this@StorageAnalysisActivity).apply {
                        val sz = (18 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            marginEnd = (8 * density).toInt()
                        }
                        setImageResource(iconRes)
                    }
                    val tvLbl = TextView(this@StorageAnalysisActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = label
                        textSize = 13f
                        setTextColor(primaryColor)
                        isSingleLine = true
                    }
                    val tvSz = TextView(this@StorageAnalysisActivity).apply {
                        text = FormatUtils.formatSize(bytes)
                        textSize = 12.5f
                        setTextColor(secondaryColor)
                    }
                    addView(iv)
                    addView(tvLbl)
                    addView(tvSz)
                }
            }

            row.addView(createCatCell(leftCat.first, leftCat.second, leftCat.third))
            row.addView(createCatCell(rightCat.first, rightCat.second, rightCat.third))
            card1.addView(row)
        }

        // DAHA FAZLA -> Video 5 (00:08-00:23) Klasörlerin İçi Boyut Sıralı Gezgin Ekranını Açar!
        addCardBottomButtons(card1, rightBtnText = "DAHA FAZLA") {
            openFolderDrillDownState(report.rootPath)
        }
        contentRoot.addView(card1)

        // ==================== KART 2: Uygulamalar (card_files.xml) ====================
        if (report.installedApps.isNotEmpty()) {
            val cardApps = createCardShell()
            addCardTitleRow(cardApps, "Uygulamalar", FormatUtils.formatSizeDetailed(report.appsBytes))

            for (app in report.installedApps.take(4)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pV = (6 * density).toInt()
                    setPadding(0, pV, 0, pV)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { openAppDetails(app) }
                }
                val iv = ImageView(this).apply {
                    val sz = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (10 * density).toInt()
                    }
                }
                ThumbnailCacheManager.loadAppIconAsync(this, app.packageName, iv, R.drawable.ic_file_apk)

                val tvName = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = app.appName
                    textSize = 13.5f
                    setTextColor(primaryColor)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                val tvSz = TextView(this).apply {
                    text = FormatUtils.formatSize(app.apkSizeBytes)
                    textSize = 13f
                    setTextColor(tealAccent)
                }
                row.addView(iv)
                row.addView(tvName)
                row.addView(tvSz)
                cardApps.addView(row)
            }

            addCardBottomButtons(cardApps, rightBtnText = "DAHA FAZLA") {
                startActivity(Intent(this, AppsManagerActivity::class.java))
            }
            contentRoot.addView(cardApps)
        }

        // ==================== KART 3: Büyük dosyalar (10 MB'den büyük dosyalar) ====================
        val cardLarge = createCardShell()
        val largeTotal = report.largeFiles.sumOf { it.size }
        addCardTitleRow(
            card = cardLarge,
            title = "Büyük dosyalar",
            rightValue = FormatUtils.formatSizeDetailed(largeTotal),
            subtitle = "10 MB'den büyük dosyalar"
        )

        for (lf in report.largeFiles.take(4)) {
            cardLarge.addView(
                createCardFileItemRow(
                    item = lf,
                    subtitle = "/${lf.parentId}",
                    rightText = FormatUtils.formatSize(lf.size),
                    onClick = { openAnalyzedItem(lf) }
                )
            )
        }

        addCardBottomButtons(cardLarge, rightBtnText = "DAHA FAZLA") {
            openLargeFilesListScreen(report)
        }
        contentRoot.addView(cardLarge)

        // ==================== KART 4: Yinelenen dosyalar ====================
        val cardDups = createCardShell()
        val allDupCopies = report.duplicateGroups.flatMap { it.drop(1) }
        val dupBytes = allDupCopies.sumOf { it.size }
        addCardTitleRow(
            card = cardDups,
            title = "Yinelenen dosyalar",
            rightValue = FormatUtils.formatSizeDetailed(dupBytes)
        )

        if (report.duplicateGroups.isEmpty()) {
            cardDups.addView(TextView(this).apply {
                text = "Yinelenen dosya bulunamadı."
                textSize = 13f
                setTextColor(secondaryColor)
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            })
        } else {
            for (grp in report.duplicateGroups.take(4)) {
                val first = grp.first()
                cardDups.addView(
                    createCardFileItemRow(
                        item = first,
                        subtitle = "/${first.parentId}",
                        rightText = FormatUtils.formatSize(first.size),
                        onClick = { openAnalyzedItem(first) }
                    )
                )
            }
        }

        addCardBottomButtons(
            card = cardDups,
            leftBtnText = if (allDupCopies.isNotEmpty()) "SİL" else null,
            onLeftClick = if (allDupCopies.isNotEmpty()) {
                { confirmAndDeleteItems(allDupCopies) }
            } else null,
            rightBtnText = "DAHA FAZLA"
        ) {
            if (allDupCopies.isNotEmpty()) {
                showMultiSelectDeleteDialog("Yinelenen Dosyalar", allDupCopies)
            } else {
                Toast.makeText(this, "Yinelenen dosya yok.", Toast.LENGTH_SHORT).show()
            }
        }
        contentRoot.addView(cardDups)
    }

    private fun openCategory(catKey: String, title: String) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "CATEGORY")
            putExtra("CATEGORY", catKey)
            putExtra("TITLE", title)
            putExtra("PREFER_GRID", true)
        })
    }

    private fun createCardFileItemRow(
        item: FileItem,
        subtitle: String,
        rightText: String,
        onClick: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val tealAccent = Color.parseColor("#008578")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pV = (5 * density).toInt()
            setPadding(0, pV, 0, pV)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iv = ImageView(this).apply {
            val sz = (24 * density).toInt()
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

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(this).apply {
            text = item.name
            textSize = 13.5f
            setTextColor(primaryColor)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(secondaryColor)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }
        col.addView(tvName)
        col.addView(tvSub)

        val tvRight = TextView(this).apply {
            text = rightText
            textSize = 13f
            setTextColor(tealAccent)
            setPadding((8 * density).toInt(), 0, 0, 0)
        }

        row.addView(iv)
        row.addView(col)
        row.addView(tvRight)
        return row
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

    private fun openAnalyzedItem(item: FileItem) {
        if (item.isDirectory) {
            openFolderDrillDownState(item.path)
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
        val checked = BooleanArray(displayList.size) { true }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("SEÇİLENLERİ SİL") { _, _ ->
                val toDelete = displayList.filterIndexed { index, _ -> checked[index] }
                if (toDelete.isNotEmpty()) {
                    confirmAndDeleteItems(toDelete)
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun confirmAndDeleteItems(items: List<FileItem>) {
        val totalBytes = items.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Sil (${items.size} öğe • ${FormatUtils.formatSize(totalBytes)})")
            .setMessage("Seçilen ${items.size} öğeyi silip ${FormatUtils.formatSize(totalBytes)} alan açmak istediğinize emin misiniz?")
            .setPositiveButton("TAMAM") { _, _ ->
                lifecycleScope.launch {
                    val deleted = LocalFileRepository.deleteFiles(
                        items = items,
                        moveToRecycleBin = false,
                        context = this@StorageAnalysisActivity
                    )
                    Toast.makeText(
                        this@StorageAnalysisActivity,
                        "$deleted öğe silindi (${FormatUtils.formatSize(totalBytes)} alan açıldı).",
                        Toast.LENGTH_SHORT
                    ).show()
                    runAnalysis()
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun handleBackNavigation() {
        if (viewState == 1) {
            if (drillDownStack.size > 1) {
                drillDownStack.pop()
                loadDrillDownDirectory(drillDownStack.peek())
                return
            } else {
                showMainCardsState()
                return
            }
        } else if (viewState == 2) {
            showMainCardsState()
            return
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    /**
     * Video 5'teki (00:09 - 00:23) Orijinal Klasör/Dosya Boyut Çubuğu Çizicisi (clip_storage_total & clip_current_folder):
     * - Yeşil çubuk (#AED581 açık temada / #33691E koyu temada): Bulunduğu klasör içindeki oranı
     * - Mavi çubuk (#4FC3F7 açık temada / #0277BD koyu temada): Toplam depolama içindeki oranı
     */
    private class ProportionalSizeBarDrawable(
        private val folderRatio: Float,
        private val totalStorageRatio: Float,
        private val isDarkTheme: Boolean
    ) : Drawable() {

        private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkTheme) Color.parseColor("#557CB342") else Color.parseColor("#99AED581")
        }
        private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkTheme) Color.parseColor("#6629B6F6") else Color.parseColor("#994FC3F7")
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val greenW = (w * folderRatio.coerceIn(0f, 1f))
            val blueW = (w * totalStorageRatio.coerceIn(0f, 1f)).coerceAtMost(greenW)

            if (greenW > 1f) {
                canvas.drawRect(0f, 0f, greenW, h, greenPaint)
            }
            if (blueW > 1f) {
                canvas.drawRect(0f, 0f, blueW, h, bluePaint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Video 5'teki (00:09 - 00:23 & 00:30 - 00:33) Klasör İçi Boyut Gezgini ve Büyük Dosyalar Adapter'ı
     */
    private inner class DrillDownSizeAdapter(
        private val onItemClick: (FileItem) -> Unit,
        private val onItemLongClick: (FileItem) -> Unit,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<DrillDownSizeAdapter.VH>() {

        private val items = ArrayList<FileItem>()
        private val selectedIds = HashSet<String>()
        private var parentTotalBytes: Long = 1L
        private var volumeTotalBytes: Long = 1L
        private var showRelativePath: Boolean = false
        var showCheckboxesAlways: Boolean = false

        fun submitDrillDownList(
            newList: List<FileItem>,
            parentTotalBytes: Long,
            volumeTotalBytes: Long,
            showRelativePath: Boolean
        ) {
            items.clear()
            items.addAll(newList)
            this.parentTotalBytes = parentTotalBytes.coerceAtLeast(1L)
            this.volumeTotalBytes = volumeTotalBytes.coerceAtLeast(1L)
            this.showRelativePath = showRelativePath
            notifyDataSetChanged()
        }

        fun toggleSelection(item: FileItem) {
            if (selectedIds.contains(item.id)) selectedIds.remove(item.id)
            else selectedIds.add(item.id)
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun clearSelection() {
            selectedIds.clear()
            notifyDataSetChanged()
        }

        fun getSelectedItems(): List<FileItem> = items.filter { selectedIds.contains(it.id) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (58 * density).toInt()
                )
                val pH = (10 * density).toInt()
                setPadding(pH, (4 * density).toInt(), pH, (4 * density).toInt())
            }

            val ivIcon = ImageView(ctx).apply {
                val sz = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (10 * density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val barBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = (8 * density).toInt()
                }
                val p = (6 * density).toInt()
                setPadding(p, (2 * density).toInt(), p, (2 * density).toInt())
            }

            val tvName = TextView(ctx).apply {
                textSize = 14.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }

            val tvSub = TextView(ctx).apply {
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }

            barBox.addView(tvName)
            barBox.addView(tvSub)

            val tvRightPctOrDate = TextView(ctx).apply {
                textSize = 12f
                gravity = Gravity.END
            }

            val cb = CheckBox(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (6 * density).toInt()
                }
            }

            row.addView(ivIcon)
            row.addView(barBox)
            row.addView(tvRightPctOrDate)
            row.addView(cb)

            val div = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (0.7f * density).toInt().coerceAtLeast(1)
                )
            }

            root.addView(row)
            root.addView(div)
            return VH(root, ivIcon, barBox, tvName, tvSub, tvRightPctOrDate, cb, div)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val isDark = ThemeManager.isDarkOrAmoled(ctx)
            val primaryColor = ThemeManager.getPrimaryTextColor(ctx)
            val secondaryColor = ThemeManager.getSecondaryTextColor(ctx)

            holder.tvName.text = item.name
            holder.tvName.setTextColor(primaryColor)

            if (showRelativePath) {
                // Büyük dosyalar ekranı (Video 5 00:30-00:33): "3,21 GB  /sa" + sağda tarih ve onay kutusu
                val relFolder = if (item.parentId.isNotBlank()) "/${item.parentId}/" else "/"
                holder.tvSub.text = "${FormatUtils.formatSizeDetailed(item.size)}   $relFolder"
                holder.tvSub.setTextColor(secondaryColor)
                holder.tvRight.text = FormatUtils.formatDate(item.lastModified)
                holder.tvRight.setTextColor(secondaryColor)
                holder.barBox.background = null
            } else {
                // Klasör Boyutu Gezgini (Video 5 00:09-00:23): "105 öğe (13,61 GB)" + Yeşil/Mavi Çubuk + Sağda "12,07%"
                val sizeStr = FormatUtils.formatSizeDetailed(item.size)
                holder.tvSub.text = if (item.isDirectory) {
                    "${item.childCount} öğe ($sizeStr)"
                } else {
                    sizeStr
                }
                holder.tvSub.setTextColor(primaryColor)

                val pctOfStorage = (item.size.toDouble() * 100.0) / volumeTotalBytes.toDouble()
                holder.tvRight.text = String.format(Locale("tr", "TR"), "%.2f%%", pctOfStorage)
                holder.tvRight.setTextColor(secondaryColor)

                val folderRatio = (item.size.toDouble() / parentTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                val storageRatio = (item.size.toDouble() / volumeTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                holder.barBox.background = ProportionalSizeBarDrawable(folderRatio, storageRatio, isDark)
            }

            holder.div.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            val fallbackIcon = when {
                item.isDirectory -> {
                    val lower = item.name.lowercase()
                    when {
                        lower == "download" || lower == "downloads" -> R.drawable.icon_folder_download
                        lower == "dcim" || lower == "camera" -> R.drawable.icon_folder_camera
                        item.childCount == 0 -> R.drawable.icon_folder_empty
                        else -> R.drawable.icon_folder_full
                    }
                }
                item.isApk -> R.drawable.ic_file_apk
                item.isVideo -> R.drawable.icon_file_video
                item.isImage -> R.drawable.icon_file_image
                item.isAudio -> R.drawable.icon_file_music
                item.isArchive -> R.drawable.icon_file_archive
                else -> R.drawable.icon_file_document
            }

            if (!item.isDirectory && (item.isVideo || item.isImage)) {
                ThumbnailCacheManager.loadThumbnailByPath(
                    path = item.path,
                    lastModified = item.lastModified,
                    isVideo = item.isVideo,
                    imageView = holder.ivIcon,
                    fallbackRes = fallbackIcon
                )
            } else {
                holder.ivIcon.tag = null
                holder.ivIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.ivIcon.setImageResource(fallbackIcon)
            }

            val isSel = selectedIds.contains(item.id)
            holder.cb.visibility = if (showCheckboxesAlways || selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = isSel
            holder.cb.setOnCheckedChangeListener { _, _ ->
                toggleSelection(item)
            }

            holder.root.setOnClickListener {
                if (selectedIds.isNotEmpty() && !showCheckboxesAlways) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }
            holder.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        inner class VH(
            val root: LinearLayout,
            val ivIcon: ImageView,
            val barBox: LinearLayout,
            val tvName: TextView,
            val tvSub: TextView,
            val tvRight: TextView,
            val cb: CheckBox,
            val div: View
        ) : RecyclerView.ViewHolder(root)
    }
}
