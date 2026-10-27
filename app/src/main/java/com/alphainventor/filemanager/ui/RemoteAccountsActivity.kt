package com.alphainventor.filemanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
     * Videodaki (01:30 - 01:31) "Uzak Konum Ekle" Diyaloğu:
     * Yerel Ağ, SMB, FTP, SFTP, WebDAV
     */
    private fun showAddRemoteProtocolDialog() {
        val protocols = arrayOf("Yerel Ağ", "SMB", "FTP", "SFTP", "WebDAV")
        AlertDialog.Builder(this)
            .setTitle("Uzak Konum Ekle")
            .setItems(protocols) { _, which ->
                val proto = RemoteProtocolType.values()[which]
                showServerConfigDialog(proto)
            }
            .show()
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
            .setPositiveButton("Tamam") { _, _ ->
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
            .setNegativeButton("İptal", null)
            .show()
    }
}
