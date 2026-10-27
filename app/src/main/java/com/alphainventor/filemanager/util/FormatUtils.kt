package com.alphainventor.filemanager.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))
    private val sectionDateFormat = SimpleDateFormat("dd MMM yyyy", Locale("tr", "TR"))

    fun formatSize(bytes: Long, base10: Boolean = AppSettings.fileSizeUnitBase10): String {
        if (bytes <= 0L) return "0 B"
        val unit = if (base10) 1000.0 else 1024.0
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit)).toInt().coerceAtMost(4)
        val pre = arrayOf("B", "KB", "MB", "GB", "TB")[exp]
        val value = bytes / Math.pow(unit, exp.toDouble())
        return if (exp >= 3) {
            String.format(Locale("tr", "TR"), "%.1f %s", value, pre)
        } else {
            String.format(Locale("tr", "TR"), "%.0f %s", value, pre)
        }
    }

    fun formatSizeDetailed(bytes: Long, base10: Boolean = AppSettings.fileSizeUnitBase10): String {
        if (bytes <= 0L) return "0 B"
        val unit = if (base10) 1000.0 else 1024.0
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit)).toInt().coerceAtMost(4)
        val pre = arrayOf("B", "KB", "MB", "GB", "TB")[exp]
        val value = bytes / Math.pow(unit, exp.toDouble())
        return String.format(Locale("tr", "TR"), "%.2f %s", value, pre)
    }

    fun formatDate(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        return synchronized(dateFormat) { dateFormat.format(Date(timestampMs)) }
    }

    fun formatSectionDate(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        return synchronized(sectionDateFormat) { sectionDateFormat.format(Date(timestampMs)) }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
