package com.alphainventor.filemanager.viewer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alphainventor.filemanager.databinding.ActivityTextEditorBinding
import com.alphainventor.filemanager.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TextEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextEditorBinding
    private var filePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.editorRoot, binding.editorToolbar)
        binding.etTextContent.setTextColor(ThemeManager.getPrimaryTextColor(this))

        filePath = intent.data?.path ?: (intent.getStringExtra("FILE_PATH") ?: "")
        val title = intent.getStringExtra("FILE_TITLE") ?: filePath.substringAfterLast('/')
        binding.tvEditorTitle.text = title

        binding.btnEditorBack.setOnClickListener { finish() }
        binding.btnSaveText.setOnClickListener { saveFile() }

        loadFile()
    }

    private fun loadFile() {
        val f = File(filePath)
        if (!f.exists() || !f.isFile) return
        lifecycleScope.launch(Dispatchers.IO) {
            val content = try {
                // Belleği yormamak için maksimum 512 KB oku
                f.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(256 * 1024)
                    val read = reader.read(buf)
                    if (read > 0) String(buf, 0, read) else ""
                }
            } catch (_: Throwable) {
                ""
            }
            withContext(Dispatchers.Main) {
                binding.etTextContent.setText(content)
            }
        }
    }

    private fun saveFile() {
        val f = File(filePath)
        val text = binding.etTextContent.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                f.writeText(text, Charsets.UTF_8)
                true
            } catch (_: Throwable) {
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@TextEditorActivity,
                    if (ok) "Dosya kaydedildi." else "Kaydetme başarısız.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
