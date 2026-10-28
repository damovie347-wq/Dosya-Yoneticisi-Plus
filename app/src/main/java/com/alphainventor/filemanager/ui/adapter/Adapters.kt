package com.alphainventor.filemanager.ui.adapter

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.databinding.ItemDrawerNavBinding
import com.alphainventor.filemanager.databinding.ItemFileGridBinding
import com.alphainventor.filemanager.databinding.ItemFileRowBinding
import com.alphainventor.filemanager.databinding.ItemHomeGridBinding
import com.alphainventor.filemanager.model.*
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager

/**
 * 1. ANA EKRAN KATEGORİ IZGARASI (Orijinal DesktopGridView & desktop2_item.xml birebir karşılığı)
 */
class HomeGridAdapter(
    private var items: List<HomeCategoryItem>,
    private val onClick: (HomeCategoryItem) -> Unit
) : RecyclerView.Adapter<HomeGridAdapter.HomeVH>() {

    init {
        setHasStableIds(true)
    }

    fun updateData(newItems: List<HomeCategoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].type.ordinal.toLong() + (items[position].customPath.hashCode().toLong() shl 8)

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
 * 2. DOSYA, 1:1 KARE MEDYA KLASÖR KUTUCUĞU VE ARAMA SONUÇLARI ADAPTER'I (120Hz Optimize)
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

    var isCompactListMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    fun submitList(newList: List<FileItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = items[oldPos]
                val n = newList[newPos]
                return o.name == n.name &&
                    o.size == n.size &&
                    o.lastModified == n.lastModified &&
                    o.childCount == n.childCount &&
                    o.firstMediaPreviewPath == n.firstMediaPreviewPath &&
                    highlightQuery.isEmpty()
            }
        })
        items.clear()
        items.addAll(newList)
        diff.dispatchUpdatesTo(this)
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

    /**
     * Orijinal APK'daki (Söylediğim dosya ayarındaki çıkan panel...jpg & Video 1 00:05) gibi
     * medya klasörü adını tek satıra sığdırırken sondaki "(8)" / "(6)" sayısını her zaman korur:
     * Örn: "Alien (Saga) Av1 A5.1 1080p 7RIP" + 7 -> "Alien (Saga) Av1... (7)"
     */
    private fun formatMediaBucketSingleLine(folderName: String, count: Int): String {
        if (count < 0) return folderName
        val clean = if (folderName.length > 18) {
            folderName.take(16).trimEnd() + "..."
        } else {
            folderName
        }
        return "$clean ($count)"
    }

    /**
     * Orijinal file_media_grid_item.xml içindeki sağ alt 20dp klasör türü rozet ikonu:
     * - Screen recordings, Camera, DCIM, mcpro24fps -> v_shape_camera
     * - Download, 1DM, Seal -> v_shape_download
     * - Movies, Video, youcut, inshot, RecorderX -> v_shape_video
     * - Screenshots, Pictures, Instagram, WhatsApp Images -> v_shape_image
     * - Diğer klasörler -> v_shape_folder_dir
     */
    private fun resolveFolderBadgeIcon(item: FileItem): Int {
        val lower = item.name.lowercase()
        return when {
            lower.contains("record") || lower.contains("camera") || lower.contains("dcim") || lower.contains("mcpro") -> R.drawable.v_shape_camera
            lower.contains("download") || lower.contains("1dm") || lower.contains("seal") || lower.contains("indir") -> R.drawable.v_shape_download
            lower.contains("movie") || lower.contains("video") || lower.contains("youcut") || lower.contains("inshot") || lower.contains("film") -> R.drawable.v_shape_video
            lower.contains("screen") || lower.contains("picture") || lower.contains("photo") || lower.contains("image") || lower.contains("whatsapp") -> R.drawable.v_shape_image
            item.mediaCategoryBadge == "VIDEOS" -> R.drawable.v_shape_folder_dir
            item.mediaCategoryBadge == "IMAGES" -> R.drawable.v_shape_folder_dir
            else -> R.drawable.v_shape_folder_dir
        }
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
                if (item.childCount >= 0) "(${item.childCount})" else "Klasör"
            } else {
                FormatUtils.formatSize(item.size)
            }

            if (showAccountOwnerBadge && item.accountEmailOrLabel.isNotBlank()) {
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
                tvFileSubInfo.text = if (item.isDirectory && item.childCount >= 0) "${item.childCount} öğe" else baseSub
                tvFileSubInfo.setTextColor(secondaryColor)
            }

            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            ivPlayOverlay.visibility = if (!item.isDirectory && item.isVideo) View.VISIBLE else View.GONE

            val iconRes = resolveOriginalFileIcon(item)
            if (!item.isDirectory && item.sourceType == FileSourceType.LOCAL && AppSettings.showThumbnails && (item.isImage || item.isVideo)) {
                ThumbnailCacheManager.loadThumbnailByPath(
                    path = item.path,
                    lastModified = item.lastModified,
                    isVideo = item.isVideo,
                    imageView = ivFileIcon,
                    fallbackRes = if (item.isVideo) 0 else iconRes
                )
            } else if (item.isDirectory && item.firstMediaPreviewPath.isNotBlank() && AppSettings.showThumbnails) {
                val previewIsVideo = item.mediaCategoryBadge == "VIDEOS" ||
                    item.firstMediaPreviewPath.substringAfterLast('.', "").lowercase() in FileItem.VIDEO_EXTENSIONS
                ThumbnailCacheManager.loadThumbnailByPath(
                    path = item.firstMediaPreviewPath,
                    lastModified = item.lastModified,
                    isVideo = previewIsVideo,
                    imageView = ivFileIcon,
                    fallbackRes = iconRes
                )
            } else {
                ivFileIcon.tag = null
                ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                ivFileIcon.setImageResource(iconRes)
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
            val isMediaBucket = item.isDirectory && item.mediaCategoryBadge in setOf("VIDEOS", "IMAGES")

            tvGridFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))

            if (isMediaBucket) {
                // Orijinal file_media_grid_item.xml: 1:1 Kare + Sağ alt rozet + Tek satır "Movies (6)"
                flGridThumbContainer.setBackgroundColor(Color.BLACK)
                tvGridFileName.maxLines = 1
                tvGridFileName.minLines = 1
                tvGridFileName.isSingleLine = true
                tvGridFileName.ellipsize = TextUtils.TruncateAt.END
                val formattedLabel = formatMediaBucketSingleLine(item.name, item.childCount)
                tvGridFileName.text = buildHighlightedText(formattedLabel, highlightQuery)

                ivGridPlayOverlay.visibility = View.GONE
                ivGridCategoryBadge.visibility = View.VISIBLE
                ivGridCategoryBadge.setImageResource(resolveFolderBadgeIcon(item))

                val previewIsVideo = item.mediaCategoryBadge == "VIDEOS" ||
                    item.firstMediaPreviewPath.substringAfterLast('.', "").lowercase() in FileItem.VIDEO_EXTENSIONS
                if (item.firstMediaPreviewPath.isNotBlank() && AppSettings.showThumbnails) {
                    ThumbnailCacheManager.loadThumbnailByPath(
                        path = item.firstMediaPreviewPath,
                        lastModified = item.lastModified,
                        isVideo = previewIsVideo,
                        imageView = ivGridThumb,
                        fallbackRes = 0
                    )
                } else {
                    ivGridThumb.tag = null
                    ivGridThumb.setImageDrawable(null)
                }
            } else if (!item.isDirectory && (item.isVideo || item.isImage)) {
                // Orijinal Video/Resim Klasör İçi Izgarası (Video 1 00:08-00:11):
                // Turuncu klasör ikonu ASLA gösterilmez; siyah kare zemin + ortada Play ikonu + 2 satır dosya adı!
                flGridThumbContainer.setBackgroundColor(Color.BLACK)
                tvGridFileName.isSingleLine = false
                tvGridFileName.maxLines = 2
                tvGridFileName.minLines = 2
                tvGridFileName.ellipsize = TextUtils.TruncateAt.END
                tvGridFileName.text = buildHighlightedText(item.name, highlightQuery)

                ivGridPlayOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                ivGridCategoryBadge.visibility = View.GONE

                if (item.sourceType == FileSourceType.LOCAL && AppSettings.showThumbnails) {
                    ThumbnailCacheManager.loadThumbnailByPath(
                        path = item.path,
                        lastModified = item.lastModified,
                        isVideo = item.isVideo,
                        imageView = ivGridThumb,
                        fallbackRes = 0
                    )
                } else {
                    ivGridThumb.tag = null
                    ivGridThumb.setImageDrawable(null)
                }
            } else {
                // Normal Klasör / Belge / Ses Izgara Kutucuğu
                flGridThumbContainer.setBackgroundColor(
                    if (isDark) Color.parseColor("#181818") else Color.parseColor("#F5F5F5")
                )
                tvGridFileName.isSingleLine = false
                tvGridFileName.maxLines = 2
                tvGridFileName.minLines = 1
                tvGridFileName.ellipsize = TextUtils.TruncateAt.END
                val label = if (item.isDirectory && item.childCount >= 0) {
                    "${item.name} (${item.childCount})"
                } else {
                    item.name
                }
                tvGridFileName.text = buildHighlightedText(label, highlightQuery)

                ivGridPlayOverlay.visibility = View.GONE
                ivGridCategoryBadge.visibility = View.GONE
                val iconRes = resolveOriginalFileIcon(item)
                ivGridThumb.tag = null
                ivGridThumb.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (18 * ctx.resources.displayMetrics.density).toInt()
                ivGridThumb.setPadding(pad, pad, pad, pad)
                ivGridThumb.setImageResource(iconRes)
                return@with
            }

            ivGridThumb.setPadding(0, 0, 0, 0)

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
 * 3. BULUT HESAPLARI ADAPTER'I (Orijinal Bulut ekranındaki hem 3 sütunlu ızgara hem de liste görünümü desteği)
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
            setBackgroundResource(android.R.drawable.list_selector_background)
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
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvSub = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
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
            tvFileName.text = acc.host
            tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))

            tvFileSubInfo.text = acc.username.ifBlank { acc.displayName }
            tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            tvFileDate.text = ""
            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            val iconRes = when (acc.protocol) {
                RemoteProtocolType.LAN -> R.drawable.icon_folder_lan
                RemoteProtocolType.SMB -> R.drawable.icon_folder_smb
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
