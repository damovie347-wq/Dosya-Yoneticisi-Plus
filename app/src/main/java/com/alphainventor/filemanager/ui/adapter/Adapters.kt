package com.alphainventor.filemanager.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.databinding.ItemDrawerNavBinding
import com.alphainventor.filemanager.databinding.ItemFileRowBinding
import com.alphainventor.filemanager.databinding.ItemHomeGridBinding
import com.alphainventor.filemanager.model.*
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import java.io.File

// ==================== 1. ANA EKRAN 12'Lİ KATEGORİ IZGARA ADAPTÖRÜ ====================
class HomeGridAdapter(
    private val onClick: (HomeCategoryItem) -> Unit
) : RecyclerView.Adapter<HomeGridAdapter.VH>() {

    private val items = mutableListOf<HomeCategoryItem>()

    init {
        setHasStableIds(true)
    }

    fun submitList(newItems: List<HomeCategoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].type.ordinal.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHomeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        with(holder.binding) {
            badgeContainer.background = ThemeManager.createHomeCircleDrawable(ctx)
            ivCategoryIcon.setImageResource(item.iconRes)
            tvCategoryTitle.text = item.title
            tvCategoryTitle.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvCategorySubtitle.text = item.subtitle

            if (item.type == HomeCategoryType.MAIN_STORAGE && item.progressPercent >= 0) {
                pbStorageMini.visibility = View.VISIBLE
                pbStorageMini.progress = item.progressPercent
                tvCategorySubtitle.setTextColor(Color.parseColor("#5AB55E"))
            } else {
                pbStorageMini.visibility = View.GONE
                tvCategorySubtitle.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            }

            itemRoot.setOnClickListener { onClick(item) }
        }
    }

    class VH(val binding: ItemHomeGridBinding) : RecyclerView.ViewHolder(binding.root)
}

// ==================== 2. SOL GEZİNME ÇEKMECESİ ADAPTÖRÜ (01:43) ====================
data class DrawerEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val rightBadge: String = "",
    val iconRes: Int,
    val payload: Any? = null
)

class DrawerNavAdapter(
    private val onClick: (DrawerEntry) -> Unit
) : RecyclerView.Adapter<DrawerNavAdapter.VH>() {

    private val list = mutableListOf<DrawerEntry>()

    fun submitList(newList: List<DrawerEntry>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDrawerNavBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        val ctx = holder.itemView.context
        with(holder.binding) {
            ivDrawerIcon.setImageResource(item.iconRes)
            tvDrawerTitle.text = item.title
            tvDrawerTitle.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            if (item.subtitle.isNotBlank()) {
                tvDrawerSubtitle.visibility = View.VISIBLE
                tvDrawerSubtitle.text = item.subtitle
                tvDrawerSubtitle.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            } else {
                tvDrawerSubtitle.visibility = View.GONE
            }
            tvDrawerRightBadge.text = item.rightBadge
            tvDrawerRightBadge.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            drawerItemRoot.setOnClickListener { onClick(item) }
        }
    }

    class VH(val binding: ItemDrawerNavBinding) : RecyclerView.ViewHolder(binding.root)
}

