package com.alphainventor.filemanager.ui

import androidx.core.content.ContextCompat

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityFileExplorerBinding
import com.alphainventor.filemanager.model.CloudAccount
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

class FileExplorerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileExplorerBinding
    private lateinit var fileAdapter: FileListAdapter

    private var mode: String = "LOCAL" // LOCAL, CATEGORY, CLOUD, REMOTE
    private var currentPath: String = ""
    private var categoryName: String = ""
    private var cloudAccount: CloudAccount? = null
    private var remoteAccount: RemoteAccount? = null
    private val cloudFolderStack = Stack<Pair<String, String>>() // Pair<folderId, folderTitle>

    private var allLoadedItems: List<FileItem> = emptyList()
    private var currentSortBy: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.explorerRoot, binding.explorerToolbar)
        binding.pathDivider.setBackgroundColor(ThemeManager.getDividerColor(this))

        mode = intent.getStringExtra("MODE") ?: "LOCAL"
        currentPath = intent.getStringExtra("PATH") ?: LocalFileRepository.rootStorageDir.absolutePath
        categoryName = intent.getStringExtra("CATEGORY") ?: ""
        @Suppress("DEPRECATION")
        cloudAccount = intent.getSerializableExtra("CLOUD_ACCOUNT") as? CloudAccount
        @Suppress("DEPRECATION")
        remoteAccount = intent.getSerializableExtra("REMOTE_ACCOUNT") as? RemoteAccount

        setupRecyclerView()
        setupToolbarAndSearch()
        setupBottomActionBar()

        when (mode) {
            "CLOUD" -> {
                val acc = cloudAccount ?: return finish()
                binding.tvExplorerTitle.text = acc.provider.displayName
                binding.tvExplorerSubtitle.visibility = View.VISIBLE
                binding.tvExplorerSubtitle.text = acc.emailOrName
                binding.btnPathRightAction.text = if (acc.freeQuotaBytes > 0) {
                    "Boş ${FormatUtils.formatSize(acc.freeQuotaBytes)}"
                } else "Bulut"
                val initFolder = intent.getStringExtra("INITIAL_FOLDER_ID") ?: "root"
                val initTitle = intent.getStringExtra("TITLE") ?: acc.provider.displayName
                cloudFolderStack.push(initFolder to initTitle)
                loadCurrentContent()
            }
            "REMOTE" -> {
                val rem = remoteAccount ?: return finish()
                binding.tvExplorerTitle.text = rem.host
                binding.tvExplorerSubtitle.visibility = View.VISIBLE
                binding.tvExplorerSubtitle.text = "${rem.protocol.displayName} (${rem.displayName})"
                binding.btnPathRightAction.text = rem.protocol.badge
                currentPath = rem.initialPath
                loadCurrentContent()
            }
            "CATEGORY" -> {
                val title = intent.getStringExtra("TITLE") ?: categoryName
                binding.tvExplorerTitle.text = title
                if (categoryName == "NEW_FILES") {
                    binding.btnPathRightAction.text = "BOŞALT"
                    binding.btnPathRightAction.setOnClickListener {
                        allLoadedItems = emptyList()
                        fileAdapter.submitList(emptyList())
                    }
                } else {
                    val overview = LocalFileRepository.getStorageOverview()
                    binding.btnPathRightAction.text = "KULLANILAN ${overview.usedPercent}%"
                }
                loadCurrentContent()
            }
            else -> {
                val title = intent.getStringExtra("TITLE") ?: "Ana bellek"
                binding.tvExplorerTitle.text = title
                val overview = LocalFileRepository.getStorageOverview()
                binding.btnPathRightAction.text = "KULLANILAN ${overview.usedPercent}%"
                binding.btnPathRightAction.setOnClickListener {
                    startActivity(Intent(this, StorageAnalysisActivity::class.java))
                }
                loadCurrentContent()
            }
        }
    }

    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter(
            showAccountBadgeInSubtitle = false,
            onItemClick = { item -> onFileItemClicked(item) },
            onItemLongClick = { item ->
                fileAdapter.toggleSelection(item)
                updateSelectionBar()
            }
        )
        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@FileExplorerActivity)
            setHasFixedSize(true)
            adapter = fileAdapter
        }
        binding.swipeRefreshFiles.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_teal))
        binding.swipeRefreshFiles.setOnRefreshListener {
            loadCurrentContent()
        }
    }

    private fun setupToolbarAndSearch() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnSearchToggle.setOnClickListener {
            if (binding.etInlineSearch.visibility == View.VISIBLE) {
                binding.etInlineSearch.setText("")
                binding.etInlineSearch.visibility = View.GONE
                binding.titleContainer.visibility = View.VISIBLE
                fileAdapter.submitList(allLoadedItems)
            } else {
                binding.titleContainer.visibility = View.GONE
                binding.etInlineSearch.visibility = View.VISIBLE
                binding.etInlineSearch.requestFocus()
            }
        }

        binding.etInlineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) {
                    fileAdapter.submitList(allLoadedItems)
                } else {
                    fileAdapter.submitList(allLoadedItems.filter { it.name.contains(q, ignoreCase = true) })
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSortView.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 0, 0, "Ada göre sırala (A-Z)")
            popup.menu.add(0, 1, 1, "Boyuta göre sırala (Büyükten küçüğe)")
            popup.menu.add(0, 2, 2, "Tarihe göre sırala (Yeniden eskiye)")
            popup.menu.add(0, 3, 3, "Türe göre sırala")
            popup.setOnMenuItemClickListener { m ->
                currentSortBy = m.itemId
                loadCurrentContent()
                true
            }
            popup.show()
        }

        binding.btnExplorerMore.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Yeni Klasör")
            popup.menu.add(0, 2, 1, "Yeni Metin Dosyası")
            popup.menu.add(0, 3, 2, if (AppSettings.showHiddenFiles) "Gizli Dosyaları Gizle" else "Gizli Dosyaları Göster")
            popup.menu.add(0, 4, 3, "Yenile")
            popup.menu.add(0, 5, 4, "Ayarlar")
            popup.setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    1 -> showCreateNewFolderDialog()
                    2 -> showCreateNewFileDialog()
                    3 -> {
                        AppSettings.showHiddenFiles = !AppSettings.showHiddenFiles
                        loadCurrentContent()
                    }
                    4 -> loadCurrentContent()
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            }
            popup.show()
        }
    }

    private fun setupBottomActionBar() {
        binding.btnActionCopy.setOnClickListener {
            ClipboardFileHolder.files = fileAdapter.getSelectedItems().map { File(it.path) }
            ClipboardFileHolder.isMove = false
            fileAdapter.clearSelection()
            binding.btnActionPasteHere.visibility = View.VISIBLE
            Toast.makeText(this, "Kopyalandı. Hedef klasörde 'Buraya Yapıştır'a dokunun.", Toast.LENGTH_SHORT).show()
        }
        binding.btnActionMove.setOnClickListener {
            ClipboardFileHolder.files = fileAdapter.getSelectedItems().map { File(it.path) }
            ClipboardFileHolder.isMove = true
            fileAdapter.clearSelection()
            binding.btnActionPasteHere.visibility = View.VISIBLE
            Toast.makeText(this, "Kesildi. Hedef klasörde 'Buraya Yapıştır'a dokunun.", Toast.LENGTH_SHORT).show()
        }
        binding.btnActionPasteHere.setOnClickListener {
            if (mode == "LOCAL") {
                lifecycleScope.launch {
                    val dest = File(currentPath)
                    for (f in ClipboardFileHolder.files) {
                        LocalFileRepository.copyOrMoveFile(f, dest, ClipboardFileHolder.isMove)
                    }
                    ClipboardFileHolder.files = emptyList()
                    binding.btnActionPasteHere.visibility = View.GONE
                    binding.bottomActionBar.visibility = View.GONE
                    loadCurrentContent()
                }
            }
        }
        binding.btnActionRename.setOnClickListener {
            val first = fileAdapter.getSelectedItems().firstOrNull() ?: return@setOnClickListener
            showRenameDialog(first)
        }
        binding.btnActionDelete.setOnClickListener {
            val selected = fileAdapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Dosyaları Sil")
                .setMessage("${selected.size} öğe silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    lifecycleScope.launch {
                        for (item in selected) {
                            if (item.sourceType == FileSourceType.LOCAL) {
                                LocalFileRepository.deleteFileOrRecycle(File(item.path))
                            }
                        }
                        fileAdapter.clearSelection()
                        updateSelectionBar()
                        loadCurrentContent()
                    }
                }
                .setNegativeButton("İptal", null)
                .show()
        }
        binding.btnActionOpenWith.setOnClickListener {
            val first = fileAdapter.getSelectedItems().firstOrNull() ?: return@setOnClickListener
            LocalFileRepository.openWithExternalChooser(this, first, cloudAccount?.accessToken ?: "")
        }
    }

    private fun updateSelectionBar() {
        val count = fileAdapter.selectedIds.size
        binding.bottomActionBar.visibility = if (count > 0 || ClipboardFileHolder.files.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadCurrentContent() {
        binding.pbLoading.visibility = View.VISIBLE
        updateBreadcrumbViews()

        lifecycleScope.launch {
            val items = when (mode) {
                "CLOUD" -> {
                    val acc = cloudAccount!!
                    val currentFolderId = cloudFolderStack.peek().first
                    CloudManager.listCloudFolder(this@FileExplorerActivity, acc, currentFolderId)
                }
                "REMOTE" -> {
                    val rem = remoteAccount!!
                    RemoteManager.listRemoteDirectory(rem, currentPath)
                }
                "CATEGORY" -> {
                    LocalFileRepository.scanCategoryFiles(categoryName)
                }
                else -> {
                    LocalFileRepository.listDirectory(File(currentPath), AppSettings.showHiddenFiles, currentSortBy)
                }
            }
            allLoadedItems = items
            fileAdapter.submitList(items)
            binding.pbLoading.visibility = View.GONE
            binding.swipeRefreshFiles.isRefreshing = false
            binding.tvEmptyFolder.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateBreadcrumbViews() {
        binding.llBreadcrumbs.removeAllViews()
        val segments: List<String> = when (mode) {
            "CLOUD" -> cloudFolderStack.map { it.second }
            "CATEGORY" -> listOf(binding.tvExplorerTitle.text.toString())
            else -> {
                val rootPath = LocalFileRepository.rootStorageDir.absolutePath
                val rel = currentPath.removePrefix(rootPath).trim('/')
                if (rel.isEmpty()) listOf("Ana bellek") else listOf("Ana bellek") + rel.split("/")
            }
        }
        for ((index, seg) in segments.withIndex()) {
            val tv = TextView(this).apply {
                text = if (index < segments.lastIndex) "$seg  ›  " else seg
                textSize = 13f
                setTextColor(
                    if (index == segments.lastIndex) ThemeManager.getPrimaryTextColor(this@FileExplorerActivity)
                    else ThemeManager.getSecondaryTextColor(this@FileExplorerActivity)
                )
            }
            binding.llBreadcrumbs.addView(tv)
        }
    }

    private fun onFileItemClicked(item: FileItem) {
        if (item.isDirectory) {
            when (mode) {
                "CLOUD" -> {
                    cloudFolderStack.push(item.id to item.name)
                    loadCurrentContent()
                }
                "REMOTE" -> {
                    currentPath = item.path
                    loadCurrentContent()
                }
                else -> {
                    mode = "LOCAL"
                    currentPath = item.path
                    binding.tvExplorerTitle.text = item.name
                    loadCurrentContent()
                }
            }
            return
        }

        // Bulut dosyasına tıklandığında videodaki (00:39 ve 00:55) gibi hem Dahili Oynatıcı hem "Şununla aç" desteği
        if (item.sourceType == FileSourceType.CLOUD) {
            showCloudFileOpenDialog(item)
            return
        }

        // Yerel dosya açma
        when {
            item.isVideoFile() && AppSettings.useInternalVideoPlayer -> {
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("VIDEO_URL", item.path)
                    putExtra("VIDEO_TITLE", item.name)
                })
            }
            item.isImageFile() && AppSettings.useInternalImageViewer -> {
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra("IMAGE_PATH", item.path)
                    putExtra("IMAGE_TITLE", item.name)
                })
            }
            item.isAudioFile() && AppSettings.useInternalMusicPlayer -> {
                startActivity(Intent(this, MusicPlayerActivity::class.java).apply {
                    putExtra("AUDIO_URL", item.path)
                    putExtra("AUDIO_TITLE", item.name)
                })
            }
            (item.extension in setOf("txt", "md", "json", "xml", "log", "ini", "csv", "html") && AppSettings.useInternalTextEditor) -> {
                startActivity(Intent(this, TextEditorActivity::class.java).apply {
                    putExtra("FILE_PATH", item.path)
                    putExtra("FILE_TITLE", item.name)
                })
            }
            else -> LocalFileRepository.openWithExternalChooser(this, item)
        }
    }

    /**
     * Videodaki (00:39 ve 00:55) Google Drive dosyası tıklama davranışı:
     * Video ise doğrudan Dahili Video Oynatıcıda akış başlatabilir veya "Şununla aç" (MediaInfo, mpvKt, Nova, 1DM+, Seal) açabilir.
     */
    private fun showCloudFileOpenDialog(item: FileItem) {
        if (item.isVideoFile() && AppSettings.useInternalVideoPlayer) {
            // Doğrudan dahili video oynatıcıda başlat (veya oynatıcı içindeki sağ üst menüden "Şununla aç" seçilebilir)
            val streamUrl = item.downloadUrl.ifBlank {
                "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media"
            }
            startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("VIDEO_URL", streamUrl)
                putExtra("VIDEO_TITLE", item.name)
                putExtra("AUTH_TOKEN", cloudAccount?.accessToken ?: "")
            })
        } else {
            LocalFileRepository.openWithExternalChooser(this, item, cloudAccount?.accessToken ?: "")
        }
    }

    private fun showCreateNewFolderDialog() {
        if (mode != "LOCAL") return
        val et = EditText(this).apply { hint = "Klasör adı" }
        AlertDialog.Builder(this)
            .setTitle("Yeni Klasör")
            .setView(et)
            .setPositiveButton("Oluştur") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    File(currentPath, name).mkdirs()
                    loadCurrentContent()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showCreateNewFileDialog() {
        if (mode != "LOCAL") return
        val et = EditText(this).apply { hint = "Dosya adı (örn: notlar.txt)" }
        AlertDialog.Builder(this)
            .setTitle("Yeni Dosya")
            .setView(et)
            .setPositiveButton("Oluştur") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    File(currentPath, name).createNewFile()
                    loadCurrentContent()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showRenameDialog(item: FileItem) {
        if (item.sourceType != FileSourceType.LOCAL) return
        val et = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("Yeniden Adlandır")
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val f = File(item.path)
                    f.renameTo(File(f.parentFile, newName))
                    fileAdapter.clearSelection()
                    updateSelectionBar()
                    loadCurrentContent()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fileAdapter.selectedIds.isNotEmpty()) {
            fileAdapter.clearSelection()
            updateSelectionBar()
            return
        }
        if (mode == "CLOUD" && cloudFolderStack.size > 1) {
            cloudFolderStack.pop()
            loadCurrentContent()
            return
        }
        if (mode == "LOCAL") {
            val rootPath = LocalFileRepository.rootStorageDir.absolutePath
            val curFile = File(currentPath)
            if (curFile.absolutePath != rootPath && curFile.parentFile != null &&
                curFile.parentFile!!.absolutePath.startsWith(rootPath)
            ) {
                currentPath = curFile.parentFile!!.absolutePath
                binding.tvExplorerTitle.text = curFile.parentFile!!.name
                loadCurrentContent()
                return
            }
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

object ClipboardFileHolder {
    var files: List<File> = emptyList()
    var isMove: Boolean = false
}
