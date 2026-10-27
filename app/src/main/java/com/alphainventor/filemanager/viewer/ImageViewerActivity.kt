package com.alphainventor.filemanager.viewer

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityImageViewerBinding
import com.alphainventor.filemanager.model.FileItem
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.data?.path ?: (intent.getStringExtra("IMAGE_PATH") ?: "")
        val title = intent.getStringExtra("IMAGE_TITLE") ?: path.substringAfterLast('/')
        binding.tvImageTitle.text = title

        val file = File(path)
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            binding.ivFullImage.setImageBitmap(bmp)
        }

        binding.btnImageBack.setOnClickListener { finish() }
        binding.btnImageShare.setOnClickListener {
            if (file.exists()) {
                LocalFileRepository.openWithExternalChooser(
                    this,
                    FileItem(file.absolutePath, file.name, file.absolutePath, false, file.length(), file.lastModified(), mimeType = "image/*")
                )
            }
        }
    }
}
