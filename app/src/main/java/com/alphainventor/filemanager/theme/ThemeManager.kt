package com.alphainventor.filemanager.theme

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(val id: Int, val titleTr: String) {
    LIGHT(0, "Açık (Varsayılan)"),
    DARK(1, "Koyu"),
    AMOLED(2, "AMOLED (Tam Siyah)");

    val displayName: String
        get() = titleTr

    companion object {
        fun fromId(id: Int): AppThemeMode = entries.firstOrNull { it.id == id } ?: LIGHT
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

    fun getBackgroundColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#121212")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getSurfaceColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FAFAFA")
        AppThemeMode.DARK -> Color.parseColor("#1E1E1E")
        AppThemeMode.AMOLED -> Color.parseColor("#0A0A0A")
    }

    fun getCardColor(context: Context): Int = getSurfaceColor(context)

    fun getToolbarColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT, AppThemeMode.DARK -> Color.parseColor("#212121")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getPrimaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#000000")
        AppThemeMode.DARK -> Color.parseColor("#F5F5F5")
        AppThemeMode.AMOLED -> Color.parseColor("#FFFFFF")
    }

    fun getSecondaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#757575")
        AppThemeMode.DARK -> Color.parseColor("#9E9E9E")
        AppThemeMode.AMOLED -> Color.parseColor("#8E8E93")
    }

    fun getDividerColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#E8E8E8")
        AppThemeMode.DARK -> Color.parseColor("#2C2C2C")
        AppThemeMode.AMOLED -> Color.parseColor("#161616")
    }

    fun createHomeTileSquircle(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            val strokePx = (1f * density).toInt().coerceAtLeast(1)
            when (getThemeMode(context)) {
                AppThemeMode.LIGHT -> {
                    setColor(Color.parseColor("#FFFFFF"))
                    setStroke(strokePx, Color.parseColor("#B8B8B8"))
                }
                AppThemeMode.DARK -> {
                    setColor(Color.parseColor("#242424"))
                    setStroke(strokePx, Color.parseColor("#3A3A3A"))
                }
                AppThemeMode.AMOLED -> {
                    setColor(Color.parseColor("#080808"))
                    setStroke(strokePx, Color.parseColor("#242424"))
                }
            }
        }
    }

    fun applyWindowColors(activity: Activity, rootView: View, toolbarView: View? = null) {
        val bg = getBackgroundColor(activity)
        val toolbarColor = getToolbarColor(activity)
        rootView.setBackgroundColor(bg)
        toolbarView?.setBackgroundColor(toolbarColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.statusBarColor = Color.BLACK
            activity.window.navigationBarColor = if (getThemeMode(activity) == AppThemeMode.AMOLED) Color.BLACK else toolbarColor
        }
    }
}
