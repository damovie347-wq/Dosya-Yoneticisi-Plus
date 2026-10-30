package com.alphainventor.filemanager.ui

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
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
import com.google.android.gms.common.AccountPicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Bulut Ekranı (Google Drive + MEGA + Çoklu Bulut):
 * 1) "+ Bir bulut konumu ekle -> Google Drive" tıklandığında orijinal APK'daki (Lax/q7/a;->c() & MainActivity$s)
 *    gibi doğrudan sistem AccountPicker penceresini açar.
 * 2) Kullanıcı cihazdaki Google hesabını seçtiğinde:
 *    - Asla tarayıcıya veya manuel e-posta/şifre WebView ekranına düşmez!
 *    - Doğrudan GoogleAuthUtil.getToken("oauth2: https://www.googleapis.com/auth/drive") çağrılır.
 *    - İlk seferde izin gerekiyorsa yerel Google Play Hizmetleri izin penceresi (UserRecoverableAuthException.intent) açılır;
 *      izin verilince hesap eklenir ve Drive içindeki fotoğraf/videolar görüntülenir.
 * 3) MEGA (mega.nz) hesap ekleme:
 *    - Doğrudan resmi MEGA JSON-RPC API + düzeltilmiş RSA csid çözümü ile çalışır; çalışmayan web sayfasına yönlendirmez.
 */
class CloudAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var accountsAdapter: CloudAccountsAdapter
    private lateinit var unifiedResultsAdapter: FileListAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFilter: CloudSearchFilter = CloudSearchFilter.ALL
    private var unifiedSearchJob: Job? = null
    private var pendingGoogleAccountForAuth: CloudAccount? = null

    private var searchUiState: Int = 0
    private var searchHistoryContainer: LinearLayout? = null

    /**
     * Orijinal APK'daki (MainActivity->onActivityResult requestCode=3) AccountPicker sonucu:
     * Seçilen "authAccount" (AccountManager.KEY_ACCOUNT_NAME) değerini alır ve doğrudan yerel OAuth akışına sokar.
     */
    private val googleAccountPickerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
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
     * Orijinal APK'daki (MainActivity->onActivityResult requestCode=11) yerel Google Drive izin sonucu:
     * Kullanıcı "İzin ver" dediğinde GoogleAuthUtil üzerinden token'ı alır, kotayı günceller ve Drive gezginini açar.
     */
    private val googlePlayConsentLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGooglePlayConsentResult(result.resultCode)
    }

    private fun handleGooglePlayConsentResult(resultCode: Int) {
        val acc = pendingGoogleAccountForAuth
        pendingGoogleAccountForAuth = null
        if (resultCode == Activity.RESULT_OK && acc != null) {
            lifecycleScope.launch {
                val token = CloudManager.resolveGoogleDriveToken(
                    this@CloudAccountsActivity,
                    acc,
                    forceRefresh = false
                )
                if (token.isNotBlank()) {
                    val updated = CloudManager.fetchGoogleDriveQuota(
                        this@CloudAccountsActivity,
                        acc.copy(accessToken = token)
                    )
                    refreshAccountsList()
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "✓ Google Drive bağlandı: ${updated.emailOrName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchExplorerForAccount(
                        CloudManager.getAccounts(this@CloudAccountsActivity).firstOrNull { it.id == updated.id } ?: updated
                    )
                } else {
                    val nextRecovery = CloudManager.pendingRecoveryIntent
                    if (nextRecovery != null) {
                        pendingGoogleAccountForAuth = acc
                        googlePlayConsentLauncher.launch(nextRecovery)
                    } else {
                        Toast.makeText(
                            this@CloudAccountsActivity,
                            CloudManager.lastCloudErrorMessage ?: "Google Drive erişim anahtarı alınamadı.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else if (acc != null) {
            Toast.makeText(this, "Google Drive erişim izni verilmedi.", Toast.LENGTH_SHORT).show()
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
                popup.menu.add(0, 1, 0, "Google Drive Hesabı Seç (Cihazdaki Hesaplar)")
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

    /**
     * Orijinal APK'daki (Lax/q7/a;->c() & Lax/T5/a;->a()) sistem AccountPicker çağrısı.
     * Cihazdaki Google hesaplarını listeler; tarayıcıya veya e-posta/şifre ekranına düşmez.
     */
    private fun launchNativeGoogleAccountPicker(preselectedEmail: String? = null) {
        val selectedAcc = if (!preselectedEmail.isNullOrBlank()) {
            android.accounts.Account(preselectedEmail, "com.google")
        } else null

        try {
            @Suppress("DEPRECATION")
            val gmsChooseIntent = AccountPicker.newChooseAccountIntent(
                selectedAcc,
                null,
                arrayOf("com.google"),
                true,
                null,
                null,
                null,
                null
            )
            googleAccountPickerLauncher.launch(gmsChooseIntent)
            return
        } catch (_: Throwable) {
        }

        try {
            @Suppress("DEPRECATION")
            val systemChooseIntent = AccountManager.newChooseAccountIntent(
                selectedAcc,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
            googleAccountPickerLauncher.launch(systemChooseIntent)
            return
        } catch (_: Throwable) {
        }

        try {
            val rawIntent = Intent("com.google.android.gms.common.account.CHOOSE_ACCOUNT").apply {
                setPackage("com.google.android.gms")
                putExtra("allowableAccountTypes", arrayOf("com.google"))
                putExtra("alwaysPromptForAccount", true)
                if (selectedAcc != null) {
                    putExtra("selectedAccount", selectedAcc)
                }
            }
            googleAccountPickerLauncher.launch(rawIntent)
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "Cihaz hesap seçicisi açılamadı: ${e.localizedMessage}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * AccountPicker'dan hesap seçildiğinde:
     * 1) Hesabı kaydeder.
     * 2) Doğrudan CloudManager.resolveGoogleDriveToken ile cihazdaki hesaptan OAuth2 Drive token'ı ister.
     * 3) İlk seferde izin gerekiyorsa (pendingRecoveryIntent != null), yerel Play Services izin penceresini açar.
     * 4) İzin verildiyse doğrudan Drive gezginini açar.
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
            val token = CloudManager.resolveGoogleDriveToken(
                this@CloudAccountsActivity,
                savedAccount,
                forceRefresh = false
            )
            if (token.isNotBlank()) {
                val updated = CloudManager.fetchGoogleDriveQuota(
                    this@CloudAccountsActivity,
                    savedAccount.copy(accessToken = token)
                )
                refreshAccountsList()
                Toast.makeText(
                    this@CloudAccountsActivity,
                    "✓ Google Drive bağlandı: ${updated.emailOrName}",
                    Toast.LENGTH_SHORT
                ).show()
                launchExplorerForAccount(updated)
            } else {
                val recovery = CloudManager.pendingRecoveryIntent
                if (recovery != null) {
                    pendingGoogleAccountForAuth = savedAccount
                    googlePlayConsentLauncher.launch(recovery)
                } else {
                    // Cihaz izin penceresi FileExplorerActivity içinde de tetiklenebilir
                    launchExplorerForAccount(savedAccount)
                }
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
            val latestAcc = CloudManager.getAccounts(this@CloudAccountsActivity)
                .firstOrNull { it.id == account.id } ?: account

            if (latestAcc.provider == CloudProviderType.GOOGLE_DRIVE) {
                val token = CloudManager.resolveGoogleDriveToken(
                    this@CloudAccountsActivity,
                    latestAcc,
                    forceRefresh = false
                )
                val recovery = CloudManager.pendingRecoveryIntent
                if (recovery != null) {
                    pendingGoogleAccountForAuth = latestAcc
                    googlePlayConsentLauncher.launch(recovery)
                    return@launch
                }
                if (token.isNotBlank()) {
                    val updated = CloudManager.getAccounts(this@CloudAccountsActivity)
                        .firstOrNull { it.id == latestAcc.id } ?: latestAcc.copy(accessToken = token)
                    launchExplorerForAccount(updated)
                    return@launch
                }
            }

            val updated = CloudManager.getAccounts(this@CloudAccountsActivity)
                .firstOrNull { it.id == latestAcc.id } ?: latestAcc
            launchExplorerForAccount(updated)
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
            if (account?.provider == CloudProviderType.MEGA) {
                Toast.makeText(this@CloudAccountsActivity, "MEGA dosyası hazırlanıyor...", Toast.LENGTH_SHORT).show()
                val localFile = CloudManager.downloadMegaFileToCache(this@CloudAccountsActivity, account, item)
                if (localFile != null && localFile.exists()) {
                    val localItem = item.copy(path = localFile.absolutePath, downloadUrl = localFile.absolutePath)
                    when {
                        localItem.isVideo -> startActivity(Intent(this@CloudAccountsActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", localFile.absolutePath)
                            putExtra("VIDEO_TITLE", item.name)
                        })
                        localItem.isAudio -> startActivity(Intent(this@CloudAccountsActivity, MusicPlayerActivity::class.java).apply {
                            putExtra("AUDIO_URL", localFile.absolutePath)
                            putExtra("AUDIO_TITLE", item.name)
                        })
                        localItem.isImage -> startActivity(Intent(this@CloudAccountsActivity, ImageViewerActivity::class.java).apply {
                            putExtra("IMAGE_PATH", localFile.absolutePath)
                            putExtra("IMAGE_TITLE", item.name)
                        })
                        else -> LocalFileRepository.openWithExternalChooser(this@CloudAccountsActivity, localItem, "")
                    }
                } else {
                    Toast.makeText(this@CloudAccountsActivity, "MEGA dosyası indirilemedi.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

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
     * Doğrudan E-posta + Şifre (ve gerekirse 2FA kodu) ile resmi MEGA API üzerinden bağlanır.
     * Çalışmayan bir web sitesine yönlendirmez.
     */
    private fun showAddMegaAccountDialog(existingAccount: CloudAccount? = null) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * density).toInt())
        }

        val tvInfo = TextView(this).apply {
            text = "MEGA hesabınızın e-posta ve şifresini girerek doğrudan bağlanabilirsiniz."
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

        val etMfa = EditText(this).apply {
            hint = "2FA Doğrulama Kodu (6 Haneli Kod)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            visibility = View.GONE
        }

        val tvError = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#E53935"))
            setPadding(0, (8 * density).toInt(), 0, 0)
            visibility = View.GONE
        }

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        val tvProgress = TextView(this).apply {
            text = "MEGA hesabına bağlanılıyor..."
            textSize = 13f
            setTextColor(ThemeManager.getSecondaryTextColor(this@CloudAccountsActivity))
            setPadding((10 * density).toInt(), 0, 0, 0)
        }
        progressRow.addView(pb)
        progressRow.addView(tvProgress)

        layout.addView(tvInfo)
        layout.addView(etEmail)
        layout.addView(etPassword)
        layout.addView(etMfa)
        layout.addView(tvError)
        layout.addView(progressRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle("MEGA Hesabı Bağla")
            .setView(layout)
            .setPositiveButton("BAĞLAN", null)
            .setNegativeButton("İPTAL", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString().orEmpty()
            val mfaPin = etMfa.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

            if (email.isBlank() || password.isBlank()) {
                tvError.text = "Lütfen MEGA e-posta ve şifrenizi girin."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvError.visibility = View.GONE
            progressRow.visibility = View.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

            lifecycleScope.launch {
                val saved = CloudManager.authenticateMegaAccount(
                    context = this@CloudAccountsActivity,
                    email = email,
                    password = password,
                    existingAccount = existingAccount,
                    mfaPin = mfaPin
                )
                progressRow.visibility = View.GONE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true

                if (saved != null) {
                    dialog.dismiss()
                    refreshAccountsList()
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "✓ MEGA bağlandı: ${saved.emailOrName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchExplorerForAccount(saved)
                } else {
                    if (CloudManager.lastMegaErrorCode == -26) {
                        etMfa.visibility = View.VISIBLE
                        etMfa.requestFocus()
                    }
                    tvError.text = CloudManager.lastCloudErrorMessage
                        ?: "MEGA hesabına giriş yapılamadı. E-posta ve şifrenizi kontrol edin."
                    tvError.visibility = View.VISIBLE
                }
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
            "Cihazdan Farklı Google Hesabı Seç",
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
                            CloudProviderType.GOOGLE_DRIVE -> onGoogleAccountPickedFromPlayServices(account.emailOrName)
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
