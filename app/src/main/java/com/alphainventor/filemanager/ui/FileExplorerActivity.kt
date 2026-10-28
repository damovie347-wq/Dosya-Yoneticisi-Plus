package com.alphainventor.filemanager.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.Stack

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Dosya, Medya Kategorileri ve Bulut Gezgini:
 * - Videolar, Görüntüler, Ses ve Belgeler kategorilerinde önce Klasör Kutucukları (Folder Buckets: InstaEclipse (64), Twitter (22), Movies (6) vb.) gösterilir, klasöre tıklanınca o klasördeki medya dosyaları açılır.
 * - Ana bellek ve SD kart içinde PathBar sağ tarafında "KULLANILAN 66%" / "KULLANILAN 79%" rozeti yer alır, tıklanınca ilgili diskin Bellek Analizi ekranı açılır.
 * - Yer İmi Ekle ve Geçmiş kaydı sol çekmeceyle tam senkronize çalışır.
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
    private var remoteAccount: RemoteAccount? = null

    private data class CloudCrumb(val id: String, val name: String)
    private val cloudNavStack = Stack<CloudCrumb>()
    private var fullCurrentItems: List<FileItem> = emptyList()

    private val driveConsentLauncher = registerForActivityResult(
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
                        CloudProviderType.DROPBOX -> R.drawable.cloud_icon_dropbox
                        CloudProviderType.ONEDRIVE -> R.drawable.cloud_icon_onedrive
                        CloudProviderType.BOX -> R.drawable.cloud_icon_box
                        CloudProviderType.NEXTCLOUD -> R.drawable.ic_cloud_nextcloud
                        CloudProviderType.YANDEX -> R.drawable.cloud_icon_yandex
                    }
                )
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
                binding.tvStorageBadge.visibility = View.GONE
                binding.btnParentFolderUp.visibility = View.VISIBLE
                // Videolar, Görüntüler, Ses ve Belgeler varsayılan olarak Klasör Kutucuğu (Grid) modunda açılır
                if (categoryName in setOf("VIDEOS", "IMAGES", "AUDIO", "DOCUMENTS")) {
                    adapter.isGridMode = true
                    updateRecyclerLayoutManager()
                }
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

                binding.tvExplorerTitle.text = title
                binding.tvExplorerSubtitle.visibility = View.GONE
                updateLocalStorageUsedPill()
                binding.btnParentFolderUp.visibility = View.VISIBLE
            }
        }

        loadCurrentDirectory(forceRefresh = false)
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
        binding.tvStorageBadge.text = "◔ KULLANILAN ${overview.usedPercent}%"
        binding.tvStorageBadge.setOnClickListener {
            startActivity(Intent(this, StorageAnalysisActivity::class.java).apply {
                putExtra("TARGET_PATH", rootPath)
                putExtra("TARGET_TITLE", localVolumeTitle)
            })
        }
    }

    private fun updateCloudQuotaLabel(acc: CloudAccount) {
        binding.tvStorageBadge.background = null
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
        adapter.isGridMode = initialGrid
        updateRecyclerLayoutManager()
        binding.rvFileList.adapter = adapter
        binding.rvFileList.setHasFixedSize(true)

        binding.swipeRefreshFiles.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_teal))
        binding.swipeRefreshFiles.setOnRefreshListener {
            loadCurrentDirectory(forceRefresh = true)
        }
    }

    private fun updateRecyclerLayoutManager() {
        if (adapter.isGridMode) {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density
            val spans = when {
                widthDp >= 720f -> 7
                widthDp >= 540f -> 5
                else -> 3
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

        // Görünüm (Liste / Izgara) ve Sıralama Menüsü
        binding.btnSortAndView.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 10, 0, if (adapter.isGridMode) "Görünüm: Liste Görünümüne Geç" else "Görünüm: Izgara (Kare) Görünümüne Geç")
            popup.menu.add(0, 1, 1, "Ada Göre Sırala (A → Z)")
            popup.menu.add(0, 2, 2, "Ada Göre Sırala (Z → A)")
            popup.menu.add(0, 3, 3, "Boyuta Göre Sırala (Büyükten Küçüğe)")
            popup.menu.add(0, 4, 4, "Boyuta Göre Sırala (Küçükten Büyüğe)")
            popup.menu.add(0, 5, 5, "Tarihe Göre Sırala (Yeniden Eskiye)")
            popup.menu.add(0, 6, 6, "Tarihe Göre Sırala (Eskiden Yeniye)")
            popup.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    10 -> {
                        adapter.isGridMode = !adapter.isGridMode
                        updateRecyclerLayoutManager()
                    }
                    1 -> { AppSettings.sortBy = 0; AppSettings.sortAscending = true; loadCurrentDirectory() }
                    2 -> { AppSettings.sortBy = 0; AppSettings.sortAscending = false; loadCurrentDirectory() }
                    3 -> { AppSettings.sortBy = 1; AppSettings.sortAscending = false; loadCurrentDirectory() }
                    4 -> { AppSettings.sortBy = 1; AppSettings.sortAscending = true; loadCurrentDirectory() }
                    5 -> { AppSettings.sortBy = 2; AppSettings.sortAscending = false; loadCurrentDirectory() }
                    6 -> { AppSettings.sortBy = 2; AppSettings.sortAscending = true; loadCurrentDirectory() }
                }
                true
            }
            popup.show()
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
                        loadCurrentDirectory()
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

        // Alt Çoklu Seçim Aksiyon Çubuğu
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
            val first = selected.first()
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.action_share))
            popup.menu.add(0, 2, 1, getString(R.string.action_bookmark))
            popup.menu.add(0, 3, 2, getString(R.string.action_open_with))
            popup.menu.add(0, 4, 3, getString(R.string.action_properties))
            popup.menu.add(0, 5, 4, getString(R.string.settings_title))
            if (mode == "LOCAL") {
                popup.menu.add(0, 6, 5, getString(R.string.action_compress))
                if (first.extension == "zip") {
                    popup.menu.add(0, 7, 6, getString(R.string.action_extract))
                }
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> LocalFileRepository.shareFile(this, first)
                    2 -> {
                        LocalFileRepository.addBookmark(this, first)
                        Toast.makeText(this, "Yer imlerine eklendi: ${first.name}", Toast.LENGTH_SHORT).show()
                        adapter.clearSelection()
                        updateSelectionUi()
                    }
                    3 -> {
                        LocalFileRepository.openWithExternalChooser(
                            this,
                            first,
                            cloudAccount?.accessToken ?: ""
                        )
                        adapter.clearSelection()
                        updateSelectionUi()
                    }
                    4 -> showPropertiesDialog(first)
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                    6 -> showCompressZipDialog(selected)
                    7 -> extractZipHere(first)
                }
                true
            }
            popup.show()
        }

        binding.btnCancelPaste.setOnClickListener {
            LocalFileRepository.clipboardFiles = emptyList()
            binding.pasteActionBar.visibility = View.GONE
        }

        binding.btnConfirmPaste.setOnClickListener {
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
                "CATEGORY" -> {
                    val baseTitle = intent.getStringExtra("TITLE") ?: categoryName
                    if (selectedBucketName.isNotBlank()) selectedBucketName else baseTitle
                }
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
        binding.pbLoadingFiles.visibility = View.VISIBLE
        binding.llEmptyFolder.visibility = View.GONE
        renderBreadcrumbs()

        lifecycleScope.launch {
            val list = when (mode) {
                "CLOUD" -> {
                    val acc = cloudAccount ?: return@launch
                    val currentFolderId = if (cloudNavStack.isEmpty()) "root" else cloudNavStack.peek().id
                    val files = CloudManager.listCloudFolder(this@FileExplorerActivity, acc, currentFolderId, forceRefresh)
                    val recovery = CloudManager.pendingRecoveryIntent
                    if (recovery != null) {
                        binding.pbLoadingFiles.visibility = View.GONE
                        driveConsentLauncher.launch(recovery)
                        return@launch
                    }
                    // Güncel token'ı cloudAccount'ta sakla
                    val refreshedAcc = CloudManager.getAccounts(this@FileExplorerActivity).firstOrNull { it.id == acc.id }
                    if (refreshedAcc != null) {
                        cloudAccount = refreshedAcc
                    }
                    launch {
                        val updatedAcc = CloudManager.fetchGoogleDriveQuota(this@FileExplorerActivity, cloudAccount ?: acc)
                        cloudAccount = updatedAcc
                        updateCloudQuotaLabel(updatedAcc)
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
                            // 1. AŞAMA: Klasör Kutucukları (Folder Buckets: InstaEclipse (64), Twitter (22), Movies (6) vb.)
                            LocalFileRepository.getCategoryFolderBuckets(this@FileExplorerActivity, categoryName)
                        } else {
                            // 2. AŞAMA: Seçilen klasörün içindeki medya dosyaları
                            LocalFileRepository.getCategoryFilesInBucket(
                                this@FileExplorerActivity,
                                categoryName,
                                selectedBucketPath
                            )
                        }
                    } else {
                        LocalFileRepository.getCategoryFiles(this@FileExplorerActivity, categoryName)
                    }
                }
                else -> {
                    updateLocalStorageUsedPill()
                    LocalFileRepository.listDirectory(File(currentPath))
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
                cloudNavStack.mapIndexed { index, crumb ->
                    crumb.name to {
                        if (index < cloudNavStack.size - 1) {
                            while (cloudNavStack.size > index + 1) {
                                cloudNavStack.pop()
                            }
                            loadCurrentDirectory(forceRefresh = false)
                        }
                    }
                }
            }
            "REMOTE" -> {
                val parts = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                val list = mutableListOf<Pair<String, () -> Unit>>(
                    (remoteAccount?.displayName ?: "Uzak") to {
                        if (currentPath != "/") {
                            currentPath = "/"
                            loadCurrentDirectory()
                        }
                    }
                )
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
                val rootTitle = intent.getStringExtra("TITLE") ?: categoryName
                val list = mutableListOf<Pair<String, () -> Unit>>(
                    rootTitle to {
                        if (selectedBucketPath.isNotEmpty()) {
                            selectedBucketPath = ""
                            selectedBucketName = ""
                            loadCurrentDirectory()
                        }
                    }
                )
                if (selectedBucketName.isNotEmpty()) {
                    list.add(selectedBucketName to {})
                }
                list
            }
            else -> {
                val rootPath = localVolumeRootPath.ifBlank { LocalFileRepository.rootStorageDir.absolutePath }
                val rel = currentPath.removePrefix(rootPath).trim('/')
                val list = mutableListOf<Pair<String, () -> Unit>>(
                    localVolumeTitle to {
                        if (currentPath != rootPath) {
                            currentPath = rootPath
                            loadCurrentDirectory()
                        }
                    }
                )
                if (rel.isNotEmpty()) {
                    var build = rootPath
                    for (segment in rel.split("/")) {
                        build = "$build/$segment"
                        val target = build
                        list.add(segment to {
                            if (currentPath != target) {
                                currentPath = target
                                loadCurrentDirectory()
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
                text = if (idx == 0) label else " ›  $label"
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
                    // Klasör Kutucuğuna (örn: InstaEclipse (64) veya Movies (6)) tıklandığında o klasörün medyalarını aç!
                    selectedBucketPath = item.path
                    selectedBucketName = item.name
                    LocalFileRepository.recordHistory(this, item, intent.getStringExtra("TITLE") ?: "Medya")
                    loadCurrentDirectory(forceRefresh = false)
                }
                else -> {
                    currentPath = item.path
                    LocalFileRepository.recordHistory(this, item, localVolumeTitle)
                    loadCurrentDirectory()
                }
            }
            return
        }

        // Geçmişe (Sol Çekmece 3. Sekme 🕒) kaydet
        if (item.sourceType == FileSourceType.LOCAL) {
            val section = when (mode) {
                "CATEGORY" -> intent.getStringExtra("TITLE") ?: "Videolar"
                else -> localVolumeTitle
            }
            LocalFileRepository.recordHistory(this, item, section)
        }

        lifecycleScope.launch {
            val activeToken = if (item.sourceType == FileSourceType.CLOUD && cloudAccount != null) {
                CloudManager.resolveGoogleDriveToken(this@FileExplorerActivity, cloudAccount!!, forceRefresh = false)
            } else {
                cloudAccount?.accessToken ?: ""
            }

            when {
                item.isVideo -> {
                    if (AppSettings.useInternalVideoPlayer) {
                        startActivity(Intent(this@FileExplorerActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", if (item.sourceType == FileSourceType.CLOUD) item.downloadUrl else item.path)
                            putExtra("VIDEO_TITLE", item.name)
                            putExtra("AUTH_TOKEN", activeToken)
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
                    if (AppSettings.useInternalImageViewer && item.sourceType == FileSourceType.LOCAL) {
                        startActivity(Intent(this@FileExplorerActivity, ImageViewerActivity::class.java).apply {
                            putExtra("IMAGE_PATH", item.path)
                            putExtra("IMAGE_TITLE", item.name)
                        })
                    } else {
                        LocalFileRepository.openWithExternalChooser(this@FileExplorerActivity, item, activeToken)
                    }
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
            .setPositiveButton("Oluştur") { _, _ ->
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
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showRenameDialog(item: FileItem) {
        val et = EditText(this).apply {
            setText(item.name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_rename))
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                val newName = et.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && mode == "LOCAL") {
                    val ok = LocalFileRepository.renameFile(File(item.path), newName)
                    adapter.clearSelection()
                    updateSelectionUi()
                    if (ok) loadCurrentDirectory(forceRefresh = true)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showDeleteConfirmDialog(items: List<FileItem>) {
        AlertDialog.Builder(this)
            .setTitle("Sil (${items.size} öğe)")
            .setMessage("Seçilen ${items.size} öğeyi silmek istediğinize emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    val count = LocalFileRepository.deleteFiles(items, moveToRecycleBin = true)
                    adapter.clearSelection()
                    updateSelectionUi()
                    Toast.makeText(this@FileExplorerActivity, "$count öğe silindi.", Toast.LENGTH_SHORT).show()
                    loadCurrentDirectory(forceRefresh = true)
                }
            }
            .setNegativeButton("Kalsın", null)
            .show()
    }

    private fun showCompressZipDialog(items: List<FileItem>) {
        val et = EditText(this).apply {
            setText("${items.first().name.substringBeforeLast('.')}.zip")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_compress))
            .setView(et)
            .setPositiveButton("Sıkıştır") { _, _ ->
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
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun extractZipHere(item: FileItem) {
        val destDir = File(currentPath, item.name.substringBeforeLast('.'))
        lifecycleScope.launch {
            binding.pbLoadingFiles.visibility = View.VISIBLE
            val ok = LocalFileRepository.extractZip(File(item.path), destDir)
            binding.pbLoadingFiles.visibility = View.GONE
            adapter.clearSelection()
            updateSelectionUi()
            if (ok) loadCurrentDirectory(forceRefresh = true)
        }
    }

    private fun showPropertiesDialog(item: FileItem) {
        lifecycleScope.launch {
            val checksumInfo = if (!item.isDirectory && item.sourceType == FileSourceType.LOCAL) {
                val f = File(item.path)
                val md5 = LocalFileRepository.computeChecksum(f, "MD5")
                "\nMD5: $md5"
            } else ""

            val accountLine = if (item.accountEmailOrLabel.isNotBlank()) {
                "\nBulut Hesabı: ${item.providerName} (${item.accountEmailOrLabel})"
            } else ""

            AlertDialog.Builder(this@FileExplorerActivity)
                .setTitle(getString(R.string.action_properties))
                .setMessage(
                    "Ad: ${item.name}\n" +
                        "Konum: ${item.path}\n" +
                        "Tür: ${if (item.isDirectory) "Klasör" else item.mimeType.ifBlank { item.extension }}\n" +
                        "Boyut: ${FormatUtils.formatSizeDetailed(item.size)}\n" +
                        "Değiştirilme: ${FormatUtils.formatSectionDate(item.lastModified)}" +
                        accountLine + checksumInfo
                )
                .setPositiveButton("Tamam", null)
                .show()
        }
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
                    // Klasör içinden Kategori Klasör Kutucukları ekranına geri dön
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
                        loadCurrentDirectory()
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
