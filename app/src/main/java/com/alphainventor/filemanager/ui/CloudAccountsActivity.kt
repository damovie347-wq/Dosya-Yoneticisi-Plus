package com.alphainventor.filemanager.ui

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.alphainventor.filemanager.viewer.MusicPlayerActivity
import com.alphainventor.filemanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.UUID

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bulut Ekranı (Google Play Hizmetleri Hesap Seçici + Mavi Barlı Toplu Bulut Arama):
 * 1) "+ Bir bulut konumu ekle -> Google Drive" tıklandığında orijinal APK'daki (r$c->n) gibi doğrudan
 *    Google Play Hizmetleri Sistem Hesap Seçicisini (com.google.android.gms.common.account.CHOOSE_ACCOUNT) açar!
 * 2) Cihazda açık olan Google hesaplarından biri seçildiğinde hesabı anında Bulut listesine ve sol çekmeceye ekler.
 * 3) Play Hizmetleri (GoogleAuthUtil) token verir veya UserRecoverableAuthException izin penceresini açar;
 *    eğer özel GitHub derleme imzası nedeniyle Play Hizmetleri token vermezse, seçilen e-posta (login_hint) ile
 *    tek dokunuşlu OAuth onayını tamamlar.
 * 4) Sağ üstteki BÜYÜTEÇ (🔍) ikonuna basınca tüm bağlı Google Drive ve bulut hesaplarında paralel arama yapar.
 */
class CloudAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var accountsAdapter: CloudAccountsAdapter
    private lateinit var unifiedResultsAdapter: FileListAdapter

    private var activeFilter: CloudSearchFilter = CloudSearchFilter.ALL
    private var unifiedSearchJob: Job? = null
    private var loopbackServerJob: Job? = null
    private var activeLoopbackSocket: ServerSocket? = null
    private var pendingGoogleAccountForAuth: CloudAccount? = null

    private var searchUiState: Int = 0
    private var searchHistoryContainer: LinearLayout? = null

    /**
     * Orijinal Google Play Hizmetleri Hesap Seçicisi (com.google.android.gms.common.account.CHOOSE_ACCOUNT)
     * Cihazda oturum açılmış tüm Google hesaplarını listeler.
     */
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

    /**
     * Google Play Hizmetleri OAuth İzin Penceresi (UserRecoverableAuthException)
     */
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
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        refreshAccountsList()
    }

    override fun onDestroy() {
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
                popup.menu.add(0, 2, 1, if (accountsAdapter.isEditMode) "Düzenleme Modunu Kapat" else "Hesapları Düzenle / Kaldır")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> launchNativeGoogleAccountPicker()
                        2 -> {
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

    /**
     * Orijinal APK'daki (Lcom/alphainventor/filemanager/file/r$c;->n) Google Play Hizmetleri Hesap Seçicisi:
     * Telefonda açık olan tüm Google hesaplarını yerel sistem penceresinde açar!
     */
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
            startGoogleDriveOAuthFlow(existingAccount = null)
        }
    }

    /**
     * Kullanıcı Play Hizmetleri penceresinden bir Google hesabı (örn: damovie355@gmail.com) seçtiğinde:
     * 1) Hesabı anında Bulut ekranına kaydeder (orijinal r$c->k davranışı).
     * 2) Play Hizmetleri üzerinden (GoogleAuthUtil) token almayı dener.
     * 3) Eğer Play Hizmetleri UserRecoverableAuthException döndürürse orijinal izin penceresini açar.
     * 4) Eğer özel GitHub imzası nedeniyle Play Hizmetleri UNREGISTERED_ON_API_CONSOLE döndürürse,
     *    seçilen e-posta adresiyle (login_hint) tek dokunuşlu OAuth onayını başlatır.
     */
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

        lifecycleScope.launch {
            val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, savedAccount, forceRefresh = false)
            val recovery = CloudManager.pendingRecoveryIntent
            if (recovery != null) {
                pendingGoogleAccountForAuth = savedAccount
                googlePlayConsentLauncher.launch(recovery)
                return@launch
            }
            if (token.isNotBlank()) {
                CloudManager.fetchGoogleDriveQuota(this@CloudAccountsActivity, savedAccount.copy(accessToken = token))
                refreshAccountsList()
                Toast.makeText(this@CloudAccountsActivity, "✓ Google Drive eklendi: $email", Toast.LENGTH_SHORT).show()
            } else if (CloudManager.needsWebOAuthFallbackForAccount != null) {
                startGoogleDriveOAuthFlow(existingAccount = savedAccount)
            }
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

        binding.toolbarCloud.setBackgroundColor(ThemeManager.getToolbarColor(this))
        binding.btnCloudBack.setImageResource(R.drawable.ic_back_white)
        binding.tvCloudToolbarTitle.text = "Bulut"

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUnifiedCloudSearch.windowToken, 0)
    }

    private fun renderSearchHistoryRows() {
        if (searchHistoryContainer == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            binding.cardUnifiedCloudSearch.addView(container)
            searchHistoryContainer = container
        }
        val container = searchHistoryContainer ?: return
        container.removeAllViews()

        val history = CloudManager.getSearchHistory(this)
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)

        for (itemQuery in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (50 * density).toInt()
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }

            val ivClock = ImageView(this).apply {
                val sz = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = (8 * density).toInt()
                    marginEnd = (16 * density).toInt()
                }
                setImageResource(R.drawable.ic_history_clock)
                setColorFilter(secondaryColor)
            }

            val tvQuery = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = itemQuery
                textSize = 15.5f
                setTextColor(primaryColor)
                isSingleLine = true
            }

            val btnDelete = ImageButton(this).apply {
                val sz = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setImageResource(R.drawable.ic_close_white)
                setColorFilter(secondaryColor)
                val p = (10 * density).toInt()
                setPadding(p, p, p, p)
                setOnClickListener {
                    CloudManager.removeSearchHistory(this@CloudAccountsActivity, itemQuery)
                    renderSearchHistoryRows()
                }
            }

            row.addView(ivClock)
            row.addView(tvQuery)
            row.addView(btnDelete)
            row.setOnClickListener {
                binding.etUnifiedCloudSearch.setText(itemQuery)
                binding.etUnifiedCloudSearch.setSelection(itemQuery.length)
                executeUnifiedMultiCloudSearch()
            }
            container.addView(row)
        }
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
        binding.rvCloudAccounts.layoutManager = GridLayoutManager(this, 3)
        binding.rvCloudAccounts.adapter = accountsAdapter
    }

    private fun openCloudAccountExplorer(account: CloudAccount) {
        lifecycleScope.launch {
            if (account.provider == CloudProviderType.GOOGLE_DRIVE) {
                val token = CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account, forceRefresh = false)
                val recovery = CloudManager.pendingRecoveryIntent
                if (recovery != null) {
                    pendingGoogleAccountForAuth = account
                    googlePlayConsentLauncher.launch(recovery)
                    return@launch
                }
                if (token.isBlank() || CloudManager.needsWebOAuthFallbackForAccount != null) {
                    startGoogleDriveOAuthFlow(existingAccount = account)
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (searchUiState == 1) {
                    renderSearchHistoryRows()
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
        if (binding.etUnifiedCloudSearch.text?.isNotBlank() == true || searchUiState == 2) {
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
        unifiedResultsAdapter.highlightQuery = query
        switchToBlueResultsState(query)

        binding.tvSearchResultsHeader.text = "\"$query\"  (Taranıyor...)"
        unifiedResultsAdapter.submitList(emptyList())

        unifiedSearchJob = lifecycleScope.launch {
            val results = CloudManager.searchAcrossAllAccounts(
                context = this@CloudAccountsActivity,
                query = query,
                filter = activeFilter,
                onProgress = { progress ->
                    runOnUiThread {
                        unifiedResultsAdapter.highlightQuery = query
                        binding.tvSearchResultsHeader.text =
                            "\"$query\"  (${progress.completedAccounts}/${progress.totalAccounts} hesap • ${progress.partialResults.size} sonuç)"
                        unifiedResultsAdapter.submitList(progress.partialResults)
                    }
                }
            )

            unifiedResultsAdapter.highlightQuery = query
            binding.tvSearchResultsHeader.text = "\"$query\""
            unifiedResultsAdapter.submitList(results)
            if (results.isEmpty()) {
                Toast.makeText(this@CloudAccountsActivity, "\"$query\" için eşleşen dosya bulunamadı.", Toast.LENGTH_SHORT).show()
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
                            val token = if (account != null) CloudManager.resolveGoogleDriveToken(this@CloudAccountsActivity, account) else ""
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
                else -> {
                    LocalFileRepository.openWithExternalChooser(this@CloudAccountsActivity, item, activeToken)
                }
            }
        }
    }

    /**
     * Orijinal APK "Yeni bulut bağlantısı" 3 sütunlu seçim diyaloğu:
     * Google Drive tıklandığında DOĞRUDAN Play Hizmetleri Sistem Hesap Seçicisi açılır!
     */
    private fun showAddCloudProviderDialog() {
        val providers = listOf(
            CloudProviderType.GOOGLE_DRIVE to R.drawable.cloud_icon_googledrive,
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
                    if (type == CloudProviderType.GOOGLE_DRIVE) {
                        // Doğrudan cihazdaki Google Play Hizmetleri hesap seçicisini aç!
                        launchNativeGoogleAccountPicker()
                    } else {
                        showAddCloudCredentialsDialog(type)
                    }
                }
            }
            grid.addView(cell)
        }

        dialog.show()
    }

    /**
     * Play Hizmetleri'nin özel APK imzası nedeniyle doğrudan token vermediği durumlarda
     * seçilen Google hesabını (login_hint=email) tek dokunuşla yetkilendiren OAuth 2.0 akışı.
     * - Sayfa içindeki üçüncü taraf istemci adını ("rclone") otomatik olarak "Dosya Yöneticisi +" yapar.
     * - Kullanıcı cihaz tarayıcısında zaten oturum açmış olduğu için "Tarayıcıda Tek Tıkla Onayla" ile şifre yazmadan bağlanır.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startGoogleDriveOAuthFlow(existingAccount: CloudAccount?) {
        stopLoopbackServer()

        lifecycleScope.launch {
            val serverSocket = withContext(Dispatchers.IO) {
                try {
                    ServerSocket(53682, 5, InetAddress.getByName("127.0.0.1"))
                } catch (_: Exception) {
                    ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
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
                "&prompt=" + (if (emailHint != null) "consent" else "select_account+consent") +
                loginHintParam

            val density = resources.displayMetrics.density
            val rootLayout = LinearLayout(this@CloudAccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(ThemeManager.getCardColor(this@CloudAccountsActivity))
            }

            val topHelperBar = LinearLayout(this@CloudAccountsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (10 * density).toInt()
                setPadding(p, p / 2, p, p / 2)
                setBackgroundColor(Color.parseColor("#212121"))
            }

            val tvHelper = TextView(this@CloudAccountsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = if (!emailHint.isNullOrBlank()) "Google Drive: $emailHint" else "Google Drive Yetkilendirmesi"
                setTextColor(Color.WHITE)
                textSize = 13.5f
            }

            val btnOpenBrowser = Button(this@CloudAccountsActivity).apply {
                text = "Tarayıcıda Onayla"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#008578"))
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                    } catch (_: Exception) {
                    }
                }
            }

            topHelperBar.addView(tvHelper)
            topHelperBar.addView(btnOpenBrowser)

            var authDialog: AlertDialog? = null
            var codeHandled = false

            fun handleAuthCodeReceived(code: String) {
                if (codeHandled || code.isBlank()) return
                codeHandled = true
                runOnUiThread {
                    authDialog?.dismiss()
                    Toast.makeText(this@CloudAccountsActivity, "Google Drive hesabı bağlanıyor...", Toast.LENGTH_SHORT).show()
                }
                stopLoopbackServer()
                lifecycleScope.launch {
                    val saved = CloudManager.exchangeGoogleAuthCodeAndSaveAccount(
                        context = this@CloudAccountsActivity,
                        authCode = code,
                        redirectUri = redirectUri,
                        existingAccount = existingAccount
                    )
                    if (saved != null) {
                        refreshAccountsList()
                        Toast.makeText(
                            this@CloudAccountsActivity,
                            "✓ Google Drive bağlandı: ${saved.emailOrName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        launchExplorerForAccount(saved)
                    } else {
                        Toast.makeText(
                            this@CloudAccountsActivity,
                            CloudManager.lastCloudErrorMessage ?: "Google Drive bağlantısı tamamlanamadı.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            val brandFixJs = """
                (function() {
                    function renameAppLabel() {
                        if (!document.body) return;
                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        while ((node = walker.nextNode())) {
                            if (node.nodeValue && /rclone/i.test(node.nodeValue)) {
                                node.nodeValue = node.nodeValue.replace(/rclone/gi, 'Dosya Yöneticisi +');
                            }
                        }
                    }
                    renameAppLabel();
                    if (window.MutationObserver && document.body) {
                        new MutationObserver(renameAppLabel).observe(document.body, { childList: true, subtree: true });
                    }
                })();
            """.trimIndent()

            val webView = WebView(this@CloudAccountsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (460 * density).toInt()
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        view?.evaluateJavascript(brandFixJs, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(brandFixJs, null)
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
                loadUrl(authUrl)
            }

            rootLayout.addView(topHelperBar)
            rootLayout.addView(webView)

            authDialog = AlertDialog.Builder(this@CloudAccountsActivity)
                .setView(rootLayout)
                .setNegativeButton("İPTAL") { _, _ ->
                    stopLoopbackServer()
                }
                .setOnCancelListener {
                    stopLoopbackServer()
                }
                .create()

            authDialog.show()

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
                        <p>Dosya Yöneticisi + uygulamasına geri dönebilirsiniz.</p>
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
                        if (account.provider == CloudProviderType.GOOGLE_DRIVE) {
                            startGoogleDriveOAuthFlow(existingAccount = account)
                        } else {
                            showAddCloudCredentialsDialog(account.provider)
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
