package com.alphainventor.filemanager.ui.adapter

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.databinding.ItemDrawerNavBinding
import com.alphainventor.filemanager.databinding.ItemFileGridBinding
import com.alphainventor.filemanager.databinding.ItemFileRowBinding
import com.alphainventor.filemanager.databinding.ItemHomeGridBinding
import com.alphainventor.filemanager.model.*
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import java.io.File

/**
 * 1. ANA EKRAN KATEGORİ IZGARASI (Orijinal DesktopGridView & desktop2_item.xml birebir karşılığı)
 */
class HomeGridAdapter(
    private var items: List<HomeCategoryItem>,
    private val onClick: (HomeCategoryItem) -> Unit
) : RecyclerView.Adapter<HomeGridAdapter.HomeVH>() {

    fun updateData(newItems: List<HomeCategoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeVH {
        val binding = ItemHomeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HomeVH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: HomeVH, position: Int) {
        holder.bind(items[position])
    }

    inner class HomeVH(private val binding: ItemHomeGridBinding) : RecyclerView.ViewHolder(binding.root) {
        private var dynamicPieView: com.alphainventor.filemanager.widget.PieProgressView? = null

        fun bind(item: HomeCategoryItem) = with(binding) {
            val ctx = root.context
            val density = ctx.resources.displayMetrics.density
            tvCategoryTitle.text = item.title
            tvCategoryTitle.textSize = 13.5f
            tvCategoryTitle.setTextColor(ThemeManager.getPrimaryTextColor(ctx))

            tvCategorySubtitle.text = item.subtitle
            tvCategorySubtitle.textSize = 11.5f
            tvCategorySubtitle.setTextColor(ThemeManager.getSecondaryTextColor(ctx))

            val iconFrame = ivCategoryIcon.parent as? FrameLayout
            // Orijinal APK'daki (00:01) büyük ve dolgun simge boyutu (74dp)
            val boxSize = (74 * density).toInt()
            iconFrame?.layoutParams = iconFrame?.layoutParams?.apply {
                width = boxSize
                height = boxSize
            }

            if (iconFrame != null) {
                for (i in 0 until iconFrame.childCount) {
                    val ch = iconFrame.getChildAt(i)
                    if (ch is android.widget.ProgressBar) {
                        ch.visibility = View.GONE
                    } else if (ch is com.alphainventor.filemanager.widget.PieProgressView) {
                        dynamicPieView = ch
                    }
                }
                if (dynamicPieView == null) {
                    val pv = com.alphainventor.filemanager.widget.PieProgressView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        visibility = View.GONE
                    }
                    iconFrame.addView(pv)
                    dynamicPieView = pv
                }
            }

            when {
                item.showPieChart -> {
                    iconFrame?.background = null
                    iconFrame?.setPadding(0, 0, 0, 0)
                    ivCategoryIcon.visibility = View.GONE
                    dynamicPieView?.visibility = View.VISIBLE
                    dynamicPieView?.progressPercent = item.piePercent
                    dynamicPieView?.invalidate()
                }
                item.type == HomeCategoryType.MAIN_STORAGE ||
                item.type == HomeCategoryType.SD_CARD ||
                item.type == HomeCategoryType.USB_STORAGE ||
                item.type == HomeCategoryType.DOWNLOADS ||
                item.type == HomeCategoryType.RECYCLE_BIN -> {
                    iconFrame?.background = null
                    iconFrame?.setPadding(0, 0, 0, 0)
                    dynamicPieView?.visibility = View.GONE
                    ivCategoryIcon.visibility = View.VISIBLE
                    ivCategoryIcon.layoutParams = ivCategoryIcon.layoutParams?.apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    ivCategoryIcon.setImageResource(item.iconRes)
                }
                else -> {
                    iconFrame?.background = ThemeManager.createHomeTileSquircle(ctx)
                    val pad = (13 * density).toInt()
                    iconFrame?.setPadding(pad, pad, pad, pad)
                    dynamicPieView?.visibility = View.GONE
                    ivCategoryIcon.visibility = View.VISIBLE
                    ivCategoryIcon.layoutParams = ivCategoryIcon.layoutParams?.apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    ivCategoryIcon.setImageResource(item.iconRes)
                }
            }

            // Ana bellek ve SD kart yatay mavi doluluk çubuğu kontrolü
            var storageBar: android.widget.ProgressBar? = null
            for (i in 0 until root.childCount) {
                val ch = root.getChildAt(i)
                if (ch is android.widget.ProgressBar) {
                    storageBar = ch
                    break
                }
            }
            if (storageBar == null && item.showStorageBar) {
                val pb = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (82 * density).toInt(),
                        (4 * density).toInt()
                    ).apply {
                        topMargin = (3 * density).toInt()
                    }
                    max = 100
                    progressDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.progress_storage_bar)
                }
                val titleIdx = root.indexOfChild(tvCategoryTitle)
                if (titleIdx >= 0) {
                    root.addView(pb, titleIdx + 1)
                } else {
                    root.addView(pb)
                }
                storageBar = pb
            }

            if (item.showStorageBar && item.totalBytes > 0L) {
                storageBar?.visibility = View.VISIBLE
                storageBar?.progress = ((item.usedBytes.toDouble() / item.totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            } else {
                storageBar?.visibility = View.GONE
            }

            root.setOnClickListener { onClick(item) }
        }
    }
}

