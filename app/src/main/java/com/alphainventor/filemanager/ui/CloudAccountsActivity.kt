package com.alphainventor.filemanager.ui

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bulut Ekranı (00:07 - 01:05) +
 * Kullanıcının videoda (00:54 - 01:01) mavi çizimle istediği Üst Araç Çubuğundaki BÜYÜTEÇ (Toplu Bulut Arama) özelliği.
 */
class CloudAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var accountsAdapter: CloudAccountsAdapter
    private lateinit var unifiedResultsAdapter: FileListAdapter

    private var activeFilter: CloudSearchFilter = CloudSearchFilter.ALL
    private var unifiedSearchJob: Job? = null
    private var debounceJob: Job? = null
    private var pendingGoogleAccountForAuth: CloudAccount? = null

    /**
     * Android sistem hesap seçicisi ("Dosya Yöneticisi + için hesap seçin" - 00:28)
     */
    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!email.isNullOrBlank()) {
                authenticateAndSaveGoogleDriveAccount(email.trim())
            }
        }
    }

    /**
     * İlk kez Google Drive bağlantısı kurulurken Google OAuth izin ekranını (UserRecoverableAuthException) açar
     */
    private val googleAuthConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingGoogleAccountForAuth
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            lifecycleScope.launch {
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, pending, forceRefresh = true)
                if (token.isNotBlank()) {
                    val withQuota = CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, pending.copy(accessToken = token))
                    refreshAccountsList()
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "Google Drive bağlandı: ${withQuota.emailOrName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupAccountsList()
        setupUnifiedSearchEngine()
        applyCurrentTheme()

        if (intent.getBooleanExtra("FOCUS_UNIFIED_SEARCH", false)) {
            toggleTopSearchBar(forceOpen = true)
        }
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        refreshAccountsList()
    }

    private fun setupToolbar() {
        binding.btnCloudBack.setOnClickListener {
            if (binding.cardUnifiedCloudSearch.visibility == View.VISIBLE ||
                binding.llUnifiedSearchResultsSection.visibility == View.VISIBLE
            ) {
                closeUnifiedSearch()
            } else {
                finish()
            }
        }

        // Kullanıcının videoda (00:54 - 01:01) mavi kalemle işaretlediği BÜYÜTEÇ ikonu:
        // Tıklandığında üst arama kutusunu açar ve klavyeyi odaklayarak tüm Google Drive hesaplarında aramaya hazırlar
        binding.btnCloudToolbarSearch.setOnClickListener {
            val isVisible = binding.cardUnifiedCloudSearch.visibility == View.VISIBLE
            toggleTopSearchBar(forceOpen = !isVisible)
        }

        // Sağdaki Kalem (Düzenle) İkonu: Düzenleme modunu açar/kapatır veya çoklu hesap ekleme menüsü sunar
        binding.btnBatchAddDrive.setOnClickListener {
            accountsAdapter.isEditMode = !accountsAdapter.isEditMode
            val msg = if (accountsAdapter.isEditMode) {
                "Düzenleme modu: Hesaba dokunarak yeniden adlandırabilir veya kaldırabilirsiniz."
            } else {
                "Düzenleme modu kapatıldı."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnAddCloudLocation.setOnClickListener {
            showAddCloudProviderDialog()
        }
    }

    private fun toggleTopSearchBar(forceOpen: Boolean) {
        if (forceOpen) {
            binding.cardUnifiedCloudSearch.visibility = View.VISIBLE
            binding.etUnifiedCloudSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUnifiedCloudSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            closeUnifiedSearch()
        }
    }

    private fun closeUnifiedSearch() {
        debounceJob?.cancel()
        unifiedSearchJob?.cancel()
        binding.etUnifiedCloudSearch.setText("")
        binding.cardUnifiedCloudSearch.visibility = View.GONE
        binding.llUnifiedSearchStatus.visibility = View.GONE
        binding.llUnifiedSearchResultsSection.visibility = View.GONE
        binding.rvCloudAccounts.visibility = View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUnifiedCloudSearch.windowToken, 0)
    }

    private fun setupAccountsList() {
        accountsAdapter = CloudAccountsAdapter(
            accounts = emptyList(),
            onAccountClick = { account ->
                if (accountsAdapter.isEditMode) {
                    showAccountOptionsDialog(account)
                } else {
                    openCloudAccountExplorer(account)
                }
            },
            onAccountLongClick = { account ->
                showAccountOptionsDialog(account)
            }
        )
        binding.rvCloudAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvCloudAccounts.adapter = accountsAdapter
    }

    private fun openCloudAccountExplorer(account: CloudAccount) {
        // Eğer Google Drive hesabıysa ve henüz OAuth token alınmadıysa önce izin/token kontrolü yap
        if (account.provider == CloudProviderType.GOOGLE_DRIVE && account.accessToken.isBlank()) {
            lifecycleScope.launch {
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account, forceRefresh = false)
                val recovery = CloudManager.pendingRecoveryIntent
                if (recovery != null) {
                    pendingGoogleAccountForAuth = account
                    googleAuthConsentLauncher.launch(recovery)
                    return@launch
                }
                val updatedAccount = CloudManager.getAccounts(this@CloudAccountsActivity)
                    .firstOrNull { it.id == account.id } ?: account.copy(accessToken = token)
                launchExplorerForAccount(updatedAccount)
            }
        } else {
            launchExplorerForAccount(account)
        }
    }

    private fun launchExplorerForAccount(account: CloudAccount) {
        startActivity(Intent(this, FileExplorerActivity::class.java).apply {
            putExtra("MODE", "CLOUD")
            putExtra("CLOUD_ACCOUNT", account)
            putExtra("TITLE", account.provider.displayName)
        })
    }

    private fun setupUnifiedSearchEngine() {
        unifiedResultsAdapter = FileListAdapter(
            onItemClick = { item -> onUnifiedResultClicked(item) },
            onItemLongClick = { item -> showUnifiedResultActionDialog(item) },
            showAccountOwnerBadge = true
        )
        binding.rvUnifiedSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvUnifiedSearchResults.adapter = unifiedResultsAdapter

        binding.btnRunUnifiedSearch.setOnClickListener {
            executeUnifiedMultiCloudSearch()
        }

        binding.etUnifiedCloudSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeUnifiedMultiCloudSearch()
                true
            } else {
                false
            }
        }

        // Kullanıcı büyütece tıklayıp dosya adını yazdıkça otomatik olarak bütün hesapları taramaya başlar!
        binding.etUnifiedCloudSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                debounceJob?.cancel()
                if (q.isEmpty()) {
                    unifiedSearchJob?.cancel()
                    binding.llUnifiedSearchStatus.visibility = View.GONE
                    binding.llUnifiedSearchResultsSection.visibility = View.GONE
                    binding.rvCloudAccounts.visibility = View.VISIBLE
                } else {
                    debounceJob = lifecycleScope.launch {
                        delay(280L)
                        executeUnifiedMultiCloudSearch()
                    }
                }
            }
        })

        binding.btnClearUnifiedSearch.setOnClickListener {
            binding.etUnifiedCloudSearch.setText("")
        }

        binding.btnCloseSearchResults.setOnClickListener {
            closeUnifiedSearch()
        }

        binding.chipFilterAll.setOnClickListener { updateSearchFilter(CloudSearchFilter.ALL) }
        binding.chipFilterVideos.setOnClickListener { updateSearchFilter(CloudSearchFilter.VIDEOS) }
        binding.chipFilterDocs.setOnClickListener { updateSearchFilter(CloudSearchFilter.DOCUMENTS) }
        binding.chipFilterArchives.setOnClickListener { updateSearchFilter(CloudSearchFilter.ARCHIVES) }
    }

    private fun updateSearchFilter(newFilter: CloudSearchFilter) {
        activeFilter = newFilter
        val chips = mapOf(
            CloudSearchFilter.ALL to binding.chipFilterAll,
            CloudSearchFilter.VIDEOS to binding.chipFilterVideos,
            CloudSearchFilter.DOCUMENTS to binding.chipFilterDocs,
            CloudSearchFilter.ARCHIVES to binding.chipFilterArchives
        )
        chips.forEach { (flt, view) ->
            if (flt == activeFilter) {
                view.setBackgroundResource(R.drawable.bg_badge_pill)
                view.setTextColor(Color.WHITE)
            } else {
                view.setBackgroundResource(R.drawable.bg_search_box)
                view.setTextColor(Color.parseColor("#555555"))
            }
        }
        if (binding.etUnifiedCloudSearch.text?.isNotBlank() == true ||
            binding.llUnifiedSearchResultsSection.visibility == View.VISIBLE
        ) {
            executeUnifiedMultiCloudSearch()
        }
    }

    private fun executeUnifiedMultiCloudSearch() {
        val query = binding.etUnifiedCloudSearch.text?.toString()?.trim().orEmpty()
        val accounts = CloudManager.getAccounts(this)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "Önce '+ Bir bulut konumu ekle' ile Google Drive veya bulut hesabı ekleyin.", Toast.LENGTH_SHORT).show()
            return
        }

        unifiedSearchJob?.cancel()
        binding.llUnifiedSearchStatus.visibility = View.VISIBLE
        binding.pbUnifiedSearch.visibility = View.VISIBLE
        binding.llUnifiedSearchResultsSection.visibility = View.VISIBLE
        binding.rvCloudAccounts.visibility = View.GONE
        binding.tvUnifiedSearchStatus.text = "${accounts.size} bulut hesabı paralel taranıyor..."

        unifiedSearchJob = lifecycleScope.launch {
            val results = CloudManager.searchAcrossAllAccounts(
                context = this@CloudAccountsActivity,
                query = query,
                filter = activeFilter,
                onProgress = { progress ->
                    runOnUiThread {
                        binding.tvUnifiedSearchStatus.text =
                            "${progress.completedAccounts}/${progress.totalAccounts} hesap tarandı • ${progress.partialResults.size} eşleşme (${progress.currentAccountName})"
                        unifiedResultsAdapter.submitList(progress.partialResults)
                    }
                }
            )

            binding.pbUnifiedSearch.visibility = View.GONE
            binding.tvUnifiedSearchStatus.text =
                "✓ ${accounts.size} bulut hesabı tarandı • Toplam ${results.size} dosya/klasör bulundu"
            binding.tvSearchResultsHeader.text =
                if (query.isEmpty()) "Tüm Bulut Dosyaları (${results.size})"
                else "\"$query\" Arama Sonuçları (${results.size})"
            unifiedResultsAdapter.submitList(results)
        }
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
        } else {
            showUnifiedResultActionDialog(item)
        }
    }

    private fun showUnifiedResultActionDialog(item: FileItem) {
        val account = CloudManager.getAccounts(this).firstOrNull { it.id == item.accountId }
        val options = arrayOf(
            "▶ Dahili Oynatıcıda / Görüntüleyicide Aç",
            "↗ Şununla aç (Harici Uygulama Seç)",
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
        when {
            item.isVideo -> {
                if (AppSettings.useInternalVideoPlayer) {
                    startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URL", item.downloadUrl.ifBlank { item.path })
                        putExtra("VIDEO_TITLE", item.name)
                        putExtra("AUTH_TOKEN", account?.accessToken ?: "")
                    })
                } else {
                    LocalFileRepository.openWithExternalChooser(this, item, account?.accessToken ?: "")
                }
            }
            item.isAudio -> {
                startActivity(Intent(this, MusicPlayerActivity::class.java).apply {
                    putExtra("AUDIO_URL", item.downloadUrl.ifBlank { item.path })
                    putExtra("AUDIO_TITLE", item.name)
                    putExtra("AUTH_TOKEN", account?.accessToken ?: "")
                })
            }
            item.isImage -> {
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra("IMAGE_PATH", item.downloadUrl.ifBlank { item.path })
                    putExtra("IMAGE_TITLE", item.name)
                })
            }
            else -> {
                startActivity(Intent(this, TextEditorActivity::class.java).apply {
                    putExtra("FILE_PATH", item.path)
                    putExtra("FILE_TITLE", item.name)
                })
            }
        }
    }

    /**
     * Orijinal APK'daki (00:26) "Bulut Ekle" diyalogu:
     * İkonlu liste: Dropbox, Google Drive, OneDrive, Box, Nextcloud, Yandex
     */
    private fun showAddCloudProviderDialog() {
        val providers = CloudProviderType.values()
        val icons = intArrayOf(
            R.drawable.cloud_icon_dropbox,
            R.drawable.cloud_icon_googledrive,
            R.drawable.cloud_icon_onedrive,
            R.drawable.cloud_icon_box,
            R.drawable.ic_cloud_nextcloud,
            R.drawable.cloud_icon_yandex
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cloud_dialog_title))
            .setView(container)
            .create()

        providers.forEachIndexed { index, type ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true

                val iv = ImageView(this@CloudAccountsActivity).apply {
                    val sz = (32 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (16 * resources.displayMetrics.density).toInt()
                    }
                    setImageResource(icons[index])
                }
                val tv = TextView(this@CloudAccountsActivity).apply {
                    text = type.displayName
                    textSize = 16f
                    setTextColor(ThemeManager.getPrimaryTextColor(this@CloudAccountsActivity))
                }
                addView(iv)
                addView(tv)
                setOnClickListener {
                    dialog.dismiss()
                    if (type == CloudProviderType.GOOGLE_DRIVE) {
                        launchSystemGoogleAccountPicker()
                    } else {
                        showAddCloudCredentialsDialog(type)
                    }
                }
            }
            container.addView(row)
        }

        dialog.show()
    }

    /**
     * Orijinal APK'daki (00:28-00:30) Android Sistem Google Hesap Seçicisini ("Dosya Yöneticisi + için hesap seçin") doğrudan açar.
     */
    private fun launchSystemGoogleAccountPicker() {
        try {
            @Suppress("DEPRECATION")
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
            googleAccountPickerLauncher.launch(intent)
        } catch (_: Exception) {
            showAddCloudCredentialsDialog(CloudProviderType.GOOGLE_DRIVE)
        }
    }

    /**
     * Seçilen Google Drive e-posta adresini kaydeder ve GoogleAuthUtil üzerinden gerçek OAuth2 token'ını alır.
     */
    private fun authenticateAndSaveGoogleDriveAccount(email: String) {
        val newAcc = CloudAccount(
            id = UUID.randomUUID().toString(),
            provider = CloudProviderType.GOOGLE_DRIVE,
            emailOrName = email,
            displayName = email,
            accessToken = ""
        )
        CloudManager.addOrUpdateAccount(this, newAcc)
        refreshAccountsList()

        lifecycleScope.launch {
            val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, newAcc, forceRefresh = false)
            val recovery = CloudManager.pendingRecoveryIntent
            if (recovery != null) {
                pendingGoogleAccountForAuth = newAcc
                googleAuthConsentLauncher.launch(recovery)
            } else if (token.isNotBlank()) {
                CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, newAcc.copy(accessToken = token))
                refreshAccountsList()
                Toast.makeText(this@CloudAccountsActivity, "Google Drive eklendi: $email", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@CloudAccountsActivity,
                    "Google Drive eklendi: $email (Klasörü açtığınızda erişim izni istenecektir)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showAddCloudCredentialsDialog(provider: CloudProviderType) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }

        val etEmail = EditText(this).apply {
            hint = if (provider == CloudProviderType.GOOGLE_DRIVE) {
                "Google Drive E-posta Adresi (örn: kullanici@gmail.com)"
            } else {
                "${provider.displayName} Hesap E-postası / Kullanıcı Adı"
            }
            setSingleLine()
        }
        val etServer = EditText(this).apply {
            hint = "WebDAV / Nextcloud Sunucu URL (https://...)"
            setSingleLine()
            visibility = if (provider == CloudProviderType.NEXTCLOUD) View.VISIBLE else View.GONE
        }
        val etToken = EditText(this).apply {
            hint = if (provider == CloudProviderType.NEXTCLOUD) {
                "Uygulama Şifresi / Parola"
            } else {
                "OAuth2 Erişim Anahtarı (Cihaz hesabıysa boş bırakın)"
            }
            setSingleLine()
        }

        layout.addView(etEmail)
        layout.addView(etServer)
        layout.addView(etToken)

        AlertDialog.Builder(this)
            .setTitle("${provider.displayName} Hesabı Ekle")
            .setView(layout)
            .setPositiveButton("Ekle") { _, _ ->
                val email = etEmail.text?.toString()?.trim().orEmpty()
                val token = etToken.text?.toString()?.trim().orEmpty()
                val server = etServer.text?.toString()?.trim().orEmpty()
                if (email.isNotEmpty()) {
                    if (provider == CloudProviderType.GOOGLE_DRIVE && token.isEmpty()) {
                        authenticateAndSaveGoogleDriveAccount(email)
                    } else {
                        val acc = CloudAccount(
                            id = UUID.randomUUID().toString(),
                            provider = provider,
                            emailOrName = email,
                            displayName = email,
                            accessToken = token,
                            serverUrl = server,
                            username = email
                        )
                        CloudManager.addOrUpdateAccount(this, acc)
                        refreshAccountsList()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showAccountOptionsDialog(account: CloudAccount) {
        val items = arrayOf(
            "Hesabı Aç (${account.emailOrName})",
            "Google Drive Oturum İznini Yenile",
            "Hesabı Kaldır"
        )
        AlertDialog.Builder(this)
            .setTitle("${account.provider.displayName} • ${account.emailOrName}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openCloudAccountExplorer(account)
                    1 -> {
                        lifecycleScope.launch {
                            CloudManager.clearAccountCache(account.id)
                            val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account, forceRefresh = true)
                            val recovery = CloudManager.pendingRecoveryIntent
                            if (recovery != null) {
                                pendingGoogleAccountForAuth = account
                                googleAuthConsentLauncher.launch(recovery)
                            } else if (token.isNotBlank()) {
                                CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, account.copy(accessToken = token))
                                refreshAccountsList()
                                Toast.makeText(this@CloudAccountsActivity, "Oturum yenilendi: ${account.emailOrName}", Toast.LENGTH_SHORT).show()
                            } else {
                                showAddCloudCredentialsDialog(account.provider)
                            }
                        }
                    }
                    2 -> {
                        CloudManager.removeAccount(this, account.id)
                        refreshAccountsList()
                    }
                }
            }
            .show()
    }

    private fun refreshAccountsList() {
        val accounts = CloudManager.getAccounts(this)
        accountsAdapter.updateAccounts(accounts)
        binding.tvCloudToolbarTitle.text = "Bulut"
    }

    private fun applyCurrentTheme() {
        ThemeManager.applyWindowColors(this, binding.cloudRoot, binding.toolbarCloud)
        binding.tvAddCloudFooter.setTextColor(ThemeManager.getPrimaryTextColor(this))
    }
}
