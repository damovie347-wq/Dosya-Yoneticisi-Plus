package com.alphainventor.filemanager.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.core.InstalledAppItem
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityAppsManagerBinding
import com.alphainventor.filemanager.databinding.ItemFileRowBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Uygulamalar Ekranı (Video 03:03 - 03:12):
 * 1) Satırlarda sağda onay kutusu (checkbox) YOKTUR; yalnızca sürüm numarası (59.0.2, 1.97.59, 11.2.3) görünür.
 * 2) Bir uygulamaya tek tıklandığında Orijinal Uygulama Detay Penceresi (Video 03:07 - 03:09) açılır:
 *    - Başlık: [Uygulama İkonu] Uygulama Adı
 *    - 4 Bilgi Satırı: Boyut | Değiştirilmiş | Sürüm | Uygulama bilgileri (paket adı)
 *    - 3 Çerçeveli Kare Buton: [ Aç ]  |  [ Bilgi ]  |  [ Mağaza ]
 *    - Sağ alt: TAMAM
 * 3) Uzun basıldığında (Video 03:04 - 03:06): Satır turkuaz (#66008578) vurgulanır, üst bar "✕ 1 / 65" olur
 *    ve altta 5'li işlem çubuğu ([Yedekle] [Kaldır] [Paylaş] [Aç] [Mağaza]) açılır!
 */
class AppsManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsManagerBinding
    private val appsAdapter = AppsAdapter()
    private var showSystemApps = false
    private var allApps: List<InstalledAppItem> = emptyList()
    private var sortByMode = 1 // 0: Ad, 1: Boyut, 2: Tarih
    private var sortAsc = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.appsRoot, binding.appsToolbar)

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.setHasFixedSize(true)
        binding.rvApps.setItemViewCacheSize(28)
        binding.rvApps.adapter = appsAdapter

        binding.btnAppsBack.setOnClickListener {
            if (appsAdapter.selectedPackages.isNotEmpty()) {
                appsAdapter.clearSelection()
                updateSelectionUi()
            } else if (binding.etAppsSearch.visibility == View.VISIBLE) {
                closeSearchBox()
            } else {
                finish()
            }
        }

        binding.btnAppsSearch.setOnClickListener {
            if (binding.etAppsSearch.visibility == View.VISIBLE) {
                closeSearchBox()
            } else {
                binding.tvAppsTitle.visibility = View.GONE
                binding.etAppsSearch.visibility = View.VISIBLE
                binding.etAppsSearch.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.etAppsSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        binding.btnAppsSort.setOnClickListener {
            showAppsSortDialog()
        }

        binding.tabDownloadedApps.setOnClickListener {
            showSystemApps = false
            appsAdapter.clearSelection()
            updateSelectionUi()
            binding.tabDownloadedApps.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
            binding.tabAllApps.setTextColor(ThemeManager.getSecondaryTextColor(this))
            loadApps()
        }
        binding.tabAllApps.setOnClickListener {
            showSystemApps = true
            appsAdapter.clearSelection()
            updateSelectionUi()
            binding.tabAllApps.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
            binding.tabDownloadedApps.setTextColor(ThemeManager.getSecondaryTextColor(this))
            loadApps()
        }

        binding.etAppsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndSubmitApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setupBottomSelectionActions()
        loadApps()
    }

    private fun closeSearchBox() {
        binding.etAppsSearch.setText("")
        binding.etAppsSearch.visibility = View.GONE
        binding.tvAppsTitle.visibility = View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etAppsSearch.windowToken, 0)
        filterAndSubmitApps()
    }

    private fun showAppsSortDialog() {
        val options = arrayOf("Ad ▲", "Ad ▼", "Boyut ▲", "Boyut ▼", "Tarih ▲", "Tarih ▼")
        val currentIdx = when (sortByMode) {
            0 -> if (sortAsc) 0 else 1
            1 -> if (sortAsc) 2 else 3
            else -> if (sortAsc) 4 else 5
        }
        AlertDialog.Builder(this)
            .setTitle("Sıralama ölçütü")
            .setSingleChoiceItems(options, currentIdx) { d, which ->
                when (which) {
                    0 -> { sortByMode = 0; sortAsc = true }
                    1 -> { sortByMode = 0; sortAsc = false }
                    2 -> { sortByMode = 1; sortAsc = true }
                    3 -> { sortByMode = 1; sortAsc = false }
                    4 -> { sortByMode = 2; sortAsc = true }
                    5 -> { sortByMode = 2; sortAsc = false }
                }
                d.dismiss()
                filterAndSubmitApps()
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun setupBottomSelectionActions() {
        binding.btnAppBottomBackup.setOnClickListener {
            val selected = appsAdapter.getSelectedApps()
            if (selected.isEmpty()) return@setOnClickListener
            backupAppsToDownloads(selected)
            appsAdapter.clearSelection()
            updateSelectionUi()
        }

        binding.btnAppBottomUninstall.setOnClickListener {
            val selected = appsAdapter.getSelectedApps()
            for (app in selected) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            }
            appsAdapter.clearSelection()
            updateSelectionUi()
        }

        binding.btnAppBottomShare.setOnClickListener {
            val first = appsAdapter.getSelectedApps().firstOrNull() ?: return@setOnClickListener
            val fi = FileItem(
                id = first.apkPath,
                name = "${first.appName}.apk",
                path = first.apkPath,
                isDirectory = false,
                size = first.apkSizeBytes,
                lastModified = first.lastUpdateTime,
                mimeType = "application/vnd.android.package-archive",
                sourceType = FileSourceType.LOCAL
            )
            LocalFileRepository.shareFile(this, fi)
            appsAdapter.clearSelection()
            updateSelectionUi()
        }

        binding.btnAppBottomOpen.setOnClickListener {
            val first = appsAdapter.getSelectedApps().firstOrNull() ?: return@setOnClickListener
            launchAppPackage(first.packageName)
            appsAdapter.clearSelection()
            updateSelectionUi()
        }

        binding.btnAppBottomStore.setOnClickListener {
            val first = appsAdapter.getSelectedApps().firstOrNull() ?: return@setOnClickListener
            openPlayStoreForPackage(first.packageName)
            appsAdapter.clearSelection()
            updateSelectionUi()
        }
    }

    private fun updateSelectionUi() {
        val count = appsAdapter.selectedPackages.size
        if (count > 0) {
            binding.appsToolbar.setBackgroundColor(Color.parseColor("#00796B"))
            binding.btnAppsBack.setImageResource(R.drawable.ic_close_white)
            binding.tvAppsTitle.visibility = View.VISIBLE
            binding.etAppsSearch.visibility = View.GONE
            binding.tvAppsTitle.text = "$count / ${allApps.size}"
            binding.llAppsBottomActionBar.visibility = View.VISIBLE
        } else {
            binding.appsToolbar.setBackgroundColor(ThemeManager.getToolbarColor(this))
            binding.btnAppsBack.setImageResource(R.drawable.ic_back_white)
            binding.tvAppsTitle.text = getString(R.string.apps)
            binding.llAppsBottomActionBar.visibility = View.GONE
        }
    }

    private fun loadApps() {
        binding.pbAppsLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            allApps = LocalFileRepository.getInstalledApps(this@AppsManagerActivity, showSystemApps)
            filterAndSubmitApps()
            binding.pbAppsLoading.visibility = View.GONE
        }
    }

    private fun filterAndSubmitApps() {
        val q = binding.etAppsSearch.text?.toString()?.trim().orEmpty()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
        val sorted = when (sortByMode) {
            0 -> if (sortAsc) filtered.sortedBy { it.appName.lowercase(Locale("tr", "TR")) }
                 else filtered.sortedByDescending { it.appName.lowercase(Locale("tr", "TR")) }
            2 -> if (sortAsc) filtered.sortedBy { it.lastUpdateTime }
                 else filtered.sortedByDescending { it.lastUpdateTime }
            else -> if (sortAsc) filtered.sortedBy { it.apkSizeBytes }
                    else filtered.sortedByDescending { it.apkSizeBytes }
        }
        appsAdapter.submitList(sorted)
    }

    private fun launchAppPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Bu uygulama doğrudan başlatılamıyor.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayStoreForPackage(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun backupAppsToDownloads(apps: List<InstalledAppItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "backups")
            destDir.mkdirs()
            var okCount = 0
            for (app in apps) {
                try {
                    val src = File(app.apkPath)
                    if (src.exists()) {
                        val destFile = File(destDir, "${app.appName}_${app.versionName}.apk")
                        src.copyTo(destFile, overwrite = true)
                        okCount++
                    }
                } catch (_: Throwable) {
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@AppsManagerActivity,
                    if (okCount > 0) "✓ $okCount APK yedeklendi (/Download/backups)" else "APK yedeklenemedi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Video 03:07 - 03:09'daki Orijinal Uygulama Detay Diyaloğu:
     * - Üstte: [Uygulama İkonu] Uygulama Adı
     * - 4 Bilgi Satırı: Boyut, Değiştirilmiş, Sürüm, Uygulama bilgileri (paket adı)
     * - Altta 3 Çerçeveli Kare Buton: [ Aç ]  |  [ Bilgi ]  |  [ Mağaza ]
     */
    private fun showOriginalAppDetailsDialog(app: InstalledAppItem) {
        val density = resources.displayMetrics.density
        val cardColor = ThemeManager.getCardColor(this)
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val dividerColor = ThemeManager.getDividerColor(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pH = (22 * density).toInt()
            val pV = (18 * density).toInt()
            setPadding(pH, pV, pH, (6 * density).toInt())
            setBackgroundColor(cardColor)
        }

        // Başlık Satırı (Uygulama İkonu + Uygulama Adı)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val ivAppIcon = ImageView(this).apply {
            val sz = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (14 * density).toInt()
            }
        }
        ThumbnailCacheManager.loadAppIconAsync(this, app.packageName, ivAppIcon, R.drawable.ic_cat_apps)

        val tvAppName = TextView(this).apply {
            text = app.appName
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
        }

        headerRow.addView(ivAppIcon)
        headerRow.addView(tvAppName)
        root.addView(headerRow)

        fun addInfoRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
            }
            val tvLbl = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((125 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                text = label
                textSize = 13.5f
                setTextColor(secondaryColor)
            }
            val tvVal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = value
                textSize = 13.5f
                setTextColor(primaryColor)
            }
            row.addView(tvLbl)
            row.addView(tvVal)
            root.addView(row)
        }

        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).format(Date(app.lastUpdateTime))
        addInfoRow("Boyut", FormatUtils.formatSizeDetailed(app.apkSizeBytes))
        addInfoRow("Değiştirilmiş", dateStr)
        addInfoRow("Sürüm", app.versionName.ifBlank { "1.0" })
        addInfoRow("Uygulama bilgileri", app.packageName)

        // 3 Çerçeveli Kare Buton: [ Aç ] | [ Bilgi ] | [ Mağaza ]
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * density).toInt()
            }
        }

        var dialogRef: AlertDialog? = null

        fun createSquareActionBox(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, (68 * density).toInt(), 1f).apply {
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    setStroke((1 * density).toInt().coerceAtLeast(1), dividerColor)
                    cornerRadius = 3f * density
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialogRef?.dismiss()
                    onClick()
                }

                val iv = ImageView(this@AppsManagerActivity).apply {
                    val sz = (22 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        bottomMargin = (6 * density).toInt()
                    }
                    setImageResource(iconRes)
                    setColorFilter(primaryColor)
                }
                val tv = TextView(this@AppsManagerActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(primaryColor)
                }
                addView(iv)
                addView(tv)
            }
        }

        buttonsRow.addView(createSquareActionBox(R.drawable.ic_vp_play, "Aç") {
            launchAppPackage(app.packageName)
        })
        buttonsRow.addView(createSquareActionBox(R.drawable.ic_menu_info, "Bilgi") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        })
        buttonsRow.addView(createSquareActionBox(R.drawable.ic_star_bookmark, "Mağaza") {
            openPlayStoreForPackage(app.packageName)
        })

        root.addView(buttonsRow)

        dialogRef = AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("TAMAM", null)
            .create()
        dialogRef.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (appsAdapter.selectedPackages.isNotEmpty()) {
            appsAdapter.clearSelection()
            updateSelectionUi()
            return
        }
        if (binding.etAppsSearch.visibility == View.VISIBLE) {
            closeSearchBox()
            return
        }
        super.onBackPressed()
    }

    inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {
        private val items = ArrayList<InstalledAppItem>()
        val selectedPackages = HashSet<String>()

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        fun toggleSelection(pkg: String) {
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg)
            else selectedPackages.add(pkg)
            notifyDataSetChanged()
            updateSelectionUi()
        }

        fun clearSelection() {
            selectedPackages.clear()
            notifyDataSetChanged()
        }

        fun getSelectedApps(): List<InstalledAppItem> = items.filter { selectedPackages.contains(it.packageName) }

        fun submitList(newList: List<InstalledAppItem>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos].packageName == newList[newPos].packageName
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos] == newList[newPos]
            })
            items.clear()
            items.addAll(newList)
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val isSelected = selectedPackages.contains(item.packageName)
            with(holder.binding) {
                tvSectionHeader.visibility = View.GONE
                tvIconBadge.visibility = View.GONE
                ivPlayOverlay.visibility = View.GONE
                // Orijinal APK (Video 03:03): Sağda checkbox YOKTUR, yalnızca sürüm yazısı görünür!
                cbSelect.visibility = View.GONE

                fileRowRoot.setBackgroundColor(
                    if (isSelected) Color.parseColor("#66008578") else Color.TRANSPARENT
                )

                tvFileName.text = item.appName
                tvFileName.setTextColor(ThemeManager.getPrimaryTextColor(ctx))
                tvFileSubInfo.text = FormatUtils.formatSizeDetailed(item.apkSizeBytes)
                tvFileSubInfo.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
                tvFileDate.text = item.versionName
                tvFileDate.setTextColor(ThemeManager.getSecondaryTextColor(ctx))
                rowDivider.setBackgroundColor(ThemeManager.getDividerColor(ctx))

                ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                ThumbnailCacheManager.loadAppIconAsync(ctx, item.packageName, ivFileIcon, R.drawable.ic_cat_apps)

                fileRowRoot.setOnClickListener {
                    if (selectedPackages.isNotEmpty()) {
                        toggleSelection(item.packageName)
                    } else {
                        showOriginalAppDetailsDialog(item)
                    }
                }
                fileRowRoot.setOnLongClickListener {
                    toggleSelection(item.packageName)
                    true
                }
            }
        }

        inner class VH(val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
