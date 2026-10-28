package com.alphainventor.filemanager.theme

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(val id: Int, val titleTr: String) {
    LIGHT(0, "Açık (Varsayılan)"),
    DARK(1, "Koyu"),
    AMOLED(2, "AMOLED (Tam Siyah)");

    val displayName: String
        get() = titleTr

    companion object {
        fun fromId(id: Int): AppThemeMode = values().firstOrNull { it.id == id } ?: LIGHT
    }
}

object ThemeManager {
    private const val PREF_NAME = "FileManagerThemePrefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val id = prefs.getInt(KEY_THEME_MODE, AppThemeMode.LIGHT.id)
        return AppThemeMode.fromId(id)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode.id)
            .apply()
        applyNightModeFlag(mode)
    }

    fun applySavedTheme(context: Context) {
        applyNightModeFlag(getThemeMode(context))
    }

    fun applyNightModeFlag(mode: AppThemeMode) {
        when (mode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            AppThemeMode.DARK, AppThemeMode.AMOLED -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun isDarkOrAmoled(context: Context): Boolean {
        val mode = getThemeMode(context)
        return mode == AppThemeMode.DARK || mode == AppThemeMode.AMOLED
    }

    fun getBackgroundColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#121212")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getSurfaceColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FAFAFA")
        AppThemeMode.DARK -> Color.parseColor("#1E1E1E")
        AppThemeMode.AMOLED -> Color.parseColor("#141414")
    }

    fun getCardColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#222222")
        AppThemeMode.AMOLED -> Color.parseColor("#161616")
    }

    fun getToolbarColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT, AppThemeMode.DARK -> Color.parseColor("#212121")
        AppThemeMode.AMOLED -> Color.parseColor("#0D0D0D")
    }

    fun getPrimaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#1A1A1A")
        AppThemeMode.DARK -> Color.parseColor("#F5F5F5")
        AppThemeMode.AMOLED -> Color.parseColor("#FFFFFF")
    }

    fun getSecondaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#757575")
        AppThemeMode.DARK -> Color.parseColor("#B0BEC5")
        AppThemeMode.AMOLED -> Color.parseColor("#B8C4CC")
    }

    fun getDividerColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#E5E5E5")
        AppThemeMode.DARK -> Color.parseColor("#2E2E2E")
        AppThemeMode.AMOLED -> Color.parseColor("#262626")
    }

    fun createHomeTileSquircle(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f * density
            val strokePx = (1.2f * density).toInt().coerceAtLeast(1)
            when (getThemeMode(context)) {
                AppThemeMode.LIGHT -> {
                    setColor(Color.parseColor("#FFFFFF"))
                    setStroke(strokePx, Color.parseColor("#C2C2C2"))
                }
                AppThemeMode.DARK -> {
                    setColor(Color.parseColor("#262626"))
                    setStroke(strokePx, Color.parseColor("#4A4A4A"))
                }
                AppThemeMode.AMOLED -> {
                    setColor(Color.parseColor("#181818"))
                    setStroke(strokePx, Color.parseColor("#4A4A4A"))
                }
            }
        }
    }

    fun createUsedPillBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            when (getThemeMode(context)) {
                AppThemeMode.LIGHT -> {
                    setColor(Color.parseColor("#EEEEEE"))
                    setStroke(1, Color.parseColor("#D6D6D6"))
                }
                AppThemeMode.DARK -> {
                    setColor(Color.parseColor("#2C2C2C"))
                    setStroke(1, Color.parseColor("#484848"))
                }
                AppThemeMode.AMOLED -> {
                    setColor(Color.parseColor("#1F1F1F"))
                    setStroke(1, Color.parseColor("#4A4A4A"))
                }
            }
        }
    }

    fun applyWindowColors(activity: Activity, rootView: View, toolbarView: View? = null) {
        val bg = getBackgroundColor(activity)
        val toolbarColor = getToolbarColor(activity)
        rootView.setBackgroundColor(bg)
        toolbarView?.setBackgroundColor(toolbarColor)
        adaptViewTreeColors(activity, rootView, toolbarView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.statusBarColor = Color.BLACK
            activity.window.navigationBarColor = if (getThemeMode(activity) == AppThemeMode.AMOLED) Color.BLACK else toolbarColor
        }
    }

    /**
     * AMOLED ve Koyu modda siyah zemin üzerinde siyah/koyu gri kalan tüm statik başlıkları,
     * etiketleri ve giriş kutularını otomatik olarak yüksek kontrastlı renge kavuşturur.
     */
    fun adaptViewTreeColors(context: Context, view: View, skipView: View? = null) {
        if (view === skipView) return
        val primary = getPrimaryTextColor(context)
        val secondary = getSecondaryTextColor(context)

        when (view) {
            is Button -> {
                // Butonların kendi özel vurgu renklerini koru
            }
            is CheckBox -> {
                view.setTextColor(primary)
            }
            is EditText -> {
                if (view.currentTextColor == Color.parseColor("#202020") ||
                    view.currentTextColor == Color.parseColor("#212121") ||
                    view.currentTextColor == Color.BLACK ||
                    (isDarkOrAmoled(context) && view.currentTextColor == Color.parseColor("#F5F5F5"))
                ) {
                    view.setTextColor(primary)
                    view.setHintTextColor(secondary)
                }
            }
            is TextView -> {
                val cur = view.currentTextColor
                // Vurgu renklerini (Teal, Turuncu, İndigo, Beyaz) bozmadan koyu/açık metinleri temaya uyarla
                val isAccent = cur == Color.parseColor("#26A69A") ||
                    cur == Color.parseColor("#008578") ||
                    cur == Color.parseColor("#FF6D00") ||
                    cur == Color.parseColor("#3F51B5") ||
                    cur == Color.parseColor("#80CBC4")
                if (!isAccent) {
                    if (cur == Color.parseColor("#757575") || cur == Color.parseColor("#777777") ||
                        cur == Color.parseColor("#9E9E9E") || cur == Color.parseColor("#B0BEC5") ||
                        cur == Color.parseColor("#B8C4CC")
                    ) {
                        view.setTextColor(secondary)
                    } else if (cur == Color.parseColor("#212121") || cur == Color.parseColor("#202020") ||
                        cur == Color.parseColor("#1A1A1A") || cur == Color.BLACK ||
                        cur == Color.parseColor("#F5F5F5")
                    ) {
                        view.setTextColor(primary)
                    }
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    // Koyu araç çubuklarını (#212121, #2C2C2C, #3F51B5) atla
                    val idName = try {
                        if (child.id != View.NO_ID) context.resources.getResourceEntryName(child.id) else ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (idName.contains("toolbar", ignoreCase = true) ||
                        idName == "bottomActionBar" ||
                        idName == "pasteActionBar" ||
                        idName == "llInlineSearchBar"
                    ) {
                        continue
                    }
                    adaptViewTreeColors(context, child, skipView)
                }
            }
        }
    }
}
