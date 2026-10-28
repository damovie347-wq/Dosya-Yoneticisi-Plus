package com.alphainventor.filemanager.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.databinding.ActivityRemoteAccountsBinding
import com.alphainventor.filemanager.model.RemoteAccount
import com.alphainventor.filemanager.model.RemoteProtocolType
import com.alphainventor.filemanager.remote.RemoteManager
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.ui.adapter.RemoteAccountsAdapter
import java.util.UUID

class RemoteAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteAccountsBinding
    private lateinit var adapter: RemoteAccountsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.remoteRoot, binding.remoteToolbar)
        binding.btnAddRemoteLocation.setTextColor(ThemeManager.getPrimaryTextColor(this))

        adapter = RemoteAccountsAdapter(
            onClick = { account ->
                startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                    putExtra("MODE", "REMOTE")
                    putExtra("REMOTE_ACCOUNT", account)
                })
            },
            onLongClick = { account ->
                AlertDialog.Builder(this)
                    .setTitle("${account.host} (${account.protocol.displayName})")
                    .setItems(arrayOf("Bağlantıyı Aç", "Sil")) { _, which ->
                        if (which == 0) {
                            startActivity(Intent(this, FileExplorerActivity::class.java).apply {
                                putExtra("MODE", "REMOTE")
                                putExtra("REMOTE_ACCOUNT", account)
                            })
                        } else {
                            RemoteManager.removeRemoteAccount(this, account.id)
                            refreshList()
                        }
                    }
                    .show()
            }
        )
        binding.rvRemoteAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvRemoteAccounts.adapter = adapter

        binding.btnRemoteBack.setOnClickListener { finish() }
        binding.btnAddRemoteLocation.setOnClickListener {
            showAddRemoteProtocolDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(RemoteManager.getRemoteAccounts(this))
    }

    /**
     * Video 3'teki (00:33 - 00:37 Mavi Çizim İçi) Orijinal "Uzak Konum Ekle" İkonlu Diyaloğu:
     * - [📁 LAN] Yerel Ağ
     * - [📁 SMB] SMB
     * - [📁 FTP] FTP
     * - [📁 SFTP] SFTP
     * - [📁 WebDAV] WebDAV
     */
    private fun showAddRemoteProtocolDialog() {
        val density = resources.displayMetrics.density
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padV = (8 * density).toInt()
            setPadding(0, padV, 0, padV)
            setBackgroundColor(ThemeManager.getCardColor(this@RemoteAccountsActivity))
        }

        val items = listOf(
            Triple(RemoteProtocolType.LAN, "Yerel Ağ", R.drawable.icon_folder_lan),
            Triple(RemoteProtocolType.SMB, "SMB", R.drawable.icon_folder_smb),
            Triple(RemoteProtocolType.FTP, "FTP", R.drawable.icon_folder_ftp),
            Triple(RemoteProtocolType.SFTP, "SFTP", R.drawable.icon_folder_sftp),
            Triple(RemoteProtocolType.WEBDAV, "WebDAV", R.drawable.icon_folder_webdav)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Uzak Konum Ekle")
            .setView(container)
            .create()

        for ((proto, label, iconRes) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (52 * density).toInt()
                )
                val pH = (20 * density).toInt()
                setPadding(pH, 0, pH, 0)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    showServerConfigDialog(proto)
                }
            }

            val iv = ImageView(this).apply {
                val sz = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (16 * density).toInt()
                }
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val tv = TextView(this).apply {
                text = label
                textSize = 15.5f
                setTextColor(primaryColor)
            }

            row.addView(iv)
            row.addView(tv)
            container.addView(row)
        }

        dialog.show()
    }

    private fun showServerConfigDialog(protocol: RemoteProtocolType) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etHost = EditText(this).apply {
            hint = "Ana Bilgisayar / IP (örn: 192.168.1.94)"
            setSingleLine()
        }
        val etPort = EditText(this).apply {
            hint = "Bağlantı Noktası"
            setText(protocol.defaultPort.toString())
            setSingleLine()
        }
        val etUser = EditText(this).apply {
            hint = "Kullanıcı Adı (örn: pc veya daam)"
            setSingleLine()
        }
        val etPass = EditText(this).apply {
            hint = "Şifre"
            setSingleLine()
        }
        val cbAnon = CheckBox(this).apply {
            text = "Anonim Giriş"
        }
        layout.addView(etHost)
        layout.addView(etPort)
        layout.addView(etUser)
        layout.addView(etPass)
        layout.addView(cbAnon)

        AlertDialog.Builder(this)
            .setTitle("${protocol.displayName} Konumu Ekle")
            .setView(layout)
            .setPositiveButton("TAMAM") { _, _ ->
                val host = etHost.text.toString().trim().ifEmpty { "192.168.1.1" }
                val port = etPort.text.toString().toIntOrNull() ?: protocol.defaultPort
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()
                RemoteManager.addRemoteAccount(
                    this,
                    RemoteAccount(
                        id = UUID.randomUUID().toString(),
                        protocol = protocol,
                        host = host,
                        port = port,
                        displayName = user.ifEmpty { "pc" },
                        username = user,
                        password = pass,
                        anonymous = cbAnon.isChecked
                    )
                )
                refreshList()
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }
}