/**
 * 2. DOSYA, KLASÖR KUTUCUĞU VE ARAMA SONUÇLARI ADAPTER'I
 * - Arama modunda (highlightQuery doluysa) dosya adındaki eşleşen kelimeleri sarı fosforlu (#FFF59D) vurgular.
 * - Medya Kategorilerinde (Videolar, Görüntüler, Ses) Klasör Kutucuklarını ("InstaEclipse (64)", "Movies (6)")
 *   ilk medyanın küçük resmi ve sağ alt kategori rozetiyle gösterir.
 */
class FileListAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val showAccountOwnerBadge: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1
    }

    private val items = ArrayList<FileItem>()
    private val selectedIds = HashSet<String>()
    var selectionMode: Boolean = false
        private set

    var highlightQuery: String = ""
        set(value) {
            field = value.trim()
        }

    var isGridMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(newList: List<FileItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    fun getCurrentItems(): List<FileItem> = items

    fun toggleSelection(item: FileItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
            if (selectedIds.isEmpty()) selectionMode = false
        } else {
            selectedIds.add(item.id)
            selectionMode = true
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectionMode = true
        items.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<FileItem> = items.filter { selectedIds.contains(it.id) }

    override fun getItemViewType(position: Int): Int = if (isGridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            GridVH(ItemFileGridBinding.inflate(inflater, parent, false))
        } else {
            FileVH(ItemFileRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is GridVH) {
            holder.bind(item)
        } else if (holder is FileVH) {
            holder.bind(item)
        }
    }

    private fun buildHighlightedText(fullText: String, query: String): CharSequence {
        if (query.isEmpty()) return fullText
        val spannable = SpannableString(fullText)
        val lowerFull = fullText.lowercase()
        val lowerQ = query.lowercase()
        var startIdx = lowerFull.indexOf(lowerQ)
        while (startIdx >= 0) {
            val endIdx = (startIdx + lowerQ.length).coerceAtMost(fullText.length)
            spannable.setSpan(
                BackgroundColorSpan(Color.parseColor("#FFF59D")),
                startIdx,
                endIdx,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#000000")),
                startIdx,
                endIdx,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIdx = lowerFull.indexOf(lowerQ, endIdx)
        }
        return spannable
    }

    private fun resolveOriginalFileIcon(item: FileItem): Int {
        if (item.isDirectory) {
            val lower = item.name.lowercase()
            return when {
                lower == "download" || lower == "downloads" || lower == "indirilenler" -> R.drawable.icon_folder_download
                lower == "dcim" || lower == "camera" || lower == "kamera" -> R.drawable.icon_folder_camera
                item.childCount == 0 -> R.drawable.icon_folder_empty
                else -> R.drawable.icon_folder_full
            }
        }
        val mime = item.mimeType.lowercase()
        val ext = item.extension
        return when {
            mime == "application/vnd.google-apps.document" -> R.drawable.icon_file_gdoc
            mime == "application/vnd.google-apps.spreadsheet" -> R.drawable.icon_file_gsheet
            mime == "application/vnd.google-apps.presentation" -> R.drawable.icon_file_gslides
            mime == "application/vnd.google-apps.drawing" -> R.drawable.icon_file_gdraw
            mime == "application/vnd.google-apps.form" -> R.drawable.icon_file_gform
            item.isApk -> R.drawable.ic_file_apk
            ext == "pdf" -> R.drawable.icon_file_pdf
            ext in setOf("doc", "docx", "rtf", "odt") -> R.drawable.icon_file_word
            ext in setOf("xls", "xlsx", "csv", "ods") -> R.drawable.icon_file_excel
            ext in setOf("ppt", "pptx", "odp") -> R.drawable.icon_file_powerpoint
            ext in setOf("epub", "mobi") -> R.drawable.icon_file_ebook
            ext in setOf("txt", "md", "log", "json", "xml", "ini", "html") -> R.drawable.icon_file_plaintext
            item.isVideo -> R.drawable.icon_file_video
            item.isAudio -> R.drawable.icon_file_music
            item.isImage -> R.drawable.icon_file_image
            item.isArchive -> R.drawable.icon_file_archive
            else -> R.drawable.icon_unknown
        }
    }

    inner class FileVH(private val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) = with(binding) {
            val ctx = root.context
            val primaryColor = ThemeManager.getPrimaryTextColor(ctx)
            val secondaryColor = ThemeManager.getSecondaryTextColor(ctx)

            if (item.sectionHeader.isNotBlank()) {
                tvSectionHeader.visibility = View.VISIBLE
                tvSectionHeader.text = item.sectionHeader
            } else {
                tvSectionHeader.visibility = View.GONE
            }
            tvIconBadge.visibility = View.GONE
            tvFileName.setTextColor(primaryColor)
            tvFileName.text = buildHighlightedText(item.name, highlightQuery)

            tvFileDate.text = FormatUtils.formatDate(item.lastModified)
            tvFileDate.setTextColor(secondaryColor)

            val baseSub = if (item.isDirectory) {
                if (item.childCount >= 0) "${item.childCount} öğe" else "Klasör"
            } else {
                FormatUtils.formatSize(item.size)
            }

            if (showAccountOwnerBadge && item.accountEmailOrLabel.isNotBlank()) {
                // Orijinal Arama Sonucu formatı (Screenshot_20261028_124124_File Manager +.png):
                // Satır 2: 486 MB
                // Satır 3: /Klasör_Yolu/ • damovie355@gmail.com
                tvFileSubInfo.isSingleLine = false
                tvFileSubInfo.maxLines = 2
                val folderDisplay = if (item.parentId.isNotBlank() && item.parentId != "root") {
                    if (item.parentId.startsWith("/")) item.parentId else "/${item.parentId}/"
                } else {
                    "/${item.providerName}/"
                }
                tvFileSubInfo.text = "$baseSub\n$folderDisplay • ${item.accountEmailOrLabel}"
                tvFileSubInfo.setTextColor(secondaryColor)
            } else {
                tvFileSubInfo.isSingleLine = true
                tvFileSubInfo.maxLines = 1
                tvFileSubInfo.text = baseSub
                tvFileSubInfo.setTextColor(secondaryColor)
            }

            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            ivFileIcon.tag = item.path
            ivPlayOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            val iconRes = resolveOriginalFileIcon(item)
            ivFileIcon.setImageResource(iconRes)

            if (!item.isDirectory && item.sourceType == FileSourceType.LOCAL) {
                val f = File(item.path)
                if (f.exists()) {
                    if (item.isImage) {
                        ThumbnailCacheManager.loadImageThumbnail(f, ivFileIcon, iconRes)
                    } else if (item.isVideo) {
                        ThumbnailCacheManager.loadVideoThumbnail(f, ivFileIcon, iconRes)
                    }
                }
            }

            val isSelected = selectedIds.contains(item.id)
            cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = isSelected
            root.setBackgroundColor(
                if (isSelected) Color.parseColor("#26008578") else Color.TRANSPARENT
            )

            root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }

            root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    inner class GridVH(private val binding: ItemFileGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) = with(binding) {
            val ctx = root.context
            val isDark = ThemeManager.isDarkOrAmoled(ctx)
            val thumbFrame = ivGridThumb.parent as? FrameLayout
            thumbFrame?.setBackgroundColor(
                if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#F2F2F2")
            )

            val label = if (item.isDirectory && item.childCount >= 0) {
                "${item.name} (${item.childCount})"
            } else {
                item.name
            }
            tvGridFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvGridFileName.text = buildHighlightedText(label, highlightQuery)

            val iconRes = resolveOriginalFileIcon(item)
            ivGridThumb.tag = item.path
            ivGridThumb.scaleType = ImageView.ScaleType.CENTER_CROP
            ivGridThumb.setImageResource(iconRes)
            ivGridPlayOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            if (!item.isDirectory && item.sourceType == FileSourceType.LOCAL) {
                val f = File(item.path)
                if (f.exists()) {
                    if (item.isImage) {
                        ThumbnailCacheManager.loadImageThumbnail(f, ivGridThumb, iconRes)
                    } else if (item.isVideo) {
                        ThumbnailCacheManager.loadVideoThumbnail(f, ivGridThumb, iconRes)
                    }
                }
            } else if (item.isDirectory && item.sourceType == FileSourceType.LOCAL) {
                val previewFile = if (item.firstMediaPreviewPath.isNotBlank()) {
                    File(item.firstMediaPreviewPath).takeIf { it.exists() }
                } else {
                    File(item.path).listFiles()?.firstOrNull { child ->
                        child.isFile && (child.extension.lowercase() in FileItem.VIDEO_EXTENSIONS || child.extension.lowercase() in FileItem.IMAGE_EXTENSIONS)
                    }
                }
                if (previewFile != null) {
                    ivGridThumb.tag = previewFile.absolutePath
                    if (previewFile.extension.lowercase() in FileItem.IMAGE_EXTENSIONS) {
                        ThumbnailCacheManager.loadImageThumbnail(previewFile, ivGridThumb, iconRes)
                    } else if (previewFile.extension.lowercase() in FileItem.VIDEO_EXTENSIONS) {
                        ThumbnailCacheManager.loadVideoThumbnail(previewFile, ivGridThumb, iconRes)
                    } else {
                        ivGridThumb.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                } else {
                    ivGridThumb.scaleType = ImageView.ScaleType.FIT_CENTER
                }
            }

            val isSelected = selectedIds.contains(item.id)
            root.setBackgroundColor(
                if (isSelected) Color.parseColor("#33008578") else Color.TRANSPARENT
            )

            root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }

            root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}

/**
 * 3. BULUT HESAPLARI IZGARASI ADAPTER'I (Orijinal Bulut ekranındaki 3 sütunlu Google Drive görünümü:
 *    Screenshot_20261028_124104_File Manager +.png & burada Google Drive hesap ekleme...mp4 00:01)
 */
class CloudAccountsAdapter(
    private var accounts: List<CloudAccount>,
    private val onAccountClick: (CloudAccount) -> Unit,
    private val onAccountLongClick: (CloudAccount) -> Unit
) : RecyclerView.Adapter<CloudAccountsAdapter.CloudVH>() {

    var isEditMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun updateAccounts(newAccounts: List<CloudAccount>) {
        accounts = newAccounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloudVH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
        }
        val iv = ImageView(ctx).apply {
            val sz = (58 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tvTitle = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            gravity = Gravity.CENTER
            textSize = 13.5f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvSub = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 11.5f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(iv)
        root.addView(tvTitle)
        root.addView(tvSub)
        return CloudVH(root, iv, tvTitle, tvSub)
    }

    override fun getItemCount(): Int = accounts.size

    override fun onBindViewHolder(holder: CloudVH, position: Int) {
        holder.bind(accounts[position])
    }

    inner class CloudVH(
        private val root: LinearLayout,
        private val ivIcon: ImageView,
        private val tvTitle: TextView,
        private val tvSub: TextView
    ) : RecyclerView.ViewHolder(root) {
        fun bind(acc: CloudAccount) {
            val ctx = root.context
            tvTitle.text = if (isEditMode) "${acc.provider.displayName} ✎" else acc.provider.displayName
            tvTitle.setTextColor(ThemeManager.getPrimaryTextColor(ctx))

            tvSub.text = acc.emailOrName
            tvSub.setTextColor(ThemeManager.getSecondaryTextColor(ctx))

            val iconRes = when (acc.provider) {
                CloudProviderType.GOOGLE_DRIVE -> R.drawable.cloud_icon_googledrive
                CloudProviderType.DROPBOX -> R.drawable.cloud_icon_dropbox
                CloudProviderType.ONEDRIVE -> R.drawable.cloud_icon_onedrive
                CloudProviderType.BOX -> R.drawable.cloud_icon_box
                CloudProviderType.NEXTCLOUD -> R.drawable.ic_cloud_nextcloud
                CloudProviderType.YANDEX -> R.drawable.cloud_icon_yandex
            }
            ivIcon.setImageResource(iconRes)

            root.setOnClickListener { onAccountClick(acc) }
            root.setOnLongClickListener {
                onAccountLongClick(acc)
                true
            }
        }
    }
}

/**
 * 4. UZAK BAĞLANTILAR (FTP/SFTP/SMB/WebDAV) ADAPTER'I
 */
class RemoteAccountsAdapter(
    private var accounts: List<RemoteAccount> = emptyList(),
    private val onClick: (RemoteAccount) -> Unit,
    private val onLongClick: (RemoteAccount) -> Unit
) : RecyclerView.Adapter<RemoteAccountsAdapter.RemoteVH>() {

    fun updateAccounts(newList: List<RemoteAccount>) {
        accounts = newList
        notifyDataSetChanged()
    }

    fun submitList(newList: List<RemoteAccount>) = updateAccounts(newList)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteVH {
        val binding = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RemoteVH(binding)
    }

    override fun getItemCount(): Int = accounts.size

    override fun onBindViewHolder(holder: RemoteVH, position: Int) {
        holder.bind(accounts[position])
    }

    inner class RemoteVH(private val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(acc: RemoteAccount) = with(binding) {
            val ctx = root.context
            tvFileName.text = acc.displayName
            tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))

            tvFileSubInfo.text = "${acc.protocol.displayName} • ${acc.username}@${acc.host}:${acc.port}"
            tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            tvFileDate.text = ""
            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            val iconRes = when (acc.protocol) {
                RemoteProtocolType.LAN, RemoteProtocolType.SMB -> R.drawable.icon_folder_lan
                RemoteProtocolType.FTP, RemoteProtocolType.FTPS -> R.drawable.icon_folder_ftp
                RemoteProtocolType.SFTP -> R.drawable.icon_folder_sftp
                RemoteProtocolType.WEBDAV -> R.drawable.icon_folder_webdav
            }
            ivFileIcon.setImageResource(iconRes)
            ivPlayOverlay.visibility = View.GONE
            cbSelect.visibility = View.GONE

            root.setOnClickListener { onClick(acc) }
            root.setOnLongClickListener {
                onLongClick(acc)
                true
            }
        }
    }
}

/**
 * 5. SOL ÇEKMECE (DRAWER) LİSTESİ ADAPTER'I
 */
data class DrawerEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val rightBadge: String = "",
    val iconRes: Int,
    val payload: Any? = null
)

class DrawerNavAdapter(
    private var entries: List<DrawerEntry>,
    private val onClick: (DrawerEntry) -> Unit
) : RecyclerView.Adapter<DrawerNavAdapter.DrawerVH>() {

    fun updateEntries(newEntries: List<DrawerEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DrawerVH {
        val binding = ItemDrawerNavBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DrawerVH(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: DrawerVH, position: Int) {
        holder.bind(entries[position])
    }

    inner class DrawerVH(private val binding: ItemDrawerNavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: DrawerEntry) = with(binding) {
            val ctx = root.context
            tvDrawerTitle.text = if (entry.subtitle.isNotBlank()) "${entry.title}\n${entry.subtitle}" else entry.title
            tvDrawerTitle.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvDrawerRightBadge.text = entry.rightBadge
            tvDrawerRightBadge.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            ivDrawerIcon.setImageResource(entry.iconRes)
            root.setOnClickListener { onClick(entry) }
        }
    }
}
