package com.alphainventor.filemanager.ui

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.UUID

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bulut Ekranı (Google Drive + MEGA + Çoklu Bulut):
 * 1) "+ Bir bulut konumu ekle -> Google Drive" tıklandığında doğrudan Google Play Hizmetleri
 *    Sistem Hesap Seçicisini (com.google.android.gms.common.account.CHOOSE_ACCOUNT) açar.
 * 2) Seçilen Google hesabı için:
 *    - Çift izin sorma hatası tamamen kaldırıldı: Doğrudan tek adımlı MergeSession ("weblogin:service=gaia&continue=...")
 *      köprüsüyle cihazdaki açık Google hesabına şifresiz giriş yapılır.
 *    - WebView içinde anonim JavascriptInterface sınıfı yerine doğrudan Ana UI İş Parçacığında (Main Thread)
 *      çalışan evaluateJavascript geri çağrısı kullanılarak hem "rclone" ibareleri "Dosya Yöneticisi +" ve
 *      Google Drive logosuyla değiştirilir, hem "Devam / İzin ver" butonu otomatik tıklanır, hem de
 *      "Google Drive hesabına bağlanılıyor..." katmanının sonsuza kadar takılı kalması kesin olarak engellenir.
 * 3) MEGA (mega.nz) hesap ekleme (Doğrudan E-posta + Şifre API v1/v2 ve 2FA destekli Web Oturum Senkronizasyonu).
 * 4) Sağ üstteki BÜYÜTEÇ (🔍) ikonuna basınca tüm bağlı Google Drive, MEGA ve diğer bulut hesaplarında paralel arama yapar.
 */
class CloudAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var accountsAdapter: CloudAccountsAdapter
    private lateinit var unifiedResultsAdapter: FileListAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFilter: CloudSearchFilter = CloudSearchFilter.ALL
    private var unifiedSearchJob: Job? = null
    private var loopbackServerJob: Job? = null
    private var activeLoopbackSocket: ServerSocket? = null
    private var pendingGoogleAccountForAuth: CloudAccount? = null

    private var searchUiState: Int = 0
    private var searchHistoryContainer: LinearLayout? = null

    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim()
            if (!email.isNullOrBlank()) {
                onGoogleAccountPickedFromPlayServices(email)
            }
        }
    }

    private val googlePlayConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val acc = pendingGoogleAccountForAuth
        if (result.resultCode == Activity.RESULT_OK && acc != null) {
            lifecycleScope.launch {
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, acc, forceRefresh = true)
                if (token.isNotBlank()) {
                    CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, acc.copy(accessToken = token))
                    refreshAccountsList()
                    launchExplorerForAccount(
                        CloudManager.getAccounts(this@CloudAccountsActivity).firstOrNull { it.id == acc.id } ?: acc
                    )
                } else {
                    startGoogleDriveOAuthFlow(existingAccount = acc, autoOpenWhenConnected = true)
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
            openSearchTypingState()
        }

        val autoAuthAccountId = intent.getStringExtra("AUTO_AUTH_ACCOUNT_ID")
        if (!autoAuthAccountId.isNullOrBlank()) {
            val acc = CloudManager.getAccounts(this).firstOrNull { it.id == autoAuthAccountId }
            if (acc != null) {
                openCloudAccountExplorer(acc)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        refreshAccountsList()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopLoopbackServer()
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.btnCloudBack.setOnClickListener {
            if (searchUiState != 0) {
                closeUnifiedSearch()
            } else {
                finish()
            }
        }

        binding.btnCloudToolbarSearch.setOnClickListener {
            when (searchUiState) {
                0 -> openSearchTypingState()
                1 -> executeUnifiedMultiCloudSearch()
                2 -> openSearchTypingState()
            }
        }

        binding.btnBatchAddDrive.setOnClickListener { view ->
            if (searchUiState == 2) {
                showSearchFilterPopupMenu(view)
            } else {
                val popup = PopupMenu(this, view)
                popup.menu.add(0, 1, 0, "Google Drive Hesabı Seç (Play Hizmetleri)")
                popup.menu.add(0, 2, 1, "MEGA Hesabı Ekle")
                popup.menu.add(0, 3, 2, if (accountsAdapter.isEditMode) "Düzenleme Modunu Kapat" else "Hesapları Düzenle / Kaldır")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> launchNativeGoogleAccountPicker()
                        2 -> showAddMegaAccountDialog()
                        3 -> {
                            accountsAdapter.isEditMode = !accountsAdapter.isEditMode
                            Toast.makeText(
                                this,
                                if (accountsAdapter.isEditMode) "Düzenleme modu açık: Hesaba dokunarak yönetebilirsiniz."
                                else "Düzenleme modu kapatıldı.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    true
                }
                popup.show()
            }
        }

        binding.btnAddCloudLocation.setOnClickListener {
            showAddCloudProviderDialog()
        }
    }

    private fun launchNativeGoogleAccountPicker(preselectedEmail: String? = null) {
        try {
            val gmsIntent = Intent("com.google.android.gms.common.account.CHOOSE_ACCOUNT").apply {
                setPackage("com.google.android.gms")
                putExtra("allowableAccountTypes", arrayOf("com.google"))
                putExtra("alwaysPromptForAccount", true)
                if (!preselectedEmail.isNullOrBlank()) {
                    putExtra("selectedAccount", android.accounts.Account(preselectedEmail, "com.google"))
                }
            }
            if (gmsIntent.resolveActivity(packageManager) != null) {
                googleAccountPickerLauncher.launch(gmsIntent)
                return
            }
        } catch (_: Throwable) {
        }

        try {
            @Suppress("DEPRECATION")
            val fallbackIntent = AccountManager.newChooseAccountIntent(
                if (!preselectedEmail.isNullOrBlank()) android.accounts.Account(preselectedEmail, "com.google") else null,
                null,
                arrayOf("com.google"),
                true,
                null,
                null,
                null,
                null
            )
            googleAccountPickerLauncher.launch(fallbackIntent)
        } catch (_: Throwable) {
            startGoogleDriveOAuthFlow(existingAccount = null, autoOpenWhenConnected = true)
        }
    }

    private fun onGoogleAccountPickedFromPlayServices(email: String) {
        val existing = CloudManager.getAccounts(this).firstOrNull {
            it.provider == CloudProviderType.GOOGLE_DRIVE && it.emailOrName.equals(email, ignoreCase = true)
        }
        val savedAccount = existing ?: CloudAccount(
            id = UUID.randomUUID().toString(),
            provider = CloudProviderType.GOOGLE_DRIVE,
            emailOrName = email,
            displayName = email
        )
        CloudManager.addOrUpdateAccount(this, savedAccount)
        refreshAccountsList()

        // Eğer hesabın zaten kayıtlı token'ı varsa doğrudan aç; yoksa çift izin penceresi göstermeden
        // doğrudan tek adımlı MergeSession + OAuth akışını başlat!
        if (savedAccount.accessToken.isNotBlank() || savedAccount.refreshToken.isNotBlank()) {
            lifecycleScope.launch {
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, savedAccount, forceRefresh = false)
                if (token.isNotBlank()) {
                    val updated = CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, savedAccount.copy(accessToken = token))
                    refreshAccountsList()
                    launchExplorerForAccount(updated)
                } else {
                    startGoogleDriveOAuthFlow(existingAccount = savedAccount, autoOpenWhenConnected = true)
                }
            }
        } else {
            startGoogleDriveOAuthFlow(existingAccount = savedAccount, autoOpenWhenConnected = true)
        }
    }

    private fun showSearchFilterPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Tüm Dosyalar")
        popup.menu.add(0, 2, 1, "Sadece Videolar")
        popup.menu.add(0, 3, 2, "Sadece Ses Dosyaları")
        popup.menu.add(0, 4, 3, "Sadece Görüntüler")
        popup.menu.add(0, 5, 4, "Sadece Belgeler")
        popup.menu.add(0, 6, 5, "Sadece Arşivler")
        popup.setOnMenuItemClickListener { item ->
            val filter = when (item.itemId) {
                2 -> CloudSearchFilter.VIDEOS
                3 -> CloudSearchFilter.AUDIO
                4 -> CloudSearchFilter.IMAGES
                5 -> CloudSearchFilter.DOCUMENTS
                6 -> CloudSearchFilter.ARCHIVES
                else -> CloudSearchFilter.ALL
            }
            updateSearchFilter(filter)
            true
        }
        popup.show()
    }

    private fun openSearchTypingState() {
        searchUiState = 1
        binding.toolbarCloud.setBackgroundColor(Color.parseColor("#212121"))
        binding.btnCloudBack.setImageResource(R.drawable.ic_back_white)
        binding.tvCloudToolbarTitle.text = "Ara"

        binding.cardUnifiedCloudSearch.visibility = View.VISIBLE
        binding.llUnifiedSearchResultsSection.visibility = View.GONE
        binding.rvCloudAccounts.visibility = View.GONE
        binding.btnAddCloudLocation.visibility = View.GONE

        renderSearchHistoryRows()

        binding.etUnifiedCloudSearch.requestFocus()
        binding.etUnifiedCloudSearch.setSelection(binding.etUnifiedCloudSearch.text?.length ?: 0)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etUnifiedCloudSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun switchToBlueResultsState(query: String) {
        searchUiState = 2
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUnifiedCloudSearch.windowToken, 0)

        binding.toolbarCloud.setBackgroundColor(Color.parseColor("#3F51B5"))
        binding.btnCloudBack.setImageResource(R.drawable.ic_close_white)
        val accounts = CloudManager.getAccounts(this)
        val allGDrive = accounts.isNotEmpty() && accounts.all { it.provider == CloudProviderType.GOOGLE_DRIVE }
        binding.tvCloudToolbarTitle.text = if (allGDrive) "Ara: Google Drive" else "Ara: Tüm Bulutlar"

        binding.cardUnifiedCloudSearch.visibility = View.GONE
        binding.rvCloudAccounts.visibility = View.GONE
        binding.btnAddCloudLocation.visibility = View.GONE
        binding.llUnifiedSearchResultsSection.visibility = View.VISIBLE

        binding.tvSearchResultsHeader.text = "\"$query\""
        binding.tvSearchResultsHeader.gravity = Gravity.CENTER
        binding.tvSearchResultsHeader.textSize = 15f
        binding.btnCloseSearchResults.visibility = View.GONE
    }

    private fun closeUnifiedSearch() {
        searchUiState = 0
        unifiedSearchJob?.cancel()
        binding.etUnifiedCloudSearch.setText("")
        unifiedResultsAdapter.highlightQuery = ""
        binding.cardUnifiedCloudSearch.visibility = View.GONE
        binding.llUnifiedSearchStatus.visibility = View.GONE
        binding.llUnifiedSearchResultsSection.visibility = View.GONE
        binding.rvCloudAccounts.visibility = View.VISIBLE
        binding.btnAddCloudLocation.visibility = View.VISIBLE

        binding.btnCloudBack.setImageResource(R.drawable.ic_back_white)
        binding.tvCloudToolbarTitle.text = "Bulut"
        applyCurrentTheme()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUnifiedCloudSearch.windowToken, 0)
    }

    private fun ensureSearchHistoryContainer(): LinearLayout {
        searchHistoryContainer?.let { return it }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        (binding.cardUnifiedCloudSearch as? ViewGroup)?.addView(container)
        searchHistoryContainer = container
        return container
    }

    private fun renderSearchHistoryRows() {
        val container = ensureSearchHistoryContainer()
        container.removeAllViews()
        val history = CloudManager.getSearchHistory(this)
        if (history.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val textColor = ThemeManager.getPrimaryTextColor(this)
        val subColor = ThemeManager.getSecondaryTextColor(this)

        for (queryItem in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (12 * density).toInt()
                val padV = (10 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }

            val ivClock = ImageView(this).apply {
                val sz = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (14 * density).toInt()
                }
                setImageResource(R.drawable.ic_history_clock)
                setColorFilter(subColor)
            }

            val tvQuery = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = queryItem
                textSize = 15f
                setTextColor(textColor)
                isSingleLine = true
            }

            val btnDeleteHistory = ImageButton(this).apply {
                val sz = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setImageResource(R.drawable.ic_close_white)
                setColorFilter(subColor)
                setOnClickListener {
                    CloudManager.removeSearchHistory(this@CloudAccountsActivity, queryItem)
                    renderSearchHistoryRows()
                }
            }

            row.setOnClickListener {
                binding.etUnifiedCloudSearch.setText(queryItem)
                binding.etUnifiedCloudSearch.setSelection(queryItem.length)
                executeUnifiedMultiCloudSearch()
            }

            row.addView(ivClock)
            row.addView(tvQuery)
            row.addView(btnDeleteHistory)
            container.addView(row)
        }
    }

    private fun setupAccountsList() {
        accountsAdapter = CloudAccountsAdapter(
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
        binding.rvCloudAccounts.layoutManager = GridLayoutManager(this, 3)
        binding.rvCloudAccounts.adapter = accountsAdapter
    }

    private fun openCloudAccountExplorer(account: CloudAccount) {
        lifecycleScope.launch {
            if (account.provider == CloudProviderType.GOOGLE_DRIVE) {
                if (account.accessToken.isBlank() && account.refreshToken.isBlank()) {
                    startGoogleDriveOAuthFlow(existingAccount = account, autoOpenWhenConnected = true)
                    return@launch
                }
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account, forceRefresh = false)
                val recovery = CloudManager.pendingRecoveryIntent
                if (recovery != null) {
                    pendingGoogleAccountForAuth = account
                    googlePlayConsentLauncher.launch(recovery)
                    return@launch
                }
                if (token.isBlank() || CloudManager.needsWebOAuthFallbackForAccount != null) {
                    startGoogleDriveOAuthFlow(existingAccount = account, autoOpenWhenConnected = true)
                    return@launch
                }
            }
            val latest = CloudManager.getAccounts(this@CloudAccountsActivity)
                .firstOrNull { it.id == account.id } ?: account
            launchExplorerForAccount(latest)
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
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeUnifiedMultiCloudSearch()
                true
            } else {
                false
            }
        }

        binding.etUnifiedCloudSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearCloudSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearCloudSearch.setOnClickListener {
            binding.etUnifiedCloudSearch.setText("")
            unifiedSearchJob?.cancel()
            unifiedResultsAdapter.highlightQuery = ""
            binding.llUnifiedSearchStatus.visibility = View.GONE
            binding.llUnifiedSearchResultsSection.visibility = View.GONE
            renderSearchHistoryRows()
        }

        binding.btnCloseSearchResults.setOnClickListener {
            closeUnifiedSearch()
        }

        binding.chipFilterAll.setOnClickListener { updateSearchFilter(CloudSearchFilter.ALL) }
        binding.chipFilterVideos.setOnClickListener { updateSearchFilter(CloudSearchFilter.VIDEOS) }
        binding.chipFilterAudio.setOnClickListener { updateSearchFilter(CloudSearchFilter.AUDIO) }
        binding.chipFilterImages.setOnClickListener { updateSearchFilter(CloudSearchFilter.IMAGES) }
        binding.chipFilterDocs.setOnClickListener { updateSearchFilter(CloudSearchFilter.DOCUMENTS) }
    }

    private fun updateSearchFilter(filter: CloudSearchFilter) {
        activeFilter = filter
        val activeBg = Color.parseColor("#FF9800")
        val inactiveBg = Color.parseColor("#2A2F3A")

        binding.chipFilterAll.setBackgroundColor(if (filter == CloudSearchFilter.ALL) activeBg else inactiveBg)
        binding.chipFilterVideos.setBackgroundColor(if (filter == CloudSearchFilter.VIDEOS) activeBg else inactiveBg)
        binding.chipFilterAudio.setBackgroundColor(if (filter == CloudSearchFilter.AUDIO) activeBg else inactiveBg)
        binding.chipFilterImages.setBackgroundColor(if (filter == CloudSearchFilter.IMAGES) activeBg else inactiveBg)
        binding.chipFilterDocs.setBackgroundColor(if (filter == CloudSearchFilter.DOCUMENTS) activeBg else inactiveBg)

        if (binding.etUnifiedCloudSearch.text?.isNotBlank() == true) {
            executeUnifiedMultiCloudSearch()
        }
    }

    private fun executeUnifiedMultiCloudSearch() {
        val query = binding.etUnifiedCloudSearch.text?.toString()?.trim().orEmpty()
        val accounts = CloudManager.getAccounts(this)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "Önce '+ Bir bulut konumu ekle' ile bulut hesabınızı ekleyin.", Toast.LENGTH_SHORT).show()
            return
        }
        if (query.isEmpty()) {
            Toast.makeText(this, "Aramak istediğiniz dosya veya video adını yazın.", Toast.LENGTH_SHORT).show()
            return
        }

        switchToBlueResultsState(query)
        unifiedResultsAdapter.highlightQuery = query

        unifiedSearchJob?.cancel()
        binding.llUnifiedSearchStatus.visibility = View.VISIBLE
        binding.pbUnifiedSearch.visibility = View.VISIBLE
        binding.tvUnifiedSearchProgress.text = "${accounts.size} bulut hesabında \"$query\" aranıyor..."

        unifiedSearchJob = lifecycleScope.launch {
            val results = CloudManager.searchAcrossAllAccounts(
                context = this@CloudAccountsActivity,
                query = query,
                filter = activeFilter,
                onProgress = { progress ->
                    runOnUiThread {
                        binding.tvUnifiedSearchProgress.text =
                            "Taranıyor (${progress.completedAccounts}/${progress.totalAccounts}): ${progress.currentAccountName}"
                        unifiedResultsAdapter.submitList(progress.partialResults)
                    }
                }
            )
            binding.pbUnifiedSearch.visibility = View.GONE
            binding.llUnifiedSearchStatus.visibility = View.GONE
            binding.tvSearchResultsHeader.text = "\"$query\""
            unifiedResultsAdapter.submitList(results)

            if (results.isEmpty()) {
                val unauthorizedGoogle = accounts.firstOrNull {
                    it.provider == CloudProviderType.GOOGLE_DRIVE && it.accessToken.isBlank() && it.refreshToken.isBlank()
                }
                if (unauthorizedGoogle != null) {
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "${unauthorizedGoogle.emailOrName} için Google Drive izni gerekiyor.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "\"$query\" için eşleşen dosya bulunamadı.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
            openCloudItemInternally(item, account)
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
                    1 -> {
                        lifecycleScope.launch {
                            val token = if (account != null && account.provider == CloudProviderType.GOOGLE_DRIVE) {
                                CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account)
                            } else ""
                            LocalFileRepository.openWithExternalChooser(this@CloudAccountsActivity, item, token)
                        }
                    }
                    2 -> {
                        if (account != null) {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "CLOUD")
                                putExtra("CLOUD_ACCOUNT", account)
                                putExtra("INITIAL_FOLDER_ID", "root")
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
                                    "Klasör: /${item.parentId}/\n" +
                                    "Boyut: ${com.alphainventor.filemanager.util.FormatUtils.formatSizeDetailed(item.size)}\n" +
                                    "Dosya Kimliği: ${item.id}"
                            )
                            .setPositiveButton("TAMAM", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun openCloudItemInternally(item: FileItem, account: CloudAccount?) {
        lifecycleScope.launch {
            val activeToken = if (account != null && account.provider == CloudProviderType.GOOGLE_DRIVE) {
                CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account, forceRefresh = false)
            } else {
                account?.accessToken ?: ""
            }

            when {
                item.isVideo -> {
                    if (AppSettings.useInternalVideoPlayer) {
                        startActivity(Intent(this@CloudAccountsActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", item.downloadUrl.ifBlank { item.path })
                            putExtra("VIDEO_TITLE", item.name)
                            putExtra("AUTH_TOKEN", activeToken)
                        })
                    } else {
                        LocalFileRepository.openWithExternalChooser(this@CloudAccountsActivity, item, activeToken)
                    }
                }
                item.isAudio -> {
                    startActivity(Intent(this@CloudAccountsActivity, MusicPlayerActivity::class.java).apply {
                        putExtra("AUDIO_URL", item.downloadUrl.ifBlank { item.path })
                        putExtra("AUDIO_TITLE", item.name)
                        putExtra("AUTH_TOKEN", activeToken)
                    })
                }
                item.isImage -> {
                    startActivity(Intent(this@CloudAccountsActivity, ImageViewerActivity::class.java).apply {
                        putExtra("IMAGE_PATH", item.downloadUrl.ifBlank { item.path })
                        putExtra("IMAGE_TITLE", item.name)
                        putExtra("AUTH_TOKEN", activeToken)
                    })
                }
                else -> {
                    LocalFileRepository.openWithExternalChooser(this@CloudAccountsActivity, item, activeToken)
                }
            }
        }
    }

    private fun showAddCloudProviderDialog() {
        val providers = listOf(
            CloudProviderType.GOOGLE_DRIVE to R.drawable.cloud_icon_googledrive,
            CloudProviderType.MEGA to R.drawable.ic_cloud_mega,
            CloudProviderType.DROPBOX to R.drawable.cloud_icon_dropbox,
            CloudProviderType.ONEDRIVE to R.drawable.cloud_icon_onedrive,
            CloudProviderType.BOX to R.drawable.cloud_icon_box,
            CloudProviderType.YANDEX to R.drawable.cloud_icon_yandex,
            CloudProviderType.NEXTCLOUD to R.drawable.ic_cloud_nextcloud
        )

        val density = resources.displayMetrics.density
        val grid = GridLayout(this).apply {
            columnCount = 3
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ThemeManager.getCardColor(this@CloudAccountsActivity))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Yeni bulut bağlantısı")
            .setView(grid)
            .create()

        providers.forEach { (type, iconRes) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val p = (10 * density).toInt()
                setPadding(p, p, p, p)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }

                val iv = ImageView(this@CloudAccountsActivity).apply {
                    val sz = (54 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val tv = TextView(this@CloudAccountsActivity).apply {
                    text = type.displayName
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * density).toInt(), 0, 0)
                    setTextColor(ThemeManager.getPrimaryTextColor(this@CloudAccountsActivity))
                }
                addView(iv)
                addView(tv)
                setOnClickListener {
                    dialog.dismiss()
                    when (type) {
                        CloudProviderType.GOOGLE_DRIVE -> launchNativeGoogleAccountPicker()
                        CloudProviderType.MEGA -> showAddMegaAccountDialog()
                        else -> showAddCloudCredentialsDialog(type)
                    }
                }
            }
            grid.addView(cell)
        }

        dialog.show()
    }

    /**
     * MEGA (mega.nz) Hesap Ekleme Diyaloğu:
     * 1) Doğrudan E-posta + Şifre ile API v1/v2 girişi (PBKDF2-HMAC-SHA512 + RSA + AES-CBC).
     * 2) 2FA açık olan hesaplar için "MEGA Web ile Giriş Yap" seçeneği.
     */
    private fun showAddMegaAccountDialog(existingAccount: CloudAccount? = null) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * density).toInt())
        }

        val tvInfo = TextView(this).apply {
            text = "MEGA hesabınızın e-posta ve şifresini girerek veya 2FA açık ise Web oturumuyla doğrudan bağlanabilirsiniz."
            textSize = 13f
            setTextColor(ThemeManager.getSecondaryTextColor(this@CloudAccountsActivity))
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        val etEmail = EditText(this).apply {
            hint = "MEGA E-posta Adresi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
            setText(existingAccount?.emailOrName ?: "")
        }

        val etPassword = EditText(this).apply {
            hint = "MEGA Şifresi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        layout.addView(tvInfo)
        layout.addView(etEmail)
        layout.addView(etPassword)

        val dialog = AlertDialog.Builder(this)
            .setTitle("MEGA Hesabı Bağla")
            .setView(layout)
            .setPositiveButton("BAĞLAN", null)
            .setNeutralButton("WEB İLE GİRİŞ (2FA)") { d, _ ->
                d.dismiss()
                val emailHint = etEmail.text?.toString()?.trim().orEmpty()
                startMegaWebAuthFlow(emailHint, existingAccount)
            }
            .setNegativeButton("İPTAL", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString().orEmpty()
            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Lütfen MEGA e-posta ve şifrenizi girin veya 'WEB İLE GİRİŞ' butonunu kullanın.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            Toast.makeText(this, "MEGA hesabına bağlanılıyor...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val saved = CloudManager.authenticateMegaAccount(
                    context = this@CloudAccountsActivity,
                    email = email,
                    password = password,
                    existingAccount = existingAccount
                )
                if (saved != null) {
                    dialog.dismiss()
                    refreshAccountsList()
                    Toast.makeText(this@CloudAccountsActivity, "✓ MEGA bağlandı: ${saved.emailOrName}", Toast.LENGTH_SHORT).show()
                    launchExplorerForAccount(saved)
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        CloudManager.lastCloudErrorMessage ?: "MEGA girişi başarısız oldu.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startMegaWebAuthFlow(emailHint: String = "", existingAccount: CloudAccount? = null) {
        val dm = resources.displayMetrics
        val density = dm.density
        val dialogHeightPx = (dm.heightPixels * 0.85f).toInt().coerceAtLeast((380 * density).toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dialogHeightPx)
            setBackgroundColor(Color.parseColor("#181818"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, (8 * density).toInt(), p, (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#D9272E"))
        }

        val tvTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "MEGA Hesap Girişi (Giriş yapınca otomatik bağlanır)"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isSingleLine = true
        }

        val btnCancel = Button(this).apply {
            text = "KAPAT"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
        }

        topBar.addView(tvTitle)
        topBar.addView(btnCancel)

        var megaDialog: AlertDialog? = null
        var synced = false

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Safari/537.36"
        }

        val extractMegaTreeJs = """
            (function() {
                try {
                    if (!window.M || !window.M.d) return '';
                    var nodes = window.M.d;
                    var keys = Object.keys(nodes);
                    if (keys.length === 0) return '';
                    var rootId = window.M.RootID || '';
                    var out = [];
                    for (var i = 0; i < keys.length; i++) {
                        var n = nodes[keys[i]];
                        if (!n || (n.t !== 0 && n.t !== 1)) continue;
                        if (!n.name) continue;
                        var p = (n.p === rootId || !n.p) ? 'root' : String(n.p);
                        out.push({
                            h: String(n.h || keys[i]),
                            p: p,
                            n: String(n.name),
                            t: Number(n.t || 0),
                            s: Number(n.s || 0),
                            ts: Number(n.ts || 0)
                        });
                    }
                    if (out.length === 0 && !window.u_attr) return '';
                    var email = (window.u_attr && window.u_attr.email) ? String(window.u_attr.email) : '$emailHint';
                    return JSON.stringify({
                        email: email || 'MEGA Hesabı',
                        used: Number(window.M.cstrg || 0),
                        total: Number(window.M.mstrg || 21474836480),
                        nodes: out
                    });
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()

        val pollRunnable = object : Runnable {
            override fun run() {
                if (synced || megaDialog?.isShowing != true) return
                webView.evaluateJavascript(extractMegaTreeJs) { raw ->
                    val unescaped = unescapeEvaluateJsString(raw)
                    if (unescaped.startsWith("{") && unescaped.contains("\"nodes\"")) {
                        try {
                            val obj = JSONObject(unescaped)
                            val nodesArr = obj.optJSONArray("nodes")
                            if (nodesArr != null) {
                                synced = true
                                val saved = CloudManager.saveMegaWebSyncedAccountAndNodes(
                                    context = this@CloudAccountsActivity,
                                    email = obj.optString("email", emailHint.ifBlank { "MEGA Hesabı" }),
                                    usedBytes = obj.optLong("used", 0L),
                                    totalBytes = obj.optLong("total", 20L * 1024 * 1024 * 1024),
                                    nodesJsonArrayString = nodesArr.toString(),
                                    existingAccount = existingAccount
                                )
                                megaDialog?.dismiss()
                                refreshAccountsList()
                                Toast.makeText(
                                    this@CloudAccountsActivity,
                                    "✓ MEGA bağlandı: ${saved.emailOrName}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                launchExplorerForAccount(saved)
                                return@evaluateJavascript
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                mainHandler.postDelayed(this, 1200L)
            }
        }

        root.addView(topBar)
        root.addView(webView)

        megaDialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        btnCancel.setOnClickListener { megaDialog.dismiss() }
        megaDialog.show()
        megaDialog.window?.let { win ->
            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            win.setLayout((dm.widthPixels * 0.94f).toInt(), dialogHeightPx)
        }

        webView.loadUrl("https://mega.nz/login")
        mainHandler.postDelayed(pollRunnable, 1500L)
    }

    /**
     * Cihazdaki açık Google hesabını (MergeSession weblogin köprüsü + Ana UI İş Parçacığı evaluateJavascript)
     * kullanarak Google Drive OAuth 2.0 yetkilendirmesini hatasız tamamlar:
     * 1) Tek bir deterministik "weblogin:service=gaia&continue=..." çağrısı ile MergeSession URL'sini alır
     *    (çift izin isteme hatası tamamen giderildi).
     * 2) Anonim JavascriptInterface sınıfı yerine doğrudan Ana UI İş Parçacığında (Main Thread) çalışan
     *    evaluateJavascript(js) { result -> ... } kullanılır; böylece WebView hiçbir zaman "Google Drive hesabına
     *    bağlanılıyor..." ekranında asılı kalmaz!
     * 3) Önce OAuth onay butonunu ("Devam", "İzin ver", "#submit_approve_access") kontrol edip otomatik tıklar;
     *    onay ekranındaki üst e-posta rozetine ([data-email]) yanlışlıkla tıklama hatası tamamen kaldırıldı.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startGoogleDriveOAuthFlow(existingAccount: CloudAccount?, autoOpenWhenConnected: Boolean = true) {
        stopLoopbackServer()

        lifecycleScope.launch {
            val serverSocket = withContext(Dispatchers.IO) {
                try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("127.0.0.1", 53682), 5)
                    }
                } catch (_: Exception) {
                    ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
                }
            }
            activeLoopbackSocket = serverSocket
            val port = serverSocket.localPort
            val redirectUri = "http://127.0.0.1:$port"
            val emailHint = existingAccount?.emailOrName?.takeIf { it.contains("@") }
            val loginHintParam = if (!emailHint.isNullOrBlank()) {
                "&login_hint=" + URLEncoder.encode(emailHint, "UTF-8")
            } else ""

            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + URLEncoder.encode(CloudManager.GOOGLE_OAUTH_CLIENT_ID, "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(CloudManager.GOOGLE_OAUTH_SCOPE_URL, "UTF-8") +
                "&access_type=offline" +
                "&include_granted_scopes=true" +
                loginHintParam

            // Cihazdaki açık Google hesabından tek adımlı otomatik oturum açma (MergeSession) URL'sini al
            val mergeSessionUrl = if (!emailHint.isNullOrBlank()) {
                CloudManager.tryGetWebLoginMergeSessionUrl(
                    context = this@CloudAccountsActivity,
                    activity = this@CloudAccountsActivity,
                    email = emailHint,
                    continueUrl = authUrl
                )
            } else null

            val initialLoadUrl = mergeSessionUrl ?: authUrl
            val dm = resources.displayMetrics
            val density = dm.density
            val dialogHeightPx = (dm.heightPixels * 0.86f).toInt().coerceAtLeast((360 * density).toInt())

            val rootContainer = FrameLayout(this@CloudAccountsActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dialogHeightPx
                )
                setBackgroundColor(Color.parseColor("#181818"))
            }

            val mainColumn = LinearLayout(this@CloudAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dialogHeightPx
                )
            }

            val topHelperBar = LinearLayout(this@CloudAccountsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, (8 * density).toInt(), p, (8 * density).toInt())
                setBackgroundColor(Color.parseColor("#212121"))
            }

            val ivDriveLogo = ImageView(this@CloudAccountsActivity).apply {
                val sz = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (10 * density).toInt()
                }
                setImageResource(R.drawable.cloud_icon_googledrive)
            }

            val tvHelper = TextView(this@CloudAccountsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = if (!emailHint.isNullOrBlank()) "Google Drive • $emailHint" else "Google Drive Bağlantısı"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
            }

            val btnCancelTop = Button(this@CloudAccountsActivity).apply {
                text = "İPTAL"
                textSize = 12f
                setTextColor(Color.parseColor("#B0BEC5"))
                setBackgroundColor(Color.TRANSPARENT)
            }

            topHelperBar.addView(ivDriveLogo)
            topHelperBar.addView(tvHelper)
            topHelperBar.addView(btnCancelTop)

            val loadingOverlay = LinearLayout(this@CloudAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#181818"))
                val pad = (24 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
            }

            val ivLoadingDrive = ImageView(this@CloudAccountsActivity).apply {
                val sz = (60 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    bottomMargin = (14 * density).toInt()
                }
                setImageResource(R.drawable.cloud_icon_googledrive)
            }

            val pbConnecting = ProgressBar(this@CloudAccountsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (34 * density).toInt(),
                    (34 * density).toInt()
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
            }

            val tvLoadingStatus = TextView(this@CloudAccountsActivity).apply {
                text = if (!emailHint.isNullOrBlank()) {
                    "Google Drive hesabına bağlanılıyor...\n$emailHint"
                } else {
                    "Google Drive hesabına bağlanılıyor..."
                }
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }

            loadingOverlay.addView(ivLoadingDrive)
            loadingOverlay.addView(pbConnecting)
            loadingOverlay.addView(tvLoadingStatus)

            var authDialog: AlertDialog? = null
            var codeHandled = false
            var webViewRef: WebView? = null

            fun revealCleanWebViewOnUiThread() {
                mainHandler.post {
                    if (!codeHandled) {
                        webViewRef?.alpha = 1f
                        loadingOverlay.visibility = View.GONE
                    }
                }
            }

            fun handleAuthCodeReceived(code: String) {
                if (codeHandled || code.isBlank()) return
                codeHandled = true
                mainHandler.post {
                    CookieManager.getInstance().flush()
                    loadingOverlay.visibility = View.VISIBLE
                    tvLoadingStatus.text = "Google Drive dosyaları hazırlanıyor..."
                }
                stopLoopbackServer()
                lifecycleScope.launch {
                    val saved = CloudManager.exchangeGoogleAuthCodeAndSaveAccount(
                        context = this@CloudAccountsActivity,
                        authCode = code,
                        redirectUri = redirectUri,
                        existingAccount = existingAccount
                    )
                    authDialog?.dismiss()
                    if (saved != null) {
                        refreshAccountsList()
                        Toast.makeText(
                            this@CloudAccountsActivity,
                            "✓ Google Drive bağlandı: ${saved.emailOrName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (autoOpenWhenConnected) {
                            launchExplorerForAccount(saved)
                            if (!intent.getStringExtra("AUTO_AUTH_ACCOUNT_ID").isNullOrBlank()) {
                                finish()
                            }
                        }
                    } else {
                        Toast.makeText(
                            this@CloudAccountsActivity,
                            CloudManager.lastCloudErrorMessage ?: "Google Drive bağlantısı tamamlanamadı.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            fun dispatchViewportTapOnWebView(cssX: Float, cssY: Float, viewportW: Float, viewportH: Float) {
                val wv = webViewRef ?: return
                mainHandler.post {
                    if (wv.width <= 0 || wv.height <= 0) return@post
                    val vw = viewportW.coerceAtLeast(1f)
                    val vh = viewportH.coerceAtLeast(1f)
                    val px = ((cssX / vw) * wv.width).coerceIn(10f, (wv.width - 10).coerceAtLeast(20).toFloat())
                    val py = ((cssY / vh) * wv.height).coerceIn(10f, (wv.height - 10).coerceAtLeast(20).toFloat())
                    val downTime = SystemClock.uptimeMillis()
                    val downEvt = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
                    val upEvt = MotionEvent.obtain(downTime, downTime + 45L, MotionEvent.ACTION_UP, px, py, 0)
                    wv.dispatchTouchEvent(downEvt)
                    wv.dispatchTouchEvent(upEvt)
                    downEvt.recycle()
                    upEvt.recycle()
                }
            }

            val driveTriangleDataUri = "data:image/svg+xml;utf8," + Uri.encode(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 87.3 78"><path d="m6.6 66.85 3.85 6.65c.8 1.4 1.95 2.5 3.3 3.3l13.75-23.8h-27.5c0 1.55.4 3.1 1.2 4.5z" fill="#0066da"/><path d="m43.65 25-13.75-23.8c-1.35.8-2.5 1.9-3.3 3.3l-25.4 44a9.06 9.06 0 0 0 -1.2 4.5h27.5z" fill="#00ac47"/><path d="m73.55 76.8c1.35-.8 2.5-1.9 3.3-3.3l1.6-2.75 7.65-13.25c.8-1.4 1.2-2.95 1.2-4.5h-27.502l5.852 11.5z" fill="#ea4335"/><path d="m43.65 25 13.75-23.8c-1.35-.8-2.9-1.2-4.5-1.2h-18.5c-1.6 0-3.15.45-4.5 1.2z" fill="#00832d"/><path d="m59.8 53h-32.3l-13.75 23.8c1.35.8 2.9 1.2 4.5 1.2h50.8c1.6 0 3.15-.45 4.5-1.2z" fill="#2684fc"/><path d="m73.4 26.5-12.7-22c-.8-1.4-1.95-2.5-3.3-3.3l-13.75 23.8 16.15 28h27.45c0-1.55-.4-3.1-1.2-4.5z" fill="#ffba00"/></svg>"""
            )

            val escapedHintEmail = (emailHint ?: "").replace("'", "\\'")

            val sanitizeAndAutomateJs = """
                (function() {
                    try {
                        var driveIconUri = '$driveTriangleDataUri';
                        var targetEmail = '$escapedHintEmail'.toLowerCase();

                        function cleanBranding() {
                            if (window._fmCleaning || !document.head || !document.body) return;
                            window._fmCleaning = true;
                            try {
                                if (!document.getElementById('fm_plus_clean_style')) {
                                    var st = document.createElement('style');
                                    st.id = 'fm_plus_clean_style';
                                    st.innerHTML = 'div[data-logo] svg, div[data-custom-logo] svg { display:none !important; }';
                                    document.head.appendChild(st);
                                }
                                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                                var node;
                                while ((node = walker.nextNode())) {
                                    if (node.nodeValue && /rclone/i.test(node.nodeValue)) {
                                        node.nodeValue = node.nodeValue.replace(/rclone/gi, 'Dosya Yöneticisi +');
                                    }
                                }
                                var imgs = document.querySelectorAll('img');
                                for (var i = 0; i < imgs.length; i++) {
                                    var im = imgs[i];
                                    var src = (im.src || '').toLowerCase();
                                    var alt = (im.alt || '').toLowerCase();
                                    if (src.indexOf('googleusercontent') !== -1 && im.width >= 20 && im.width <= 96 && src.indexOf('avatar') === -1 && src.indexOf('/a/') === -1) {
                                        im.src = driveIconUri;
                                    } else if (alt.indexOf('rclone') !== -1) {
                                        im.src = driveIconUri;
                                        im.alt = 'Dosya Yöneticisi +';
                                    }
                                }
                            } finally {
                                window._fmCleaning = false;
                            }
                        }

                        cleanBranding();
                        if (window.MutationObserver && document.body && !window._fmObserverAttached) {
                            window._fmObserverAttached = true;
                            new MutationObserver(cleanBranding).observe(document.body, { childList: true, subtree: true });
                        }

                        var vw = window.innerWidth || document.documentElement.clientWidth || 360;
                        var vh = window.innerHeight || document.documentElement.clientHeight || 640;
                        var href = (window.location.href || '').toLowerCase();

                        // 1. ÖNCELİK: OAuth İzin / Onay Butonu ("Devam", "Devam et", "İzin ver", "Onayla", "Allow", "Continue", "#submit_approve_access")
                        var checkboxes = document.querySelectorAll('input[type="checkbox"]');
                        for (var c = 0; c < checkboxes.length; c++) {
                            if (!checkboxes[c].checked) {
                                checkboxes[c].click();
                            }
                        }

                        var approveBtn = document.getElementById('submit_approve_access');
                        if (!approveBtn) {
                            var allBtns = document.querySelectorAll('button, div[role="button"], input[type="submit"]');
                            for (var b = 0; b < allBtns.length; b++) {
                                var btnEl = allBtns[b];
                                if (btnEl.offsetParent === null) continue;
                                var rawTxt = (btnEl.innerText || btnEl.value || btnEl.textContent || '').trim();
                                var txt = rawTxt.toLowerCase();
                                if (txt === 'devam' || txt === 'devam et' || txt === 'i̇zin ver' || txt === 'izin ver' ||
                                    txt === 'onayla' || txt === 'kabul et' || txt === 'allow' || txt === 'continue') {
                                    approveBtn = btnEl;
                                    break;
                                }
                            }
                        }

                        if (approveBtn && approveBtn.offsetParent !== null && !approveBtn.disabled) {
                            approveBtn.scrollIntoView({block: 'center'});
                            var ar = approveBtn.getBoundingClientRect();
                            try { approveBtn.click(); } catch (e) {}
                            if (ar.width > 0 && ar.height > 0 && !window._fmConsentTapped) {
                                window._fmConsentTapped = true;
                                return 'TAP|' + (ar.left + ar.width / 2) + '|' + (ar.top + ar.height / 2) + '|' + vw + '|' + vh + '|consent_approve';
                            }
                            return 'REVEAL';
                        }

                        // 2. Hesap Seçici (AccountChooser) ekranındaysak (yalnızca birden fazla hesap satırı varsa veya AccountChooser URL'sindeysek)
                        var passInput = document.querySelector('input[type="password"]');
                        var isPassVisible = passInput && passInput.offsetParent !== null;
                        var emailInput = document.querySelector('input[type="email"], #identifierId');
                        var isEmailVisible = emailInput && emailInput.offsetParent !== null;

                        if (targetEmail && !isPassVisible && !isEmailVisible && !window._fmAccountTapped) {
                            var items = document.querySelectorAll('li [data-identifier], [role="link"][data-identifier], [data-identifier], [data-email]');
                            if (items.length > 1 || href.indexOf('accountchooser') !== -1 || href.indexOf('oauthchooseaccount') !== -1) {
                                for (var k = 0; k < items.length; k++) {
                                    var idVal = (items[k].getAttribute('data-identifier') || items[k].getAttribute('data-email') || '').toLowerCase();
                                    if (idVal === targetEmail) {
                                        window._fmAccountTapped = true;
                                        items[k].scrollIntoView({block: 'center'});
                                        var r = items[k].getBoundingClientRect();
                                        try { items[k].click(); } catch (e) {}
                                        if (r.width > 0 && r.height > 0) {
                                            return 'TAP|' + (r.left + r.width / 2) + '|' + (r.top + r.height / 2) + '|' + vw + '|' + vh + '|account_select';
                                        }
                                    }
                                }
                            }
                        }

                        // 3. E-posta Giriş kutusu (#identifierId) görünürse ve şifre alanı yoksa "Sonraki" (#identifierNext) butonuna bas
                        if (isEmailVisible && !isPassVisible && !window._fmIdentifierTapped) {
                            var curVal = (emailInput.value || '').trim();
                            if (!curVal && targetEmail) {
                                emailInput.value = targetEmail;
                                emailInput.dispatchEvent(new Event('input', { bubbles: true }));
                                curVal = targetEmail;
                            }
                            if (curVal.length > 3) {
                                var nextContainer = document.getElementById('identifierNext');
                                var nextBtn = nextContainer ? (nextContainer.querySelector('button') || nextContainer) : null;
                                if (nextBtn) {
                                    window._fmIdentifierTapped = true;
                                    nextBtn.scrollIntoView({block: 'center'});
                                    var nr = nextBtn.getBoundingClientRect();
                                    try { nextBtn.click(); } catch (e) {}
                                    if (nr.width > 0 && nr.height > 0) {
                                        return 'TAP|' + (nr.left + nr.width / 2) + '|' + (nr.top + nr.height / 2) + '|' + vw + '|' + vh + '|identifier_next';
                                    }
                                }
                            }
                        }

                        return 'REVEAL';
                    } catch (err) {
                        return 'REVEAL';
                    }
                })();
            """.trimIndent()

            fun evaluateAndProcessOAuthPage(view: WebView?) {
                val wv = view ?: webViewRef ?: return
                if (codeHandled) return
                wv.evaluateJavascript(sanitizeAndAutomateJs) { rawResult ->
                    val res = unescapeEvaluateJsString(rawResult)
                    if (res.startsWith("TAP|")) {
                        val parts = res.split("|")
                        val cx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                        val cy = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                        val vw = parts.getOrNull(3)?.toFloatOrNull() ?: 360f
                        val vh = parts.getOrNull(4)?.toFloatOrNull() ?: 640f
                        dispatchViewportTapOnWebView(cx, cy, vw, vh)
                        // Otomatik tıklamadan 650ms sonra hala onay kodu gelmediyse temiz ekranı mutlaka göster
                        mainHandler.postDelayed({
                            revealCleanWebViewOnUiThread()
                        }, 650L)
                    } else {
                        revealCleanWebViewOnUiThread()
                    }
                }
            }

            val webView = WebView(this@CloudAccountsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                alpha = 0f
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val u = url ?: ""
                        if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) {
                            val code = Uri.parse(u).getQueryParameter("code")
                            if (!code.isNullOrBlank()) {
                                handleAuthCodeReceived(code)
                            }
                            return
                        }
                        // MergeSession tamamlanıp myaccount.google.com veya ManageAccount'a yönlendiyse doğrudan OAuth sayfasına geç
                        if (u.contains("myaccount.google.com") || u.contains("accounts.google.com/ManageAccount") || u.contains("accounts.google.com/b/")) {
                            view?.loadUrl(authUrl)
                        }
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        val u = url ?: ""
                        if (!u.contains("MergeSession") && !u.contains("CheckCookie")) {
                            evaluateAndProcessOAuthPage(view)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val u = url ?: ""
                        if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) {
                            val code = Uri.parse(u).getQueryParameter("code")
                            if (!code.isNullOrBlank()) {
                                handleAuthCodeReceived(code)
                            }
                            return
                        }
                        if (u.contains("myaccount.google.com") || u.contains("accounts.google.com/ManageAccount")) {
                            view?.loadUrl(authUrl)
                            return
                        }
                        if (!u.contains("MergeSession") && !u.contains("CheckCookie")) {
                            mainHandler.postDelayed({
                                evaluateAndProcessOAuthPage(view)
                            }, 180L)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString() ?: return false
                        if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) {
                            val code = Uri.parse(u).getQueryParameter("code")
                            if (!code.isNullOrBlank()) {
                                handleAuthCodeReceived(code)
                            }
                            return true
                        }
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val u = url ?: return false
                        if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) {
                            val code = Uri.parse(u).getQueryParameter("code")
                            if (!code.isNullOrBlank()) {
                                handleAuthCodeReceived(code)
                            }
                            return true
                        }
                        return false
                    }
                }
            }
            webViewRef = webView

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            mainColumn.addView(topHelperBar)
            mainColumn.addView(webView)
            rootContainer.addView(mainColumn)
            rootContainer.addView(loadingOverlay)

            authDialog = AlertDialog.Builder(this@CloudAccountsActivity)
                .setView(rootContainer)
                .setOnCancelListener {
                    stopLoopbackServer()
                }
                .create()

            btnCancelTop.setOnClickListener {
                stopLoopbackServer()
                authDialog?.dismiss()
            }

            authDialog.show()
            authDialog.window?.let { win ->
                win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                win.setLayout(
                    (dm.widthPixels * 0.92f).toInt(),
                    dialogHeightPx
                )
            }

            webView.loadUrl(initialLoadUrl)

            // Kesin emniyet zamanlayıcısı: 1.5 saniye sonunda hala yönlendirme tamamlanmadıysa
            // temizlenmiş WebView'u mutlaka göster (asla "Google Drive hesabına bağlanılıyor..." ekranında takılı kalmaz!)
            mainHandler.postDelayed({
                if (!codeHandled && authDialog?.isShowing == true) {
                    val curUrl = webView.url ?: ""
                    if (curUrl.isBlank() || curUrl.contains("MergeSession")) {
                        webView.loadUrl(authUrl)
                    } else {
                        evaluateAndProcessOAuthPage(webView)
                    }
                    revealCleanWebViewOnUiThread()
                }
            }, 1500L)

            loopbackServerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    val pathPart = requestLine.split(" ").getOrNull(1) ?: ""
                    val code = Uri.parse("http://127.0.0.1$pathPart").getQueryParameter("code")

                    val html = """
                        <html><head><meta charset="utf-8"/></head>
                        <body style="font-family:sans-serif;text-align:center;padding:40px;background:#121212;color:#ffffff;">
                        <h2 style="color:#26A69A;">✓ Google Drive Bağlandı!</h2>
                        <p>Dosya Yöneticisi + uygulamasına yönlendiriliyorsunuz...</p>
                        <script>window.close();</script>
                        </body></html>
                    """.trimIndent()
                    val responseBytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(header.toByteArray(Charsets.UTF_8))
                        write(responseBytes)
                        flush()
                    }
                    client.close()

                    if (!code.isNullOrBlank()) {
                        handleAuthCodeReceived(code)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun unescapeEvaluateJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }

    private fun stopLoopbackServer() {
        loopbackServerJob?.cancel()
        loopbackServerJob = null
        try {
            activeLoopbackSocket?.close()
        } catch (_: Exception) {
        }
        activeLoopbackSocket = null
    }

    private fun showAddCloudCredentialsDialog(provider: CloudProviderType) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }

        val etEmail = EditText(this).apply {
            hint = "${provider.displayName} Hesap E-postası / Kullanıcı Adı"
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
                "OAuth2 Erişim Anahtarı (Access Token)"
            }
            setSingleLine()
        }

        layout.addView(etEmail)
        layout.addView(etServer)
        layout.addView(etToken)

        AlertDialog.Builder(this)
            .setTitle("${provider.displayName} Hesabı Ekle")
            .setView(layout)
            .setPositiveButton("TAMAM") { _, _ ->
                val email = etEmail.text?.toString()?.trim().orEmpty()
                val token = etToken.text?.toString()?.trim().orEmpty()
                val server = etServer.text?.toString()?.trim().orEmpty()
                if (email.isNotEmpty()) {
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
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun showAccountOptionsDialog(account: CloudAccount) {
        val items = arrayOf(
            "Hesabı Aç (${account.emailOrName})",
            "Play Hizmetleri'nden Farklı Hesap Seç",
            "Oturumu Yenile / Tekrar Bağla",
            "Hesabı Kaldır"
        )
        AlertDialog.Builder(this)
            .setTitle("${account.provider.displayName} • ${account.emailOrName}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openCloudAccountExplorer(account)
                    1 -> launchNativeGoogleAccountPicker(account.emailOrName)
                    2 -> {
                        when (account.provider) {
                            CloudProviderType.GOOGLE_DRIVE -> startGoogleDriveOAuthFlow(existingAccount = account, autoOpenWhenConnected = true)
                            CloudProviderType.MEGA -> showAddMegaAccountDialog(existingAccount = account)
                            else -> showAddCloudCredentialsDialog(account.provider)
                        }
                    }
                    3 -> {
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
        if (searchUiState == 0) {
            binding.tvCloudToolbarTitle.text = "Bulut"
        }
    }

    private fun applyCurrentTheme() {
        ThemeManager.applyWindowColors(this, binding.cloudRoot, if (searchUiState == 2) null else binding.toolbarCloud)
        if (searchUiState == 2) {
            binding.toolbarCloud.setBackgroundColor(Color.parseColor("#3F51B5"))
        }
        binding.cardUnifiedCloudSearch.setBackgroundColor(ThemeManager.getSurfaceColor(this))
        binding.tvAddCloudFooter.setTextColor(ThemeManager.getPrimaryTextColor(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchUiState != 0) {
            closeUnifiedSearch()
            return
        }
        super.onBackPressed()
    }
}
