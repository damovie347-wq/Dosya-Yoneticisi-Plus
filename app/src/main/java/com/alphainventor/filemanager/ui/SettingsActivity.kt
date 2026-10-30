package com.alphainventor.filemanager.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alphainventor.filemanager.databinding.ActivitySettingsBinding
import com.alphainventor.filemanager.theme.AppThemeMode
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettingsBack.setOnClickListener { finish() }
        rebuildSettingsView()
    }

    private fun rebuildSettingsView() {
        ThemeManager.applyWindowColors(this, binding.settingsRoot, binding.settingsToolbar)
        val container = binding.llSettingsContainer
        container.removeAllViews()

        // 1. GÖRÜNÜM VE TEMA (Kullanıcının istediği AMOLED / Koyu / Açık Tema Seçimi)
        addSectionHeader(container, "Görünüm ve Tema (AMOLED / Koyu / Açık)")
        addClickablePreference(
            container,
            title = "Tema Seçimi",
            subtitle = ThemeManager.getThemeMode(this).displayName
        ) {
            val modes = AppThemeMode.values()
            val labels = modes.map { it.displayName }.toTypedArray()
            val currentIdx = modes.indexOf(ThemeManager.getThemeMode(this)).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Uygulama Temasını Seçin")
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    ThemeManager.setThemeMode(this, modes[which])
                    rebuildSettingsView()
                    dialog.dismiss()
                }
                .setNegativeButton("İptal", null)
                .show()
        }

        // 2. VARSAYILAN UYGULAMALAR (01:48)
        addSectionHeader(container, "Varsayılan uygulamalar")
        addClickablePreference(container, "Varsayılan uygulamalar", "Dosya türleri için varsayılan açıcıları yönetin") {}

        // 3. DAHİLİ UYGULAMALAR (01:48 - 01:49)
        addSectionHeader(container, "Dahili uygulamalar")
        addCheckboxPreference(container, "Resim Görüntüleyicisi", AppSettings.useInternalImageViewer) {
            AppSettings.useInternalImageViewer = it
        }
        addCheckboxPreference(container, "Video oynatıcı", AppSettings.useInternalVideoPlayer) {
            AppSettings.useInternalVideoPlayer = it
        }
        addCheckboxPreference(container, "Müzik Çalar", AppSettings.useInternalMusicPlayer) {
            AppSettings.useInternalMusicPlayer = it
        }
        addCheckboxPreference(container, "Metin düzenleyicisi", AppSettings.useInternalTextEditor) {
            AppSettings.useInternalTextEditor = it
        }

        // 4. BİLDİRİM AYARLARI (01:49)
        addSectionHeader(container, "Bildirim ayarları")
        addClickablePreference(
            container,
            "Bellek dolu",
            "Depolama alanı ${AppSettings.storageFullThreshold}% dolu olduğunda göster"
        ) {}

        // 5. GERİ DÖNÜŞÜM KUTUSU AYARLARI (01:50)
        addSectionHeader(container, "Geri Dönüşüm Kutusu Ayarları")
        addCheckboxPreference(container, "Varsayılan olarak geri dönüşüm kutusu kullan", AppSettings.useRecycleBin) {
            AppSettings.useRecycleBin = it
        }
        addCheckboxPreference(container, "Geri Dönüşüm onayını göster", AppSettings.recycleBinConfirmation) {
            AppSettings.recycleBinConfirmation = it
        }

        // 6. GELİŞMİŞ AYARLAR (01:50 - 01:51)
        addSectionHeader(container, "Gelişmiş Ayarlar")
        addCheckboxPreference(container, "Nav icon simgesini (☰) her zaman göster", AppSettings.showDrawerAlways) {
            AppSettings.showDrawerAlways = it
        }
        addCheckboxPreference(container, "USB bağlantısını algıla", AppSettings.detectUsbAttached) {
            AppSettings.detectUsbAttached = it
        }
        addCheckboxPreference(container, "Geçmiş göster", AppSettings.showHistory) {
            AppSettings.showHistory = it
        }
        addCheckboxPreference(container, "Sistem depolamasını göster", AppSettings.showSystemStorage) {
            AppSettings.showSystemStorage = it
        }
        addClickablePreference(
            container,
            "Dosya boyutu birimi",
            if (AppSettings.fileSizeUnitBase10) "GB (1000 tabanlı)" else "GiB (1024 tabanlı)"
        ) {
            AppSettings.fileSizeUnitBase10 = !AppSettings.fileSizeUnitBase10
            rebuildSettingsView()
        }

        // 7. GELİŞMİŞ MENÜLERİ GÖSTER (01:51 - 01:53)
        addSectionHeader(container, "Gelişmiş menüleri göster")
        addCheckboxPreference(container, "Yer İmi Ekle", AppSettings.showAddToFavorite) {
            AppSettings.showAddToFavorite = it
        }
        addCheckboxPreference(container, "Ana Ekrana ekle", AppSettings.showAddToHomeScreen) {
            AppSettings.showAddToHomeScreen = it
        }
        addCheckboxPreference(container, "Gizle/Gizleme", AppSettings.showHideUnhide) {
            AppSettings.showHideUnhide = it
        }
        addCheckboxPreference(container, "Farklı aç", AppSettings.showOpenAs) {
            AppSettings.showOpenAs = it
        }

        // 8. HAKKINDA (01:52)
        addSectionHeader(container, "Hakkında")
        addClickablePreference(
            container,
            "Hakkında",
            "Sürüm 3.6.7 (Ultra-Hafif, Reklamsız & Çoklu Bulut Arama Özel Sürümü)"
        ) {}
    }

    private fun addSectionHeader(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#26A69A"))
            setPadding(42, 36, 42, 12)
        }
        parent.addView(tv)
    }

    private fun addClickablePreference(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 24, 42, 24)
            setOnClickListener { onClick() }
        }
        val tvTitle = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ThemeManager.getPrimaryTextColor(this@SettingsActivity))
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ThemeManager.getSecondaryTextColor(this@SettingsActivity))
        }
        box.addView(tvTitle)
        box.addView(tvSub)
        parent.addView(box)
        parent.addView(createDivider())
    }

    private fun addCheckboxPreference(
        parent: LinearLayout,
        title: String,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(42, 20, 32, 20)
        }
        val tv = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ThemeManager.getPrimaryTextColor(this@SettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cb = CheckBox(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.setOnClickListener { cb.isChecked = !cb.isChecked }
        row.addView(tv)
        row.addView(cb)
        parent.addView(row)
        parent.addView(createDivider())
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ThemeManager.getDividerColor(this@SettingsActivity))
        }
    }
}
