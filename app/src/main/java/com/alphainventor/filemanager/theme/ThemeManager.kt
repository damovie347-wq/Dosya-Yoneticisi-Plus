package com.alphainventor.filemanager.theme

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.alphainventor.filemanager.R

enum class AppThemeMode(val id: Int, val displayName: String) {
    LIGHT(0, "Açık Tema (Klasik Beyaz)"),
    DARK(1, "Koyu Tema (Gece Grisi)"),
    AMOLED(2, "AMOLED Tam Siyah (#000000 - Maksimum Pil & Sıfır Isınma)");

    companion object {
        fun fromId(id: Int): AppThemeMode = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

object ThemeManager {
    private const val PREF_NAME = "transferable_prefs"
    private const val KEY_THEME_MODE = "night_mode_theme"

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return AppThemeMode.fromId(prefs.getInt(KEY_THEME_MODE, AppThemeMode.LIGHT.id))
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode.id)
            .apply()
        applySavedTheme(context)
    }

    fun applySavedTheme(context: Context) {
        when (getThemeMode(context)) {
            AppThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            AppThemeMode.DARK, AppThemeMode.AMOLED -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun getBackgroundColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#121212")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getCardColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#1E1E1E")
        AppThemeMode.AMOLED -> Color.parseColor("#0A0A0A")
    }

    fun getToolbarColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT, AppThemeMode.DARK -> Color.parseColor("#212121")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getPrimaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#212121")
        AppThemeMode.DARK -> Color.parseColor("#F5F5F5")
        AppThemeMode.AMOLED -> Color.parseColor("#FFFFFF")
    }

    fun getSecondaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#757575")
        AppThemeMode.DARK -> Color.parseColor("#9E9E9E")
        AppThemeMode.AMOLED -> Color.parseColor("#8E8E93")
    }

    fun getDividerColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#EEEEEE")
        AppThemeMode.DARK -> Color.parseColor("#2C2C2C")
        AppThemeMode.AMOLED -> Color.parseColor("#161616")
    }

    fun createHomeCircleDrawable(context: Context): GradientDrawable {
        val mode = getThemeMode(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (mode) {
                AppThemeMode.LIGHT -> {
                    setColor(Color.parseColor("#F5F6F8"))
                    setStroke(3, Color.parseColor("#D5D8DC"))
                }
                AppThemeMode.DARK -> {
                    setColor(Color.parseColor("#242424"))
                    setStroke(3, Color.parseColor("#383838"))
                }
                AppThemeMode.AMOLED -> {
                    setColor(Color.parseColor("#0A0A0A"))
                    setStroke(3, Color.parseColor("#262626"))
                }
            }
        }
    }

    fun applyWindowColors(activity: Activity, rootView: View? = null, toolbarView: View? = null) {
        val toolbarColor = getToolbarColor(activity)
        val bgColor = getBackgroundColor(activity)
        activity.window.statusBarColor = toolbarColor
        activity.window.navigationBarColor = if (getThemeMode(activity) == AppThemeMode.AMOLED) Color.BLACK else toolbarColor
        rootView?.setBackgroundColor(bgColor)
        toolbarView?.setBackgroundColor(toolbarColor)
    }
}
