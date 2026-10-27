package com.alphainventor.filemanager.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityStorageAnalysisBinding
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.launch

class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalysisBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyWindowColors(this, binding.analysisRoot, binding.analysisToolbar)
        val cardColor = ThemeManager.getCardColor(this)
        binding.cardStorageBreakdown.setBackgroundColor(cardColor)
        binding.cardDuplicates.setBackgroundColor(cardColor)
        binding.cardLargeFiles.setBackgroundColor(cardColor)

        binding.btnAnalysisBack.setOnClickListener { finish() }
        binding.btnAnalysisRefresh.setOnClickListener { runAnalysis() }

        runAnalysis()
    }

    private fun runAnalysis() {
        binding.llAnalysisLoading.visibility = View.VISIBLE
        binding.svAnalysisContent.visibility = View.GONE

        lifecycleScope.launch {
            val report = LocalFileRepository.analyzeStorage()
            binding.llAnalysisLoading.visibility = View.GONE
            binding.svAnalysisContent.visibility = View.VISIBLE

            binding.tvAnalysisFreeSpace.text = "Boş ${FormatUtils.formatSizeDetailed(report.overview.freeBytes)}"
            binding.tvAnalysisPercentBig.text = "${report.overview.usedPercent}%"
            binding.tvAnalysisPercentBig.setTextColor(ThemeManager.getPrimaryTextColor(this@StorageAnalysisActivity))
            binding.pbAnalysisMain.progress = report.overview.usedPercent

            binding.tvCategoryBreakdownDetails.text =
                "• Görüntüler: ${FormatUtils.formatSize(report.imagesBytes)}      • Ses: ${FormatUtils.formatSize(report.audioBytes)}\n" +
                "• Videolar: ${FormatUtils.formatSize(report.videosBytes)}        • Belgeler: ${FormatUtils.formatSize(report.docsBytes)}\n" +
                "• Arşivler: ${FormatUtils.formatSize(report.archivesBytes)}        • Diğerleri: ${FormatUtils.formatSize(report.othersBytes)}"

            // Yinelenen dosyalar
            binding.llDuplicateItems.removeAllViews()
            val dupBytes = report.duplicateGroups.sumOf { grp -> grp.drop(1).sumOf { it.size } }
            binding.tvDuplicatesTotalSize.text = FormatUtils.formatSize(dupBytes)
            if (report.duplicateGroups.isEmpty()) {
                binding.llDuplicateItems.addView(createRowText("Yinelenen dosya bulunamadı."))
            } else {
                for (grp in report.duplicateGroups.take(15)) {
                    val first = grp.first()
                    binding.llDuplicateItems.addView(
                        createRowText("${first.name} (${grp.size} kopya • ${FormatUtils.formatSize(first.size)})")
                    )
                }
            }

            // Büyük dosyalar (> 10 MB)
            binding.llLargeFileItems.removeAllViews()
            val largeTotal = report.largeFiles.sumOf { it.size }
            binding.tvLargeFilesTotalSize.text = FormatUtils.formatSizeDetailed(largeTotal)
            if (report.largeFiles.isEmpty()) {
                binding.llLargeFileItems.addView(createRowText("10 MB'den büyük dosya bulunamadı."))
            } else {
                for (lf in report.largeFiles.take(20)) {
                    binding.llLargeFileItems.addView(
                        createRowText("${lf.name}  —  ${FormatUtils.formatSize(lf.size)}")
                    )
                }
            }
        }
    }

    private fun createRowText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextColor(ThemeManager.getPrimaryTextColor(this@StorageAnalysisActivity))
        }
    }
}
