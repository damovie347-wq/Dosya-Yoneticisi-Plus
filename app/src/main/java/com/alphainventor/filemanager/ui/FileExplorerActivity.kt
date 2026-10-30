package com.alphainventor.filemanager.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityFileExplorerBinding
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.CloudProviderType
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.model.RemoteAccount
import com.alphainventor.filemanager.remote.RemoteManager
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.ui.adapter.FileListAdapter
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.viewer.ImageViewerActivity
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.TextEditorActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Stack

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Dosya, Medya Kategorileri ve Bulut Gezgini:
 * - Videolar, Görüntüler, Ses ve Belgeler kategorilerinde önce 1:1 Kare Klasör Kutucukları (Screen recordings (8), Movies (6) vb.) gösterilir, klasöre tıklanınca o klasördeki medya dosyaları açılır.
 * - Orijinal APK'daki Lax/O1/b; ve viewer/c-e önbellek mimarisiyle klasör ve kategori geçişleri 0 ms'de (loading spinner çıkmadan) gerçekleşir.
 * - Google Drive hesabında henüz geçerli bir token yoksa boş ekranda takılmak yerine doğrudan CloudAccountsActivity (AUTO_AUTH_ACCOUNT_ID) üzerinden şifresiz MergeSession yetkilendirmesini başlatır.
 * - MEGA bulut dosyalarına tıklandığında AES-CTR ile çözülerek yerel önbelleğe indirilir ve dahili oynatıcıda açılır.
 */
class FileExplorerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileExplorerBinding
    private lateinit var adapter: FileListAdapter

    private var mode: String = "LOCAL" // LOCAL, CATEGORY, CLOUD, REMOTE
    private var currentPath: String = ""
    private var localVolumeRootPath: String = ""
    private var localVolumeTitle: String = "Ana bellek"
    private var categoryName: String = ""
    private var selectedBucketPath: String = ""
    private var selectedBucketName: String = ""
    private var cloudAccount: CloudAccount? = null
    private var loadDirectoryJob: Job? = null
    private var remoteAccount: RemoteAccount? = null

    private data class CloudCrumb(val id: String, val name: String)
    private val cloudNavStack = Stack<CloudCrumb>()
    private var fullCurrentItems: List<FileItem> = emptyList()
    private var pendingExtractZipItem: FileItem? = null

    private val driveConsentLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadCurrentDirectory(forceRefresh = true)
        } else {
            Toast.makeText(this, "Google Drive erişim izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("MODE") ?: "LOCAL"
        currentPath = intent.getStringExtra("PATH") ?: LocalFileRepository.rootStorageDir.absolutePath
        categoryName = intent.getStringExtra("CATEGORY") ?: ""
        val preferGrid = intent.getBooleanExtra("PREFER_GRID", false)

        @Suppress("DEPRECATION")
        cloudAccount = intent.getSerializableExtra("CLOUD_ACCOUNT") as? CloudAccount
        @Suppress("DEPRECATION")
        remoteAccount = intent.getSerializableExtra("REMOTE_ACCOUNT") as? RemoteAccount

        binding.btnSortAndView.setImageResource(R.drawable.ic_ab_view_settings)

        setupToolbarAndActions()
        setupRecyclerView(preferGrid)
        applyCurrentTheme()

        when (mode) {
            "CLOUD" -> {
                val acc = cloudAccount
                if (acc == null) {
                    finish()
                    return
                }
                val initFolder = intent.getStringExtra("INITIAL_FOLDER_ID") ?: "root"
                val initTitle = intent.getStringExtra("TITLE") ?: acc.provider.displayName
                cloudNavStack.push(CloudCrumb(initFolder, initTitle))
                binding.tvExplorerTitle.text = acc.provider.displayName
                binding.tvExplorerSubtitle.visibility = View.VISIBLE
                binding.tvExplorerSubtitle.text = acc.emailOrName
                binding.ivRootTypeCrumb.setImageResource(
                    when (acc.provider) {
                        CloudProviderType.GOOGLE_DRIVE -> R.drawable.cloud_icon_googledrive
                        CloudProviderType.MEGA -> R.drawable.ic_cloud_mega
                        CloudProviderType.DROPBOX -> R.drawable.cloud_icon_dropbox
                        CloudProviderType.ONEDRIVE -> R.drawable.cloud_icon_onedrive
                        CloudProviderType.BOX -> R.drawable.cloud_icon_box
                        CloudProviderType.NEXTCLOUD -> R.drawable.ic_cloud_nextcloud
                        CloudProviderType.YANDEX -> R.drawable.cloud_icon_yandex
                    }
                )
                binding.ivRootTypeCrumb.setOnClickListener {
                    if (cloudNavStack.size > 1) {
                        while (cloudNavStack.size > 1) cloudNavStack.pop()
                        loadCurrentDirectory(forceRefresh = false)
                    }
                }
                binding.tvStorageBadge.visibility = View.VISIBLE
                updateCloudQuotaLabel(acc)
                binding.btnParentFolderUp.visibility = View.GONE
            }
            "REMOTE" -> {
                val rem = remoteAccount
                if (rem == null) {
                    finish()
                    return
                }
                currentPath = rem.initialPath.ifBlank { "/" }
                binding.tvExplorerTitle.text = rem.displayName
                binding.tvExplorerSubtitle.visibility = View.VISIBLE
                binding.tvExplorerSubtitle.text = "${rem.protocol.displayName} • ${rem.host}:${rem.port}"
                binding.ivRootTypeCrumb.setImageResource(R.drawable.icon_folder_lan_s)
                binding.ivRootTypeCrumb.setOnClickListener {
                    if (currentPath != "/") {
                        currentPath = "/"
                        loadCurrentDirectory()
                    }
                }
                binding.tvStorageBadge.visibility = View.GONE
                binding.btnParentFolderUp.visibility = View.VISIBLE
            }
            "CATEGORY" -> {
                val title = intent.getStringExtra("TITLE") ?: categoryName
                binding.tvExplorerTitle.text = title
                binding.tvExplorerSubtitle.visibility = View.GONE
                binding.ivRootTypeCrumb.setImageResource(
                    when (categoryName) {
                        "VIDEOS" -> R.drawable.icon_file_video_s
                        "IMAGES" -> R.drawable.icon_file_image_s
                        "AUDIO" -> R.drawable.icon_file_music_s
                        "DOCUMENTS" -> R.drawable.icon_file_document_s
                        else -> R.drawable.icon_main_storage_s
                    }
                )
                binding.ivRootTypeCrumb.setOnClickListener {
                    if (selectedBucketPath.isNotEmpty()) {
                        selectedBucketPath = ""
                        selectedBucketName = ""
                        loadCurrentDirectory(forceRefresh = false)
                    }
                }
                binding.tvStorageBadge.visibility = View.GONE
                binding.btnParentFolderUp.visibility = View.VISIBLE
                applySavedViewModeForCurrentLocation()
            }
            else -> {
                val title = intent.getStringExtra("TITLE") ?: "Ana bellek"
                localVolumeTitle = title
                val mainRoot = LocalFileRepository.rootStorageDir.absolutePath
                val removable = LocalFileRepository.getRemovableStorageVolumes(this)
                    .firstOrNull { currentPath.startsWith(it.path) }
                if (removable != null) {
                    localVolumeRootPath = removable.path
                    localVolumeTitle = removable.title
                    binding.ivRootTypeCrumb.setImageResource(
                        if (removable.isUsb) R.drawable.icon_usb_storage_s else R.drawable.icon_sd_s
                    )
                } else {
                    localVolumeRootPath = mainRoot
                    binding.ivRootTypeCrumb.setImageResource(R.drawable.icon_main_storage_s)
                }
                binding.ivRootTypeCrumb.setOnClickListener {
                    val rootP = localVolumeRootPath.ifBlank { mainRoot }
                    if (currentPath != rootP) {
                        currentPath = rootP
                        loadCurrentDirectory()
                    }
                }

                binding.tvExplorerTitle.text = title
                binding.tvExplorerSubtitle.visibility = View.GONE
                updateLocalStorageUsedPill()
                binding.btnParentFolderUp.visibility = View.VISIBLE
            }
        }

        applySavedViewModeForCurrentLocation()
        loadCurrentDirectory(forceRefresh = false)
    }

    private fun currentLocationPreferenceKey(): String {
        return when (mode) {
            "CATEGORY" -> if (selectedBucketPath.isEmpty()) "CATEGORY:$categoryName" else "CATEGORY_BUCKET:$categoryName:$selectedBucketPath"
            "CLOUD" -> "CLOUD:${cloudAccount?.id}:${if (cloudNavStack.isEmpty()) "root" else cloudNavStack.peek().id}"
            "REMOTE" -> "REMOTE:${remoteAccount?.id}:$currentPath"
            else -> "LOCAL:$currentPath"
        }
    }

    private fun applySavedViewModeForCurrentLocation() {
        val defaultViewMode = if (mode == "CATEGORY" && categoryName in setOf("VIDEOS", "IMAGES", "AUDIO", "DOCUMENTS")) 1 else AppSettings.viewTypeMode
        val locKey = currentLocationPreferenceKey()
        val savedViewMode = AppSettings.getViewTypeForLocation(locKey, defaultViewMode)
        val savedIconSize = AppSettings.getIconSizeForLocation(locKey)
        AppSettings.iconSizeMode = savedIconSize
        val (savedSortBy, savedSortAsc) = AppSettings.getSortForLocation(locKey)
        AppSettings.sortBy = savedSortBy
        AppSettings.sortAscending = savedSortAsc
        adapter.isGridMode = (savedViewMode == 1)
        adapter.isCompactListMode = (savedViewMode == 2)
        updateRecyclerLayoutManager()
    }

    private fun updateLocalStorageUsedPill() {
        val rootPath = localVolumeRootPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
        val overview = LocalFileRepository.getStorageOverviewForPath(rootPath)
        binding.tvStorageBadge.visibility = View.VISIBLE
        binding.tvStorageBadge.background = ThemeManager.createUsedPillBackground(this)
        val density = resources.displayMetrics.density
        val hPad = (10 * density).toInt()
        val vPad = (4 * density).toInt()
        binding.tvStorageBadge.setPadding(hPad, vPad, hPad, vPad)
        binding.tvStorageBadge.setTextColor(ThemeManager.getPrimaryTextColor(this))
        val pieDr = ContextCompat.getDrawable(this, R.drawable.icon_analysis_s)?.mutate()
        val pieSz = (18 * density).toInt()
        pieDr?.setBounds(0, 0, pieSz, pieSz)
        binding.tvStorageBadge.setCompoundDrawables(pieDr, null, null, null)
        binding.tvStorageBadge.compoundDrawablePadding = (6 * density).toInt()
        binding.tvStorageBadge.text = "KULLANILAN ${overview.usedPercent}%"
        binding.tvStorageBadge.setOnClickListener {
            startActivity(Intent(this, StorageAnalysisActivity::class.java).apply {
                putExtra("TARGET_PATH", rootPath)
                putExtra("TARGET_TITLE", localVolumeTitle)
            })
        }
    }

    private fun updateCloudQuotaLabel(acc: CloudAccount) {
        binding.tvStorageBadge.background = null
        binding.tvStorageBadge.setCompoundDrawables(null, null, null, null)
        binding.tvStorageBadge.setOnClickListener(null)
        binding.tvStorageBadge.setTextColor(ThemeManager.getSecondaryTextColor(this))
        if (acc.usedQuotaBytes > 0L || acc.totalQuotaBytes > 0L) {
            binding.tvStorageBadge.text = "Boş ${FormatUtils.formatSize(acc.freeQuotaBytes)}"
        } else {
            binding.tvStorageBadge.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
    }

    private fun setupRecyclerView(initialGrid: Boolean) {
        adapter = FileListAdapter(
            onItemClick = { item -> onFileItemClicked(item) },
            onItemLongClick = { item ->
                adapter.toggleSelection(item)
                updateSelectionUi()
            },
            showAccountOwnerBadge = false
        )
        adapter.isGridMode = initialGrid || AppSettings.viewTypeMode == 1
        adapter.isCompactListMode = AppSettings.viewTypeMode == 2
        updateRecyclerLayoutManager()
        binding.rvFileList.adapter = adapter
        binding.rvFileList.setHasFixedSize(true)
        binding.rvFileList.setItemViewCacheSize(28)

        binding.swipeRefreshFiles.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_teal))
        binding.swipeRefreshFiles.setOnRefreshListener {
            loadCurrentDirectory(forceRefresh = true)
        }
    }

    private fun updateRecyclerLayoutManager() {
        if (adapter.isGridMode) {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density
            val isLargeIcons = AppSettings.iconSizeMode == 1
            val spans = when {
                widthDp >= 720f -> if (isLargeIcons) 6 else 8
                widthDp >= 540f -> if (isLargeIcons) 4 else 6
                else -> if (isLargeIcons) 3 else 4
            }
            binding.rvFileList.layoutManager = GridLayoutManager(this, spans)
        } else {
            binding.rvFileList.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun setupToolbarAndActions() {
        binding.btnBack.setOnClickListener {
            handleBackNavigation()
        }

        binding.ivHomeCrumb.setOnClickListener {
            finish()
        }

        binding.btnParentFolderUp.setOnClickListener {
            handleBackNavigation()
        }

        binding.btnSearchInFolder.setOnClickListener {
            if (binding.llInlineSearchBar.visibility == View.VISIBLE) {
                binding.etInlineSearch.setText("")
                binding.llInlineSearchBar.visibility = View.GONE
            } else {
                binding.llInlineSearchBar.visibility = View.VISIBLE
                binding.etInlineSearch.requestFocus()
            }
        }

        binding.btnCloseInlineSearch.setOnClickListener {
            binding.etInlineSearch.setText("")
            binding.llInlineSearchBar.visibility = View.GONE
        }

        binding.etInlineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                adapter.highlightQuery = q
                if (q.isEmpty()) {
                    adapter.submitList(fullCurrentItems)
                } else {
                    adapter.submitList(fullCurrentItems.filter { it.name.contains(q, ignoreCase = true) })
                }
            }
        })

        binding.btnSortAndView.setOnClickListener {
            showOriginalViewSortBottomSheet()
        }

        binding.btnExplorerMore.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.menu_new_folder))
            popup.menu.add(0, 2, 1, getString(R.string.menu_new_file))
            popup.menu.add(0, 3, 2, if (AppSettings.showHiddenFiles) "Gizli Dosyaları Gizle" else "Gizli Dosyaları Göster")
            popup.menu.add(0, 4, 3, getString(R.string.action_select_all))
            popup.menu.add(0, 5, 4, getString(R.string.menu_refresh))
            popup.menu.add(0, 6, 5, getString(R.string.settings_title))
            popup.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> showCreateNewDialog(isFolder = true)
                    2 -> showCreateNewDialog(isFolder = false)
                    3 -> {
                        AppSettings.showHiddenFiles = !AppSettings.showHiddenFiles
                        loadCurrentDirectory(forceRefresh = true)
                    }
                    4 -> {
                        adapter.selectAll()
                        updateSelectionUi()
                    }
                    5 -> loadCurrentDirectory(forceRefresh = true)
                    6 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            }
            popup.show()
        }

        binding.btnActionCopy.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                LocalFileRepository.clipboardFiles = selected
                LocalFileRepository.clipboardIsMove = false
                adapter.clearSelection()
                updateSelectionUi()
                showPasteBar()
            }
        }

        binding.btnActionMove.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                LocalFileRepository.clipboardFiles = selected
                LocalFileRepository.clipboardIsMove = true
                adapter.clearSelection()
                updateSelectionUi()
                showPasteBar()
            }
        }

        binding.btnActionRename.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.size == 1) {
                showRenameDialog(selected.first())
            } else {
                Toast.makeText(this, "Yeniden adlandırmak için tek bir öğe seçin.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnActionDelete.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                showDeleteConfirmDialog(selected)
            }
        }

        binding.btnActionMore.setOnClickListener { view ->
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener
            showOriginalBottomMorePopup(view, selected)
        }

        binding.btnCancelPaste.setOnClickListener {
            LocalFileRepository.clipboardFiles = emptyList()
            pendingExtractZipItem = null
            binding.btnConfirmPaste.text = getString(R.string.action_paste)
            binding.pasteActionBar.visibility = View.GONE
        }

        binding.btnConfirmPaste.setOnClickListener {
            val zipToExtract = pendingExtractZipItem
            if (zipToExtract != null && mode == "LOCAL") {
                pendingExtractZipItem = null
                binding.btnConfirmPaste.text = getString(R.string.action_paste)
                binding.pasteActionBar.visibility = View.GONE
                extractZipToDirectory(zipToExtract, File(currentPath))
                return@setOnClickListener
            }
            if (mode == "LOCAL") {
                lifecycleScope.launch {
                    binding.pbLoadingFiles.visibility = View.VISIBLE
                    val count = LocalFileRepository.pasteClipboardTo(File(currentPath))
                    binding.pbLoadingFiles.visibility = View.GONE
                    binding.pasteActionBar.visibility = View.GONE
                    Toast.makeText(this@FileExplorerActivity, "$count öğe aktarıldı.", Toast.LENGTH_SHORT).show()
                    loadCurrentDirectory(forceRefresh = true)
                }
            }
        }
    }

    private fun showOriginalViewSortBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val density = resources.displayMetrics.density
        val bgColor = ThemeManager.getCardColor(this)
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val dividerColor = ThemeManager.getDividerColor(this)
        val tealColor = Color.parseColor("#008578")
        val tintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(tealColor, secondaryColor)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(0, (6 * density).toInt(), 0, (12 * density).toInt())
        }
        scroll.addView(root)

        fun addDivider() {
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt())
                setBackgroundColor(dividerColor)
            })
        }

        fun addSectionTitle(title: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(tealColor)
                val pH = (20 * density).toInt()
                val pTop = (14 * density).toInt()
                val pBot = (6 * density).toInt()
                setPadding(pH, pTop, pH, pBot)
            })
        }

        val rowApplyAll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pH = (20 * density).toInt()
            val pV = (12 * density).toInt()
            setPadding(pH, pV, pH, pV)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
        }
        val tvApplyAll = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Tüm klasörlere uygula"
            textSize = 15.5f
            setTextColor(primaryColor)
        }
        val cbApplyAll = CheckBox(this).apply {
            isChecked = AppSettings.applyViewToAllFolders
            CompoundButtonCompat.setButtonTintList(this, tintList)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.applyViewToAllFolders = checked
            }
        }
        rowApplyAll.setOnClickListener { cbApplyAll.isChecked = !cbApplyAll.isChecked }
        rowApplyAll.addView(tvApplyAll)
        rowApplyAll.addView(cbApplyAll)
        root.addView(rowApplyAll)

        addDivider()

        addSectionTitle("Görünüm")

        val pH = (20 * density).toInt()
        val rgViewType = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pH, (4 * density).toInt(), pH, (4 * density).toInt())
        }

        fun createIconRadioButton(idVal: Int, drawableRes: Int, checked: Boolean): RadioButton {
            return RadioButton(this).apply {
                id = idVal
                layoutParams = RadioGroup.LayoutParams(0, (48 * density).toInt(), 1f)
                isChecked = checked
                CompoundButtonCompat.setButtonTintList(this, tintList)
                val dr = ContextCompat.getDrawable(this@FileExplorerActivity, drawableRes)?.mutate()
                dr?.setTint(if (ThemeManager.isDarkOrAmoled(this@FileExplorerActivity)) Color.WHITE else Color.parseColor("#212121"))
                setCompoundDrawablesWithIntrinsicBounds(dr, null, null, null)
                compoundDrawablePadding = (10 * density).toInt()
            }
        }

        val curViewMode = if (adapter.isGridMode) 1 else if (adapter.isCompactListMode) 2 else 0
        val rbList = createIconRadioButton(1001, R.drawable.v_shape_listview, curViewMode == 0)
        val rbGrid = createIconRadioButton(1002, R.drawable.v_shape_gridview, curViewMode == 1)
        val rbDetail = createIconRadioButton(1003, R.drawable.v_shape_detailview, curViewMode == 2)
        rgViewType.addView(rbList)
        rgViewType.addView(rbGrid)
        rgViewType.addView(rbDetail)

        rgViewType.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                1001 -> 0
                1002 -> 1
                1003 -> 2
                else -> 0
            }
            AppSettings.setViewTypeForLocation(currentLocationPreferenceKey(), newMode, cbApplyAll.isChecked)
            adapter.isGridMode = (newMode == 1)
            adapter.isCompactListMode = (newMode == 2)
            updateRecyclerLayoutManager()
            adapter.notifyDataSetChanged()
            sheet.dismiss()
        }
        root.addView(rgViewType)

        val rgIconSize = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pH, (2 * density).toInt(), pH, (8 * density).toInt())
        }
        val curSizeMode = AppSettings.iconSizeMode
        val rbMediumSize = createIconRadioButton(2001, R.drawable.v_shape_icon_medium, curSizeMode == 0)
        val rbLargeSize = createIconRadioButton(2002, R.drawable.v_shape_icon_large, curSizeMode == 1)
        val spacerView = View(this).apply {
            layoutParams = RadioGroup.LayoutParams(0, (48 * density).toInt(), 1f)
            visibility = View.INVISIBLE
        }
        rgIconSize.addView(rbMediumSize)
        rgIconSize.addView(rbLargeSize)
        rgIconSize.addView(spacerView)

        rgIconSize.setOnCheckedChangeListener { _, checkedId ->
            val newSize = if (checkedId == 2002) 1 else 0
            AppSettings.setIconSizeForLocation(currentLocationPreferenceKey(), newSize, cbApplyAll.isChecked)
            updateRecyclerLayoutManager()
            adapter.notifyDataSetChanged()
            sheet.dismiss()
        }
        root.addView(rgIconSize)

        addDivider()

        addSectionTitle("Sırala")

        val rowSort = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pV = (12 * density).toInt()
            setPadding(pH, pV, pH, pV)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
        }
        val tvSortCurrent = TextView(this).apply {
            text = getCurrentSortDisplayLabel()
            textSize = 15.5f
            setTextColor(primaryColor)
        }
        rowSort.addView(tvSortCurrent)
        rowSort.setOnClickListener {
            showOriginalSortCriteriaDialog(
                onSortSelected = {
                    tvSortCurrent.text = getCurrentSortDisplayLabel()
                    sheet.dismiss()
                    loadCurrentDirectory(forceRefresh = false)
                }
            )
        }
        root.addView(rowSort)

        addDivider()

        addSectionTitle("Diğerleri")

        val rowShowHidden = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pV = (12 * density).toInt()
            setPadding(pH, pV, pH, pV)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
        }
        val tvShowHidden = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Gizli dosyaları göster"
            textSize = 15.5f
            setTextColor(primaryColor)
        }
        val cbShowHidden = CheckBox(this).apply {
            isChecked = AppSettings.showHiddenFiles
            CompoundButtonCompat.setButtonTintList(this, tintList)
            setOnCheckedChangeListener { _, checked ->
                if (AppSettings.showHiddenFiles != checked) {
                    AppSettings.showHiddenFiles = checked
                    sheet.dismiss()
                    loadCurrentDirectory(forceRefresh = true)
                }
            }
        }
        rowShowHidden.setOnClickListener {
            cbShowHidden.isChecked = !cbShowHidden.isChecked
        }
        rowShowHidden.addView(tvShowHidden)
        rowShowHidden.addView(cbShowHidden)
        root.addView(rowShowHidden)

        if (mode == "LOCAL") {
            val rowThumbs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pV = (12 * density).toInt()
                setPadding(pH, pV, pH, pV)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
            val tvThumbs = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "Küçük resimleri göster"
                textSize = 15.5f
                setTextColor(primaryColor)
            }
            val cbThumbs = CheckBox(this).apply {
                isChecked = AppSettings.showThumbnails
                CompoundButtonCompat.setButtonTintList(this, tintList)
                setOnCheckedChangeListener { _, checked ->
                    AppSettings.showThumbnails = checked
                    adapter.notifyDataSetChanged()
                }
            }
            rowThumbs.setOnClickListener { cbThumbs.isChecked = !cbThumbs.isChecked }
            rowThumbs.addView(tvThumbs)
            rowThumbs.addView(cbThumbs)
            root.addView(rowThumbs)
        }

        sheet.setContentView(scroll)
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.peekHeight = resources.displayMetrics.heightPixels
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.show()
    }

    private fun getCurrentSortDisplayLabel(): String {
        val arrow = if (AppSettings.sortAscending) "▲" else "▼"
        return when (AppSettings.sortBy) {
            4 -> "Sıralama yok"
            0 -> "Ad $arrow"
            1 -> "Boyut $arrow"
            2 -> "Tarih $arrow"
            5 -> "Çekim Tarihi $arrow"
            3 -> "Tür $arrow"
            else -> "Tarih ▼"
        }
    }

    private fun showOriginalSortCriteriaDialog(onSortSelected: () -> Unit) {
        data class SortOption(val label: String, val sortBy: Int, val asc: Boolean)

        val options = ArrayList<SortOption>().apply {
            add(SortOption("Sıralama yok", 4, false))
            add(SortOption("Ad ▲", 0, true))
            add(SortOption("Ad ▼", 0, false))
            add(SortOption("Boyut ▲", 1, true))
            add(SortOption("Boyut ▼", 1, false))
            add(SortOption("Tarih ▲", 2, true))
            add(SortOption("Tarih ▼", 2, false))
            if (categoryName == "IMAGES") {
                add(SortOption("Çekim Tarihi ▲", 5, true))
                add(SortOption("Çekim Tarihi ▼", 5, false))
            }
            add(SortOption("Tür ▲", 3, true))
            add(SortOption("Tür ▼", 3, false))
        }

        val labels = options.map { it.label }.toTypedArray()
        val currentIdx = options.indexOfFirst {
            if (it.sortBy == 4) AppSettings.sortBy == 4
            else it.sortBy == AppSettings.sortBy && it.asc == AppSettings.sortAscending
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Sıralama ölçütü")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val chosen = options[which]
                AppSettings.setSortForLocation(currentLocationPreferenceKey(), chosen.sortBy, chosen.asc, AppSettings.applyViewToAllFolders)
                dialog.dismiss()
                onSortSelected()
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun updateSelectionUi() {
        val selected = adapter.getSelectedItems()
        if (adapter.selectionMode && selected.isNotEmpty()) {
            binding.bottomActionBar.visibility = View.VISIBLE
            binding.tvExplorerTitle.text = "${selected.size}/${fullCurrentItems.size}"
        } else {
            binding.bottomActionBar.visibility = View.GONE
            binding.tvExplorerTitle.text = when (mode) {
                "CLOUD" -> cloudAccount?.provider?.displayName ?: "Bulut"
                "REMOTE" -> remoteAccount?.displayName ?: "Uzak"
                "CATEGORY" -> intent.getStringExtra("TITLE") ?: categoryName
                else -> {
                    val rootPath = localVolumeRootPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
                    if (currentPath == rootPath) localVolumeTitle else File(currentPath).name
                }
            }
        }
    }

    private fun showPasteBar() {
        val clip = LocalFileRepository.clipboardFiles
        if (clip.isNotEmpty()) {
            binding.pasteActionBar.visibility = View.VISIBLE
            val verb = if (LocalFileRepository.clipboardIsMove) "taşınacak" else "kopyalandı"
            binding.tvClipboardStatus.text = "${clip.size} öğe $verb"
        }
    }

    private fun loadCurrentDirectory(forceRefresh: Boolean = false) {
        loadDirectoryJob?.cancel()
        applySavedViewModeForCurrentLocation()
        renderBreadcrumbs()

        if (!forceRefresh) {
            val instantCached: List<FileItem>? = when (mode) {
                "CLOUD" -> {
                    val acc = cloudAccount
                    val expectedFolderId = if (cloudNavStack.isEmpty()) "root" else cloudNavStack.peek().id
                    if (acc != null) CloudManager.getCachedCloudFolder(acc.id, expectedFolderId) else null
                }
                "LOCAL", "CATEGORY" -> {
                    if (mode == "LOCAL") updateLocalStorageUsedPill()
                    LocalFileRepository.getCachedExplorerItems(mode, currentPath, categoryName, selectedBucketPath)
                }
                else -> null
            }

            if (instantCached != null) {
                fullCurrentItems = instantCached
                adapter.submitList(instantCached)
                binding.pbLoadingFiles.visibility = View.GONE
                binding.swipeRefreshFiles.isRefreshing = false
                binding.llEmptyFolder.visibility = if (instantCached.isEmpty()) View.VISIBLE else View.GONE
                if (instantCached.isEmpty()) {
                    binding.tvEmptyFolder.text = getString(R.string.empty_folder)
                }
                updateSelectionUi()
                return
            }
        }

        binding.pbLoadingFiles.visibility = View.VISIBLE
        binding.llEmptyFolder.visibility = View.GONE

        loadDirectoryJob = lifecycleScope.launch {
            val list = when (mode) {
                "CLOUD" -> {
                    val acc = cloudAccount ?: return@launch
                    val expectedFolderId = if (cloudNavStack.isEmpty()) "root" else cloudNavStack.peek().id
                    val files = CloudManager.listCloudFolder(this@FileExplorerActivity, acc, expectedFolderId, forceRefresh)
                    val activeFolderIdNow = if (cloudNavStack.isEmpty()) "root" else cloudNavStack.peek().id
                    if (activeFolderIdNow != expectedFolderId) return@launch

                    val recovery = CloudManager.pendingRecoveryIntent
                    if (recovery != null) {
                        binding.pbLoadingFiles.visibility = View.GONE
                        driveConsentLauncher.launch(recovery)
                        return@launch
                    }

                    // Eğer Google Drive hesabında henüz OAuth token yoksa boş ekranda beklemek yerine
                    // doğrudan CloudAccountsActivity (AUTO_AUTH_ACCOUNT_ID) üzerinden MergeSession köprüsüyle bağla!
                    val fallbackAcc = CloudManager.needsWebOAuthFallbackForAccount
                    if (fallbackAcc != null && acc.provider == CloudProviderType.GOOGLE_DRIVE) {
                        binding.pbLoadingFiles.visibility = View.GONE
                        startActivity(Intent(this@FileExplorerActivity, CloudAccountsActivity::class.java).apply {
                            putExtra("AUTO_AUTH_ACCOUNT_ID", acc.id)
                        })
                        finish()
                        return@launch
                    }

                    val refreshedAcc = CloudManager.getAccounts(this@FileExplorerActivity).firstOrNull { it.id == acc.id }
                    if (refreshedAcc != null) {
                        cloudAccount = refreshedAcc
                    }
                    if (expectedFolderId == "root" && files.isNotEmpty() && acc.provider == CloudProviderType.GOOGLE_DRIVE) {
                        launch {
                            val updatedAcc = CloudManager.fetchGoogleDriveQuota(this@FileExplorerActivity, cloudAccount ?: acc)
                            cloudAccount = updatedAcc
                            updateCloudQuotaLabel(updatedAcc)
                        }
                    }
                    files
                }
                "REMOTE" -> {
                    val rem = remoteAccount ?: return@launch
                    RemoteManager.listRemoteDirectory(rem, currentPath)
                }
                "CATEGORY" -> {
                    if (categoryName in setOf("VIDEOS", "IMAGES", "AUDIO", "DOCUMENTS")) {
                        if (selectedBucketPath.isEmpty()) {
                            LocalFileRepository.getCategoryFolderBuckets(this@FileExplorerActivity, categoryName, forceRefresh)
                        } else {
                            LocalFileRepository.getCategoryFilesInBucket(
                                this@FileExplorerActivity,
                                categoryName,
                                selectedBucketPath,
                                forceRefresh
                            )
                        }
                    } else {
                        LocalFileRepository.getCategoryFiles(this@FileExplorerActivity, categoryName, forceRefresh)
                    }
                }
                else -> {
                    updateLocalStorageUsedPill()
                    LocalFileRepository.listDirectory(File(currentPath), forceRefresh = forceRefresh)
                }
            }

            fullCurrentItems = list
            adapter.submitList(list)
            binding.pbLoadingFiles.visibility = View.GONE
            binding.swipeRefreshFiles.isRefreshing = false
            if (list.isEmpty()) {
                binding.llEmptyFolder.visibility = View.VISIBLE
                val err = CloudManager.lastCloudErrorMessage
                binding.tvEmptyFolder.text = if (mode == "CLOUD" && !err.isNullOrBlank()) {
                    err
                } else {
                    getString(R.string.empty_folder)
                }
            } else {
                binding.llEmptyFolder.visibility = View.GONE
            }
            updateSelectionUi()
        }
    }

    private fun renderBreadcrumbs() {
        binding.llBreadcrumbContainer.removeAllViews()
        val crumbs: List<Pair<String, () -> Unit>> = when (mode) {
            "CLOUD" -> {
                if (cloudNavStack.size <= 1) {
                    emptyList()
                } else {
                    cloudNavStack.drop(1).mapIndexed { idx, crumb ->
                        val targetDepth = idx + 2
                        crumb.name to {
                            while (cloudNavStack.size > targetDepth) {
                                cloudNavStack.pop()
                            }
                            loadCurrentDirectory(forceRefresh = false)
                        }
                    }
                }
            }
            "REMOTE" -> {
                val parts = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                val list = mutableListOf<Pair<String, () -> Unit>>()
                var accPath = ""
                for (p in parts) {
                    accPath += "/$p"
                    val target = accPath
                    list.add(p to {
                        if (currentPath != target) {
                            currentPath = target
                            loadCurrentDirectory()
                        }
                    })
                }
                list
            }
            "CATEGORY" -> {
                if (selectedBucketName.isNotEmpty()) {
                    listOf(selectedBucketName to {})
                } else {
                    emptyList()
                }
            }
            else -> {
                val rootPath = localVolumeRootPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
                val rel = currentPath.removePrefix(rootPath).trim('/')
                val list = mutableListOf<Pair<String, () -> Unit>>()
                if (rel.isNotEmpty()) {
                    var build = rootPath
                    for (segment in rel.split("/")) {
                        build = "$build/$segment"
                        val target = build
                        list.add(segment to {
                            if (currentPath != target) {
                                currentPath = target
                                loadCurrentDirectory(forceRefresh = false)
                            }
                        })
                    }
                }
                list
            }
        }

        val visibleCrumbs = crumbs.filter { it.first.isNotEmpty() }
        visibleCrumbs.forEachIndexed { idx, (label, action) ->
            val tv = TextView(this).apply {
                text = "›  $label"
                textSize = 14f
                setTextColor(
                    if (idx == visibleCrumbs.lastIndex) ThemeManager.getPrimaryTextColor(this@FileExplorerActivity)
                    else ThemeManager.getSecondaryTextColor(this@FileExplorerActivity)
                )
                setPadding(8, 0, 8, 0)
                setOnClickListener { action() }
            }
            binding.llBreadcrumbContainer.addView(tv)
        }

        binding.hsvBreadcrumbs.post {
            binding.hsvBreadcrumbs.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun onFileItemClicked(item: FileItem) {
        if (item.isDirectory) {
            when (mode) {
                "CLOUD" -> {
                    if (cloudNavStack.isEmpty() || cloudNavStack.peek().id != item.id) {
                        cloudNavStack.push(CloudCrumb(item.id, item.name))
                    }
                    loadCurrentDirectory(forceRefresh = false)
                }
                "REMOTE" -> {
                    currentPath = item.path
                    loadCurrentDirectory()
                }
                "CATEGORY" -> {
                    selectedBucketPath = item.path
                    selectedBucketName = item.name
                    LocalFileRepository.recordHistory(this, item, intent.getStringExtra("TITLE") ?: "Medya")
                    loadCurrentDirectory(forceRefresh = false)
                }
                else -> {
                    currentPath = item.path
                    LocalFileRepository.recordHistory(this, item, localVolumeTitle)
                    loadCurrentDirectory(forceRefresh = false)
                }
            }
            return
        }

        if (item.sourceType == FileSourceType.LOCAL) {
            val section = when (mode) {
                "CATEGORY" -> intent.getStringExtra("TITLE") ?: "Videolar"
                else -> localVolumeTitle
            }
            LocalFileRepository.recordHistory(this, item, section)
        }

        val acc = cloudAccount
        if (item.sourceType == FileSourceType.CLOUD && acc != null && acc.provider == CloudProviderType.MEGA) {
            openMegaCloudFile(acc, item)
            return
        }

        lifecycleScope.launch {
            val activeToken = if (item.sourceType == FileSourceType.CLOUD && acc != null && acc.provider == CloudProviderType.GOOGLE_DRIVE) {
                CloudManager.resolveGoogleDriveToken(this@FileExplorerActivity, acc, forceRefresh = false)
            } else {
                acc?.accessToken ?: ""
            }

            when {
                item.isVideo -> {
                    if (AppSettings.useInternalVideoPlayer) {
                        val siblingVideos = fullCurrentItems.filter { !it.isDirectory && it.isVideo }
                        val siblingPaths = ArrayList(siblingVideos.map { if (it.sourceType == FileSourceType.CLOUD) it.downloadUrl else it.path })
                        val siblingTitles = ArrayList(siblingVideos.map { it.name })
                        val curIdx = siblingVideos.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        val sessionKey = LocalFileRepository.putViewerSession(siblingPaths, siblingTitles, curIdx, activeToken)

                        startActivity(Intent(this@FileExplorerActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", if (item.sourceType == FileSourceType.CLOUD) item.downloadUrl else item.path)
                            putExtra("VIDEO_TITLE", item.name)
                            putExtra("AUTH_TOKEN", activeToken)
                            putExtra("VIEWER_SESSION_KEY", sessionKey)
                            if (siblingPaths.size <= 300) {
                                putStringArrayListExtra("PLAYLIST_URLS", siblingPaths)
                                putStringArrayListExtra("PLAYLIST_TITLES", siblingTitles)
                            }
                            putExtra("PLAYLIST_INDEX", curIdx)
                        })
                    } else {
                        LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, item, activeToken)
                    }
                }
                item.isAudio -> {
                    if (AppSettings.useInternalMusicPlayer) {
                        startActivity(Intent(this@FileExplorerActivity, MusicPlayerActivity::class.java).apply {
                            putExtra("AUDIO_URL", if (item.sourceType == FileSourceType.CLOUD) item.downloadUrl else item.path)
                            putExtra("AUDIO_TITLE", item.name)
                            putExtra("AUTH_TOKEN", activeToken)
                        })
                    } else {
                        LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, item, activeToken)
                    }
                }
                item.isImage -> {
                    if (AppSettings.useInternalImageViewer) {
                        val siblingImages = fullCurrentItems.filter { !it.isDirectory && it.isImage }
                        val siblingPaths = ArrayList(siblingImages.map { if (it.sourceType == FileSourceType.CLOUD) it.downloadUrl else it.path })
                        val siblingTitles = ArrayList(siblingImages.map { it.name })
                        val curIdx = siblingImages.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        val sessionKey = LocalFileRepository.putViewerSession(siblingPaths, siblingTitles, curIdx, activeToken)

                        startActivity(Intent(this@FileExplorerActivity, ImageViewerActivity::class.java).apply {
                            putExtra("IMAGE_PATH", if (item.sourceType == FileSourceType.CLOUD) item.downloadUrl else item.path)
                            putExtra("IMAGE_TITLE", item.name)
                            putExtra("AUTH_TOKEN", activeToken)
                            putExtra("VIEWER_SESSION_KEY", sessionKey)
                            if (siblingPaths.size <= 300) {
                                putStringArrayListExtra("IMAGE_LIST_PATHS", siblingPaths)
                                putStringArrayListExtra("IMAGE_LIST_TITLES", siblingTitles)
                            }
                            putExtra("IMAGE_INDEX", curIdx)
                        })
                    } else {
                        LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, item, activeToken)
                    }
                }
                (item.extension.equals("zip", ignoreCase = true) && item.sourceType == FileSourceType.LOCAL) -> {
                    showOriginalZipExtractDialog(item)
                }
                (item.extension in setOf("txt", "md", "json", "xml", "log", "ini", "csv", "html") && AppSettings.useInternalTextEditor && item.sourceType == FileSourceType.LOCAL) -> {
                    startActivity(Intent(this@FileExplorerActivity, TextEditorActivity::class.java).apply {
                        putExtra("FILE_PATH", item.path)
                        putExtra("FILE_TITLE", item.name)
                    })
                }
                else -> {
                    LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, item, activeToken)
                }
            }
        }
    }

    private fun openMegaCloudFile(account: CloudAccount, item: FileItem) {
        val density = resources.displayMetrics.density
        val progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@FileExplorerActivity))
            addView(TextView(this@FileExplorerActivity).apply {
                text = "MEGA dosyası hazırlanıyor: ${item.name}"
                textSize = 14.5f
                setPadding((16 * density).toInt(), 0, 0, 0)
            })
        }
        val loadingDialog = AlertDialog.Builder(this)
            .setView(progressLayout)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        lifecycleScope.launch {
            val localFile = CloudManager.downloadMegaFileToCache(this@FileExplorerActivity, account, item)
            loadingDialog.dismiss()
            if (localFile == null || !localFile.exists()) {
                Toast.makeText(
                    this@FileExplorerActivity,
                    CloudManager.lastCloudErrorMessage ?: "MEGA dosyası indirilemedi.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val localItem = item.copy(
                path = localFile.absolutePath,
                downloadUrl = localFile.absolutePath,
                sourceType = FileSourceType.LOCAL
            )
            when {
                localItem.isVideo && AppSettings.useInternalVideoPlayer -> {
                    startActivity(Intent(this@FileExplorerActivity, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URL", localFile.absolutePath)
                        putExtra("VIDEO_TITLE", localItem.name)
                    })
                }
                localItem.isAudio && AppSettings.useInternalMusicPlayer -> {
                    startActivity(Intent(this@FileExplorerActivity, MusicPlayerActivity::class.java).apply {
                        putExtra("AUDIO_URL", localFile.absolutePath)
                        putExtra("AUDIO_TITLE", localItem.name)
                    })
                }
                localItem.isImage && AppSettings.useInternalImageViewer -> {
                    startActivity(Intent(this@FileExplorerActivity, ImageViewerActivity::class.java).apply {
                        putExtra("IMAGE_PATH", localFile.absolutePath)
                        putExtra("IMAGE_TITLE", localItem.name)
                    })
                }
                else -> {
                    LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, localItem)
                }
            }
        }
    }

    private fun showCreateNewDialog(isFolder: Boolean) {
        if (mode != "LOCAL") {
            Toast.makeText(this, "Bu işlem şu an yerel depolama klasörlerinde kullanılabilir.", Toast.LENGTH_SHORT).show()
            return
        }
        val et = EditText(this).apply {
            hint = if (isFolder) "Yeni klasör adı" else "dosya.txt"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(if (isFolder) getString(R.string.menu_new_folder) else getString(R.string.menu_new_file))
            .setView(et)
            .setPositiveButton("TAMAM") { _, _ ->
                val name = et.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val ok = if (isFolder) {
                        LocalFileRepository.createNewFolder(File(currentPath), name)
                    } else {
                        LocalFileRepository.createNewFile(File(currentPath), name)
                    }
                    if (ok) loadCurrentDirectory(forceRefresh = true)
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun showRenameDialog(item: FileItem) {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val hasExtension = !item.isDirectory && item.name.contains('.') && !item.name.startsWith(".")
        val baseName = if (hasExtension) item.name.substringBeforeLast('.') else item.name
        val extWithDot = if (hasExtension) ".${item.name.substringAfterLast('.')}" else ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            val pH = (22 * density).toInt()
            val pV = (14 * density).toInt()
            setPadding(pH, pV, pH, (4 * density).toInt())
        }

        val etBaseName = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(baseName)
            setSingleLine(true)
            setTextColor(primaryColor)
            textSize = 16f
            selectAll()
        }

        val tvExtension = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
            }
            text = extWithDot
            textSize = 16f
            setTextColor(primaryColor)
            visibility = if (hasExtension) View.VISIBLE else View.GONE
        }

        container.addView(etBaseName)
        container.addView(tvExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_rename))
            .setView(container)
            .setPositiveButton("TAMAM") { _, _ ->
                val newBase = etBaseName.text?.toString()?.trim().orEmpty()
                if (newBase.isNotEmpty() && mode in setOf("LOCAL", "CATEGORY")) {
                    val finalFileName = if (hasExtension) "$newBase$extWithDot" else newBase
                    val ok = LocalFileRepository.renameFile(File(item.path), finalFileName, this)
                    adapter.clearSelection()
                    updateSelectionUi()
                    if (ok) {
                        loadCurrentDirectory(forceRefresh = true)
                    } else {
                        Toast.makeText(this, "Dosya adı değiştirilemedi.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun showDeleteConfirmDialog(items: List<FileItem>) {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val tealColor = Color.parseColor("#008578")

        val isInsideRecycleBin = currentPath.startsWith(LocalFileRepository.recycleBinDir.absolutePath)
        val canUseRecycleBin = AppSettings.useRecycleBin && !isInsideRecycleBin

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pH = (24 * density).toInt()
            val pV = (16 * density).toInt()
            setPadding(pH, pV, pH, (6 * density).toInt())
        }

        val tvPrompt = TextView(this).apply {
            text = if (items.size == 1) {
                "\"${items.first().name}\" öğesini silmek istediğinizden emin misiniz?"
            } else {
                "Seçilen ${items.size} öğeyi silmek istediğinizden emin misiniz?"
            }
            textSize = 15.5f
            setTextColor(primaryColor)
        }

        val totalSize = items.sumOf { it.size }
        val tvDetails = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            text = if (canUseRecycleBin) {
                "Toplam boyut: ${FormatUtils.formatSizeDetailed(totalSize)}\n(Varsayılan olarak Geri Dönüşüm Kutusuna taşınır)"
            } else {
                "Toplam boyut: ${FormatUtils.formatSizeDetailed(totalSize)}\n(Bu öğeler cihazdan kalıcı olarak silinecek)"
            }
            textSize = 13f
            setTextColor(secondaryColor)
        }

        val cbPermanentDelete = CheckBox(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
            text = "Kalıcı olarak sil"
            textSize = 14.5f
            setTextColor(primaryColor)
            isChecked = !canUseRecycleBin
            visibility = if (canUseRecycleBin) View.VISIBLE else View.GONE
            val tintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(tealColor, secondaryColor)
            )
            CompoundButtonCompat.setButtonTintList(this, tintList)
        }

        layout.addView(tvPrompt)
        layout.addView(tvDetails)
        layout.addView(cbPermanentDelete)

        AlertDialog.Builder(this)
            .setTitle("Sil")
            .setView(layout)
            .setPositiveButton("TAMAM") { _, _ ->
                val moveToTrash = canUseRecycleBin && !cbPermanentDelete.isChecked
                lifecycleScope.launch {
                    val count = LocalFileRepository.deleteFiles(
                        items = items,
                        moveToRecycleBin = moveToTrash,
                        context = this@FileExplorerActivity
                    )
                    adapter.clearSelection()
                    updateSelectionUi()
                    val msg = if (moveToTrash) {
                        "$count öğe Geri Dönüşüm Kutusuna taşındı."
                    } else {
                        "$count öğe kalıcı olarak silindi."
                    }
                    Toast.makeText(this@FileExplorerActivity, msg, Toast.LENGTH_SHORT).show()
                    loadCurrentDirectory(forceRefresh = true)
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun showCompressZipDialog(items: List<FileItem>) {
        val et = EditText(this).apply {
            setText(items.first().name.substringBeforeLast('.'))
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_compress))
            .setView(et)
            .setPositiveButton("TAMAM") { _, _ ->
                val zipName = et.text?.toString()?.trim().orEmpty().ifEmpty { "arsiv.zip" }
                val destZip = File(currentPath, if (zipName.endsWith(".zip")) zipName else "$zipName.zip")
                lifecycleScope.launch {
                    binding.pbLoadingFiles.visibility = View.VISIBLE
                    val ok = LocalFileRepository.compressToZip(items.map { File(it.path) }, destZip)
                    binding.pbLoadingFiles.visibility = View.GONE
                    adapter.clearSelection()
                    updateSelectionUi()
                    if (ok) loadCurrentDirectory(forceRefresh = true)
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun showOriginalBottomMorePopup(anchor: View, selected: List<FileItem>) {
        val first = selected.first()
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#303030"))
            val vPad = (6 * density).toInt()
            setPadding(0, vPad, 0, vPad)
            elevation = 10f * density
        }

        var popupWindow: PopupWindow? = null

        fun addRow(iconRes: Int, label: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (210 * density).toInt(),
                    (46 * density).toInt()
                )
                val hPad = (16 * density).toInt()
                setPadding(hPad, 0, hPad, 0)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popupWindow?.dismiss()
                    onClick()
                }
            }
            val iv = ImageView(this).apply {
                val sz = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (14 * density).toInt()
                }
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                isSingleLine = true
            }
            row.addView(iv)
            row.addView(tv)
            container.addView(row)
        }

        addRow(R.drawable.ic_menu_share, "Paylaş") {
            LocalFileRepository.shareFile(this, first)
            adapter.clearSelection()
            updateSelectionUi()
        }

        addRow(R.drawable.ic_drawer_bookmarks, "Yer İmi Ekle") {
            LocalFileRepository.addBookmark(this, first)
            Toast.makeText(this, "Yer imlerine eklendi: ${first.name}", Toast.LENGTH_SHORT).show()
            adapter.clearSelection()
            updateSelectionUi()
        }

        if (mode in setOf("LOCAL", "CATEGORY")) {
            if (selected.size == 1 && first.extension.equals("zip", ignoreCase = true)) {
                addRow(R.drawable.ic_archive_zip, "Çıkart") {
                    adapter.clearSelection()
                    updateSelectionUi()
                    showOriginalZipExtractDialog(first)
                }
            } else {
                addRow(R.drawable.ic_archive_zip, "Sıkıştır") {
                    showCompressZipDialog(selected)
                }
            }
        }

        addRow(R.drawable.ic_menu_share, "Şununla aç") {
            LocalFileRepository.openWithExternalChooser(
                this,
                first,
                cloudAccount?.accessToken ?: ""
            )
            adapter.clearSelection()
            updateSelectionUi()
        }

        addRow(R.drawable.ic_menu_info, "Özellikler") {
            adapter.clearSelection()
            updateSelectionUi()
            showPropertiesDialog(first)
        }

        addRow(R.drawable.ic_menu_settings, "Ayarlar") {
            adapter.clearSelection()
            updateSelectionUi()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = container.measuredHeight
        val popupWidth = (210 * density).toInt()

        popupWindow = PopupWindow(
            container,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 12f * density
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val xPos = (resources.displayMetrics.widthPixels - popupWidth - (8 * density).toInt()).coerceAtLeast(0)
        val yPos = (loc[1] - popupHeight - (4 * density).toInt()).coerceAtLeast((60 * density).toInt())
        popupWindow.showAtLocation(binding.explorerRoot, Gravity.NO_GRAVITY, xPos, yPos)
    }

    private fun showOriginalZipExtractDialog(item: FileItem) {
        val baseFolderName = item.name.substringBeforeLast('.').ifBlank { "${item.name}_cikartilan" }
        val parentDir = File(item.path).parentFile ?: File(currentPath)
        val options = arrayOf(
            "Buraya Çıkart",
            "Klasöre çıkart $baseFolderName/",
            "Dosyaları çıkart..."
        )

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> extractZipToDirectory(item, parentDir)
                    1 -> extractZipToDirectory(item, File(parentDir, baseFolderName))
                    2 -> {
                        pendingExtractZipItem = item
                        LocalFileRepository.clipboardFiles = emptyList()
                        if (mode == "CATEGORY") {
                            mode = "LOCAL"
                            currentPath = parentDir.absolutePath
                            loadCurrentDirectory(forceRefresh = false)
                        }
                        binding.tvClipboardStatus.text = "Çıkartılacak: ${item.name}"
                        binding.btnConfirmPaste.text = "BURAYA ÇIKART"
                        binding.pasteActionBar.visibility = View.VISIBLE
                        Toast.makeText(
                            this,
                            "Çıkartmak istediğiniz klasöre gidip alttaki 'BURAYA ÇIKART' butonuna dokunun.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun extractZipToDirectory(item: FileItem, destDir: File) {
        lifecycleScope.launch {
            binding.pbLoadingFiles.visibility = View.VISIBLE
            val ok = LocalFileRepository.extractZip(File(item.path), destDir)
            binding.pbLoadingFiles.visibility = View.GONE
            adapter.clearSelection()
            updateSelectionUi()
            if (ok) {
                Toast.makeText(
                    this@FileExplorerActivity,
                    "✓ Çıkartıldı: ${destDir.name}",
                    Toast.LENGTH_SHORT
                ).show()
                loadCurrentDirectory(forceRefresh = true)
            } else {
                Toast.makeText(
                    this@FileExplorerActivity,
                    "Arşiv çıkartılırken hata oluştu.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showPropertiesDialog(item: FileItem) {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val cardColor = ThemeManager.getCardColor(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pH = (22 * density).toInt()
            val pV = (18 * density).toInt()
            setPadding(pH, pV, pH, (8 * density).toInt())
            setBackgroundColor(cardColor)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }

        val ivIcon = ImageView(this).apply {
            val sz = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
            val iconRes = when {
                item.isDirectory -> R.drawable.ic_folder_orange
                item.isArchive -> R.drawable.ic_archive_zip
                item.isVideo -> R.drawable.ic_cat_videos
                item.isImage -> R.drawable.ic_cat_images
                item.isAudio -> R.drawable.ic_cat_audio
                item.isApk -> R.drawable.ic_cat_apps
                else -> R.drawable.ic_cat_docs
            }
            setImageResource(iconRes)
        }

        val tvHeaderTitle = TextView(this).apply {
            text = item.name
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
        }

        titleRow.addView(ivIcon)
        titleRow.addView(tvHeaderTitle)
        root.addView(titleRow)

        fun addPropertyRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            val tvLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (130 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = label
                textSize = 13.5f
                setTextColor(secondaryColor)
            }
            val tvVal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = value
                textSize = 13.5f
                setTextColor(primaryColor)
            }
            row.addView(tvLabel)
            row.addView(tvVal)
            root.addView(row)
        }

        val exactBytes = NumberFormat.getIntegerInstance(Locale("tr", "TR")).format(item.size)
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        val f = File(item.path)
        val perms = if (item.sourceType == FileSourceType.LOCAL && f.exists()) {
            buildString {
                if (f.canRead()) append("Okuma ")
                if (f.canWrite()) append("Yazma")
            }.trim().ifEmpty { "Okuma" }
        } else "Okuma Yazma"

        addPropertyRow("Türü", if (item.isDirectory) "Klasör" else "Dosya")
        addPropertyRow("Boyut", "${FormatUtils.formatSize(item.size)}\n($exactBytes bayt)")
        addPropertyRow("Değiştirilmiş", sdf.format(Date(item.lastModified)))
        addPropertyRow("Yol", item.path)
        addPropertyRow("İzinler", perms)

        if (item.accountEmailOrLabel.isNotBlank()) {
            addPropertyRow("Bulut Hesabı", "${item.providerName} (${item.accountEmailOrLabel})")
        }

        if (!item.isDirectory && item.sourceType == FileSourceType.LOCAL && f.exists()) {
            val checksumRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
            }
            val tvChecksumLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (130 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = "Sağlama toplamları"
                textSize = 13.5f
                setTextColor(secondaryColor)
            }
            val checksumValueContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnCalculate = android.widget.Button(this).apply {
                text = "HESAPLAMAK"
                textSize = 12.5f
                setTextColor(primaryColor)
                setOnClickListener {
                    isEnabled = false
                    text = "Hesaplanıyor..."
                    lifecycleScope.launch {
                        val md5 = LocalFileRepository.computeChecksum(f, "MD5")
                        val sha1 = LocalFileRepository.computeChecksum(f, "SHA-1")
                        checksumValueContainer.removeAllViews()
                        val tvHashes = TextView(this@FileExplorerActivity).apply {
                            text = "MD5: $md5\nSHA1: $sha1"
                            textSize = 12.5f
                            setTextColor(primaryColor)
                            setTextIsSelectable(true)
                        }
                        checksumValueContainer.addView(tvHashes)
                    }
                }
            }
            checksumValueContainer.addView(btnCalculate)
            checksumRow.addView(tvChecksumLabel)
            checksumRow.addView(checksumValueContainer)
            root.addView(checksumRow)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("TAMAM", null)
            .show()
    }

    private fun handleBackNavigation() {
        if (adapter.selectionMode) {
            adapter.clearSelection()
            updateSelectionUi()
            return
        }
        when (mode) {
            "CLOUD" -> {
                if (cloudNavStack.size > 1) {
                    cloudNavStack.pop()
                    loadCurrentDirectory(forceRefresh = false)
                } else {
                    finish()
                }
            }
            "REMOTE" -> {
                if (currentPath != "/" && currentPath.isNotEmpty()) {
                    currentPath = currentPath.substringBeforeLast('/', "/").ifEmpty { "/" }
                    loadCurrentDirectory()
                } else {
                    finish()
                }
            }
            "CATEGORY" -> {
                if (selectedBucketPath.isNotEmpty()) {
                    selectedBucketPath = ""
                    selectedBucketName = ""
                    loadCurrentDirectory(forceRefresh = false)
                } else {
                    finish()
                }
            }
            "LOCAL" -> {
                val rootPath = localVolumeRootPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
                if (currentPath != rootPath && currentPath.startsWith(rootPath)) {
                    val parent = File(currentPath).parentFile
                    if (parent != null && parent.absolutePath.startsWith(rootPath)) {
                        currentPath = parent.absolutePath
                        loadCurrentDirectory(forceRefresh = false)
                        return
                    }
                }
                finish()
            }
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun applyCurrentTheme() {
        ThemeManager.applyWindowColors(this, binding.explorerRoot, binding.toolbarExplorer)
        binding.llBreadcrumbBar.setBackgroundColor(ThemeManager.getSurfaceColor(this))
        binding.dividerBreadcrumb.setBackgroundColor(ThemeManager.getDividerColor(this))
    }
}
