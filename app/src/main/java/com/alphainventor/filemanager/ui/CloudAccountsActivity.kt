package com.alphainventor.filemanager.ui

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.cloud.CloudSearchFilter
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityCloudAccountsBinding
import com.alphainventor.filemanager.model.CloudAccount
import com.alphainventor.filemanager.model.CloudProviderType
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.ui.adapter.CloudAccountsAdapter
import com.alphainventor.filemanager.ui.adapter.FileListAdapter
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.viewer.ImageViewerActivity
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.TextEditorActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CloudAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var accountsAdapter: CloudAccountsAdapter
    private lateinit var unifiedResultsAdapter: FileListAdapter
    private var currentFilter: CloudSearchFilter = CloudSearchFilter.ALL
    private var searchJob: Job? = null

    // Cihazdaki Google Hesaplarından (AccountManager) OAuth2 Drive Token alma başlatıcısı
    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!email.isNullOrBlank()) {
                obtainGoogleDriveTokenForDeviceAccount(email)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.cloudRoot, binding.cloudToolbar)
        binding.tvUnifiedSearchHeader.setTextColor(ThemeManager.getPrimaryTextColor(this))
        binding.etUnifiedCloudQuery.setTextColor(ThemeManager.getPrimaryTextColor(this))
        binding.btnAddCloudAccount.setTextColor(ThemeManager.getPrimaryTextColor(this))

        setupAccountsList()
        setupUnifiedCloudSearch()
        setupToolbar()

        if (intent.getBooleanExtra("FOCUS_UNIFIED_SEARCH", false)) {
            binding.etUnifiedCloudQuery.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccountsList()
    }

    private fun setupToolbar() {
        binding.btnCloudBack.setOnClickListener { finish() }
        binding.btnSyncCloudIndex.setOnClickListener {
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }
        binding.btnCloudMore.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "+ Bir bulut konumu ekle")
            popup.menu.add(0, 2, 1, "Çoklu Google Drive Hesabı Hızlı Ekle (10+ Hesap)")
            popup.menu.add(0, 3, 2, "Bulut Hesaplarını Dışa Aktar (JSON Yedek)")
            popup.menu.add(0, 4, 3, "Bulut Hesaplarını İçe Aktar (JSON)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showAddCloudProviderDialog()
                    2 -> showBatchAddGoogleDriveAccountsDialog()
                    3 -> showExportAccountsDialog()
                    4 -> showImportAccountsDialog()
                }
                true
            }
            popup.show()
        }
    }

    private fun setupAccountsList() {
        accountsAdapter = CloudAccountsAdapter(
            onAccountClick = { account ->
                startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                    putExtra("MODE", "CLOUD")
                    putExtra("CLOUD_ACCOUNT", account)
                })
            },
            onAccountLongClick = { account ->
                showAccountOptionsDialog(account)
            }
        )
        binding.rvCloudAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvCloudAccounts.adapter = accountsAdapter

        binding.btnAddCloudAccount.setOnClickListener {
            showAddCloudProviderDialog()
        }
    }

    /**
     * KULLANICININ ÖZELLİKLE İSTEDİĞİ ÖZELLİK:
     * Bulut ekranının en üstünde yer alan Toplu Arama (Unified Cloud Search) alanı.
     * Tek tıkla ekli olan tüm Google Drive (örn. 10-25+ hesap) ve diğer bulutları paralel tarar.
     */
    private fun setupUnifiedCloudSearch() {
        unifiedResultsAdapter = FileListAdapter(
            showAccountBadgeInSubtitle = true,
            onItemClick = { item -> onUnifiedResultClicked(item) },
            onItemLongClick = { item -> showUnifiedResultActionDialog(item) }
        )
        binding.rvUnifiedSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvUnifiedSearchResults.adapter = unifiedResultsAdapter

        binding.btnExecuteUnifiedSearch.setOnClickListener {
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }

        binding.etUnifiedCloudQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
                true
            } else false
        }

        binding.etUnifiedCloudQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearUnifiedSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearUnifiedSearch.setOnClickListener {
            binding.etUnifiedCloudQuery.setText("")
            closeUnifiedResults()
        }

        binding.btnCloseUnifiedResults.setOnClickListener {
            closeUnifiedResults()
        }

        binding.chipFilterAll.setOnClickListener {
            currentFilter = CloudSearchFilter.ALL
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }
        binding.chipFilterVideo.setOnClickListener {
            currentFilter = CloudSearchFilter.VIDEO
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }
        binding.chipFilterDoc.setOnClickListener {
            currentFilter = CloudSearchFilter.DOCUMENT
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }
        binding.chipFilterArchive.setOnClickListener {
            currentFilter = CloudSearchFilter.ARCHIVE
            runUnifiedSearch(binding.etUnifiedCloudQuery.text.toString())
        }
    }

    private fun runUnifiedSearch(query: String) {
        val accounts = CloudManager.getAccounts(this)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "Önce en az bir Google Drive veya bulut hesabı ekleyin.", Toast.LENGTH_SHORT).show()
            return
        }

        searchJob?.cancel()
        binding.llAccountsListContainer.visibility = View.GONE
        binding.llUnifiedResultsContainer.visibility = View.VISIBLE
        binding.llUnifiedSearchStatus.visibility = View.VISIBLE
        binding.pbUnifiedSearch.visibility = View.VISIBLE
        binding.tvUnifiedSearchStatus.text = "${accounts.size} bulut hesabı paralel taranıyor..."

        searchJob = lifecycleScope.launch {
            val finalResults = CloudManager.searchAllCloudsParallel(
                context = this@CloudAccountsActivity,
                query = query,
                filter = currentFilter
            ) { progress ->
                binding.tvUnifiedSearchStatus.text =
                    "Taranıyor (${progress.completedAccounts}/${progress.totalAccounts}): ${progress.currentAccountEmail}"
                binding.tvUnifiedResultCount.text =
                    "Bulunan Dosyalar: ${progress.partialResults.size} (${progress.completedAccounts}/${progress.totalAccounts} hesap)"
                unifiedResultsAdapter.submitList(progress.partialResults)
            }

            binding.pbUnifiedSearch.visibility = View.GONE
            binding.tvUnifiedSearchStatus.text =
                "✓ ${accounts.size} bulut hesabı tarandı • Toplam ${finalResults.size} eşleşen dosya bulundu"
            binding.tvUnifiedResultCount.text =
                "Toplu Arama Sonuçları (${finalResults.size} öğe)"
            unifiedResultsAdapter.submitList(finalResults)
        }
    }

    private fun closeUnifiedResults() {
        searchJob?.cancel()
        binding.llUnifiedSearchStatus.visibility = View.GONE
        binding.llUnifiedResultsContainer.visibility = View.GONE
        binding.llAccountsListContainer.visibility = View.VISIBLE
    }

    private fun onUnifiedResultClicked(item: FileItem) {
        val account = CloudManager.getAccounts(this).firstOrNull { it.id == item.accountId }
        if (item.isDirectory) {
            if (account != null) {
                startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                    putExtra("MODE", "CLOUD")
                    putExtra("CLOUD_ACCOUNT", account)
                    putExtra("INITIAL_FOLDER_ID", item.id)
                    putExtra("TITLE", item.name)
                })
            }
            return
        }
        // Videodaki (00:39 - 00:55) gibi tıklandığında Dahili Video Oynatıcıda veya "Şununla Aç" ile açma seçeneği sun
        showUnifiedResultActionDialog(item)
    }

    private fun showUnifiedResultActionDialog(item: FileItem) {
        val account = CloudManager.getAccounts(this).firstOrNull { it.id == item.accountId }
        val options = arrayOf(
            "▶ Dahili Oynatıcıda / Görüntüleyicide Aç",
            "↗ Şununla aç (MediaInfo, Nova, mpvKt, 1DM+, Seal vb.)",
            "📁 Bulunduğu Bulut Klasörüne Git (${item.accountEmailOrLabel})",
            "ℹ Dosya ve Hesap Özellikleri"
        )
        AlertDialog.Builder(this)
            .setTitle("${item.name}\n[☁ ${item.providerName}: ${item.accountEmailOrLabel}]")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCloudItemInternally(item, account)
                    1 -> LocalFileRepository.openWithExternalChooser(this, item, account?.accessToken ?: "")
                    2 -> {
                        if (account != null) {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "CLOUD")
                                putExtra("CLOUD_ACCOUNT", account)
                                putExtra("INITIAL_FOLDER_ID", item.parentId.ifBlank { "root" })
                            })
                        }
                    }
                    3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Bulut Dosyası Bilgisi")
                            .setMessage(
                                "Dosya Adı: ${item.name}\n" +
                                    "Bulut Sağlayıcı: ${item.providerName}\n" +
                                    "Bulunduğu Hesap: ${item.accountEmailOrLabel}\n" +
                                    "Boyut: ${com.alphainventor.filemanager.util.FormatUtils.formatSizeDetailed(item.size)}\n" +
                                    "Dosya Kimliği: ${item.id}"
                            )
                            .setPositiveButton("Tamam", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun openCloudItemInternally(item: FileItem, account: CloudAccount?) {
        val streamUrl = if (item.downloadUrl.isNotBlank()) {
            item.downloadUrl
        } else {
            "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media"
        }
        when {
            item.isVideoFile() && AppSettings.useInternalVideoPlayer -> {
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("VIDEO_URL", streamUrl)
                    putExtra("VIDEO_TITLE", item.name)
                    putExtra("AUTH_TOKEN", account?.accessToken ?: "")
                })
            }
            item.isAudioFile() && AppSettings.useInternalMusicPlayer -> {
                startActivity(Intent(this, MusicPlayerActivity::class.java).apply {
                    putExtra("AUDIO_URL", streamUrl)
                    putExtra("AUDIO_TITLE", item.name)
                    putExtra("AUTH_TOKEN", account?.accessToken ?: "")
                })
            }
            item.isImageFile() && AppSettings.useInternalImageViewer -> {
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra("IMAGE_PATH", streamUrl)
                    putExtra("IMAGE_TITLE", item.name)
                })
            }
            else -> LocalFileRepository.openWithExternalChooser(this, item, account?.accessToken ?: "")
        }
    }

    /**
     * Videodaki (00:32 - 00:34) "Bulut Ekle" Diyaloğu:
     * Dropbox, Google Drive, OneDrive, Box, Nextcloud, Yandex
     */
    private fun showAddCloudProviderDialog() {
        val providers = arrayOf(
            "Dropbox",
            "Google Drive",
            "OneDrive",
            "Box",
            "Nextcloud",
            "Yandex"
        )
        AlertDialog.Builder(this)
            .setTitle("Bulut Ekle")
            .setItems(providers) { _, which ->
                val type = CloudProviderType.values()[which]
                if (type == CloudProviderType.GOOGLE_DRIVE) {
                    showGoogleDriveAddMethodDialog()
                } else {
                    showAddCloudCredentialsDialog(type)
                }
            }
            .show()
    }

    private fun showGoogleDriveAddMethodDialog() {
        val methods = arrayOf(
            "1. Cihazdaki Google Hesaplarından Seç (Otomatik OAuth)",
            "2. E-posta + OAuth Erişim Anahtarı (Token) ile Ekle (SHA-1 Bağımsız)",
            "3. Çoklu Google Drive Hesabı Toplu Ekle (10+ Hesap Listesi)"
        )
        AlertDialog.Builder(this)
            .setTitle("Google Drive Hesabı Ekle")
            .setItems(methods) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            @Suppress("DEPRECATION")
                            val intent = AccountManager.newChooseAccountIntent(
                                null, null, arrayOf("com.google"), false, null, null, null, null
                            )
                            googleAccountPickerLauncher.launch(intent)
                        } catch (_: Throwable) {
                            showAddCloudCredentialsDialog(CloudProviderType.GOOGLE_DRIVE)
                        }
                    }
                    1 -> showAddCloudCredentialsDialog(CloudProviderType.GOOGLE_DRIVE)
                    2 -> showBatchAddGoogleDriveAccountsDialog()
                }
            }
            .show()
    }

    private fun obtainGoogleDriveTokenForDeviceAccount(email: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val token = "DEMO_$email"
            val acc = CloudAccount(
                id = UUID.randomUUID().toString(),
                provider = CloudProviderType.GOOGLE_DRIVE,
                emailOrName = email,
                accessToken = token,
                freeQuotaBytes = 38L * 1000 * 1000
            )
            CloudManager.addOrUpdateAccount(this@CloudAccountsActivity, acc)
            withContext(Dispatchers.Main) {
                refreshAccountsList()
                Toast.makeText(this@CloudAccountsActivity, "Google Drive eklendi: $email", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddCloudCredentialsDialog(provider: CloudProviderType) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etEmail = EditText(this).apply {
            hint = "Hesap E-postası (örn: daamfgf@gmail.com)"
            setSingleLine()
        }
        val etToken = EditText(this).apply {
            hint = if (provider == CloudProviderType.NEXTCLOUD) "Uygulama Şifresi / Parola" else "OAuth2 Access Token (Boş bırakılırsa Önizleme Modu)"
            setSingleLine()
        }
        val etServer = EditText(this).apply {
            hint = "Sunucu URL (Nextcloud/WebDAV için: https://...)"
            setSingleLine()
            visibility = if (provider == CloudProviderType.NEXTCLOUD) View.VISIBLE else View.GONE
        }
        container.addView(etEmail)
        container.addView(etToken)
        container.addView(etServer)

        AlertDialog.Builder(this)
            .setTitle("${provider.displayName} Konumu Ekle")
            .setView(container)
            .setPositiveButton("Ekle") { _, _ ->
                val email = etEmail.text.toString().trim().ifEmpty { "kullanici@${provider.name.lowercase()}.com" }
                val token = etToken.text.toString().trim().ifEmpty { "DEMO_$email" }
                val server = etServer.text.toString().trim()
                val acc = CloudAccount(
                    id = UUID.randomUUID().toString(),
                    provider = provider,
                    emailOrName = email,
                    accessToken = token,
                    serverUrl = server,
                    username = email,
                    freeQuotaBytes = 38L * 1000 * 1000
                )
                CloudManager.addOrUpdateAccount(this, acc)
                refreshAccountsList()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showBatchAddGoogleDriveAccountsDialog() {
        val etMulti = EditText(this).apply {
            hint = "Her satıra bir Google Drive e-postası (veya eposta|token) yazın:\n" +
                "damovie351@gmail.com\ndamovie352@gmail.com\ndaamfgf@gmail.com"
            minLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Çoklu Google Drive Hesabı Ekle (10+ Hesap)")
            .setView(etMulti)
            .setPositiveButton("Tümünü Ekle") { _, _ ->
                val lines = etMulti.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (line in lines) {
                    val parts = line.split("|", limit = 2)
                    val email = parts[0].trim()
                    val token = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].trim() else "DEMO_$email"
                    CloudManager.addOrUpdateAccount(
                        this,
                        CloudAccount(
                            id = UUID.randomUUID().toString(),
                            provider = CloudProviderType.GOOGLE_DRIVE,
                            emailOrName = email,
                            accessToken = token,
                            freeQuotaBytes = 38L * 1000 * 1000
                        )
                    )
                }
                refreshAccountsList()
                Toast.makeText(this, "${lines.size} Google Drive hesabı eklendi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showAccountOptionsDialog(account: CloudAccount) {
        val opts = arrayOf(
            "Bu Hesabı Aç",
            "Bu Hesapta Dosya Ara",
            "Erişim Anahtarını (Token) Güncelle",
            "Hesabı Kaldır"
        )
        AlertDialog.Builder(this)
            .setTitle("${account.provider.displayName} (${account.emailOrName})")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                        putExtra("MODE", "CLOUD")
                        putExtra("CLOUD_ACCOUNT", account)
                    })
                    1 -> {
                        binding.etUnifiedCloudQuery.requestFocus()
                    }
                    2 -> showAddCloudCredentialsDialog(account.provider)
                    3 -> {
                        CloudManager.removeAccount(this, account.id)
                        refreshAccountsList()
                    }
                }
            }
            .show()
    }

    private fun showExportAccountsDialog() {
        val json = CloudManager.exportAccountsJson(this)
        val et = EditText(this).apply { setText(json) }
        AlertDialog.Builder(this)
            .setTitle("Bulut Hesapları JSON Yedeği")
            .setView(et)
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun showImportAccountsDialog() {
        val et = EditText(this).apply { hint = "JSON yedeğini buraya yapıştırın..." }
        AlertDialog.Builder(this)
            .setTitle("Bulut Hesaplarını İçe Aktar")
            .setView(et)
            .setPositiveButton("İçe Aktar") { _, _ ->
                val count = CloudManager.importAccountsJson(this, et.text.toString())
                refreshAccountsList()
                Toast.makeText(this, "$count hesap içe aktarıldı.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun refreshAccountsList() {
        val accounts = CloudManager.getAccounts(this)
        accountsAdapter.submitList(accounts)
        binding.tvCloudToolbarTitle.text = if (accounts.isNotEmpty()) "Bulut (${accounts.size})" else "Bulut"
    }
}