// ==================== 3. DOSYA VE BULUT GEZGİNİ ADAPTÖRÜ ====================
class FileListAdapter(
    private val showAccountBadgeInSubtitle: Boolean = false,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private val items = mutableListOf<FileItem>()
    val selectedIds = LinkedHashSet<String>()

    init {
        setHasStableIds(true)
    }

    fun submitList(newList: List<FileItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    fun toggleSelection(item: FileItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<FileItem> = items.filter { it.id in selectedIds }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val prevSection = if (position > 0) items[position - 1].sectionHeader else ""

        with(holder.binding) {
            if (item.sectionHeader.isNotBlank() && item.sectionHeader != prevSection) {
                tvSectionHeader.visibility = View.VISIBLE
                tvSectionHeader.text = item.sectionHeader
            } else {
                tvSectionHeader.visibility = View.GONE
            }

            tvFileName.text = item.name
            tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvFileDate.text = FormatUtils.formatDate(item.lastModified)
            tvFileDate.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            // Alt bilgi: Klasörse öğe sayısı, dosyaysa boyut + Toplu Bulut Aramada hangi Google Drive hesabında olduğu!
            val baseSub = if (item.isDirectory) {
                if (item.childCount > 0) "${item.childCount} öğe" else "Klasör"
            } else {
                FormatUtils.formatSize(item.size)
            }

            if (showAccountBadgeInSubtitle && item.accountEmailOrLabel.isNotBlank()) {
                tvFileSubInfo.text = "☁ ${item.providerName} (${item.accountEmailOrLabel})  •  $baseSub"
                tvFileSubInfo.setTextColor(Color.parseColor("#26A69A"))
            } else {
                tvFileSubInfo.text = baseSub
                tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            }

            // İkon veya Küçük Resim (Thumbnail)
            tvIconBadge.visibility = View.GONE
            if (item.isDirectory) {
                ivFileIcon.setImageResource(R.drawable.ic_folder_modern)
            } else {
                when {
                    item.mimeType == "application/vnd.google-apps.document" -> ivFileIcon.setImageResource(R.drawable.ic_file_doc)
                    item.mimeType == "application/vnd.google-apps.spreadsheet" -> ivFileIcon.setImageResource(R.drawable.ic_file_sheet)
                    item.extension == "pdf" -> ivFileIcon.setImageResource(R.drawable.ic_file_pdf)
                    item.isVideoFile() -> {
                        tvIconBadge.visibility = View.VISIBLE
                        tvIconBadge.text = item.extension.uppercase().take(4).ifEmpty { "VID" }
                        if (item.sourceType == FileSourceType.LOCAL) {
                            ThumbnailCacheManager.loadThumbnailAsync(File(item.path), true, ivFileIcon, R.drawable.ic_cat_videos)
                        } else {
                            ivFileIcon.setImageResource(R.drawable.ic_cat_videos)
                        }
                    }
                    item.isImageFile() -> {
                        if (item.sourceType == FileSourceType.LOCAL) {
                            ThumbnailCacheManager.loadThumbnailAsync(File(item.path), false, ivFileIcon, R.drawable.ic_cat_images)
                        } else {
                            ivFileIcon.setImageResource(R.drawable.ic_cat_images)
                        }
                    }
                    item.isAudioFile() -> ivFileIcon.setImageResource(R.drawable.ic_cat_audio)
                    item.extension == "apk" -> ivFileIcon.setImageResource(R.drawable.ic_cat_apps)
                    item.isArchiveFile() -> {
                        tvIconBadge.visibility = View.VISIBLE
                        tvIconBadge.text = item.extension.uppercase().take(4)
                        ivFileIcon.setImageResource(R.drawable.ic_file_archive)
                    }
                    else -> ivFileIcon.setImageResource(R.drawable.ic_file_doc)
                }
            }

            val isSelected = item.id in selectedIds
            cbSelect.visibility = if (selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
            cbSelect.isChecked = isSelected
            fileRowRoot.setBackgroundColor(
                if (isSelected) Color.parseColor("#2626A69A") else Color.TRANSPARENT
            )

            fileRowRoot.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }
            fileRowRoot.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    class VH(val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root)
}

// ==================== 4. BULUT HESAPLARI LİSTE ADAPTÖRÜ (00:30) ====================
class CloudAccountsAdapter(
    private val onAccountClick: (CloudAccount) -> Unit,
    private val onAccountLongClick: (CloudAccount) -> Unit
) : RecyclerView.Adapter<CloudAccountsAdapter.VH>() {

    private val accounts = mutableListOf<CloudAccount>()

    fun submitList(newList: List<CloudAccount>) {
        accounts.clear()
        accounts.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = accounts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val acc = accounts[position]
        val ctx = holder.itemView.context
        with(holder.binding) {
            tvSectionHeader.visibility = View.GONE
            tvIconBadge.visibility = View.GONE
            cbSelect.visibility = View.GONE

            tvFileName.text = acc.provider.displayName
            tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvFileSubInfo.text = acc.emailOrName
            tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            tvFileDate.text = if (acc.freeQuotaBytes > 0) "Boş ${FormatUtils.formatSize(acc.freeQuotaBytes)}" else ""
            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            val iconRes = when (acc.provider) {
                CloudProviderType.GOOGLE_DRIVE -> R.drawable.ic_cloud_gdrive
                CloudProviderType.DROPBOX -> R.drawable.ic_cloud_dropbox
                CloudProviderType.ONEDRIVE -> R.drawable.ic_cloud_onedrive
                CloudProviderType.BOX -> R.drawable.ic_cloud_box
                CloudProviderType.NEXTCLOUD -> R.drawable.ic_cloud_nextcloud
                CloudProviderType.YANDEX -> R.drawable.ic_cloud_yandex
            }
            ivFileIcon.setImageResource(iconRes)

            fileRowRoot.setOnClickListener { onAccountClick(acc) }
            fileRowRoot.setOnLongClickListener {
                onAccountLongClick(acc)
                true
            }
        }
    }

    class VH(val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root)
}

// ==================== 5. UZAK BAĞLANTILAR LİSTE ADAPTÖRÜ (01:28) ====================
class RemoteAccountsAdapter(
    private val onClick: (RemoteAccount) -> Unit,
    private val onLongClick: (RemoteAccount) -> Unit
) : RecyclerView.Adapter<RemoteAccountsAdapter.VH>() {

    private val list = mutableListOf<RemoteAccount>()

    fun submitList(newList: List<RemoteAccount>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val acc = list[position]
        val ctx = holder.itemView.context
        with(holder.binding) {
            tvSectionHeader.visibility = View.GONE
            cbSelect.visibility = View.GONE
            ivFileIcon.setImageResource(R.drawable.ic_folder_modern)
            tvIconBadge.visibility = View.VISIBLE
            tvIconBadge.text = acc.protocol.badge

            tvFileName.text = acc.host
            tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
            tvFileSubInfo.text = acc.displayName.ifBlank { acc.username.ifBlank { "pc" } }
            tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
            tvFileDate.text = ":${acc.port}"
            rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

            fileRowRoot.setOnClickListener { onClick(acc) }
            fileRowRoot.setOnLongClickListener {
                onLongClick(acc)
                true
            }
        }
    }

    class VH(val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root)
}
