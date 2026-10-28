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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
        AppThemeMode.AMOLED -> Color.parseColor("#121212")
    }

    fun getCardColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#FFFFFF")
        AppThemeMode.DARK -> Color.parseColor("#222222")
        AppThemeMode.AMOLED -> Color.parseColor("#141414")
    }

    fun getToolbarColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT, AppThemeMode.DARK -> Color.parseColor("#212121")
        AppThemeMode.AMOLED -> Color.parseColor("#000000")
    }

    fun getPrimaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#1A1A1A")
        AppThemeMode.DARK -> Color.parseColor("#F5F5F5")
        AppThemeMode.AMOLED -> Color.parseColor("#FFFFFF")
    }

    fun getSecondaryTextColor(context: Context): Int = when (getThemeMode(context)) {
        AppThemeMode.LIGHT -> Color.parseColor("#757575")
        AppThemeMode.DARK -> Color.parseColor("#B0BEC5")
        AppThemeMode.AMOLED -> Color.parseColor("#C8D2D8")
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
                    setStroke(strokePx, Color.parseColor("#CCCCCC"))
                }
                AppThemeMode.DARK -> {
                    setColor(Color.parseColor("#242424"))
                    setStroke(strokePx, Color.parseColor("#4A4A4A"))
                }
                AppThemeMode.AMOLED -> {
                    setColor(Color.parseColor("#161616"))
                    setStroke(strokePx, Color.parseColor("#484848"))
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
                    setColor(Color.parseColor("#1C1C1C"))
                    setStroke(1, Color.parseColor("#4A4A4A"))
                }
            }
        }
    }

    fun getStatusBarHeightPx(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    /**
     * Üstteki saat/pil/Wi-Fi durum çubuğu (status bar) ile uygulama araç çubuğunun (toolbar)
     * çakışmasını Android 4'ten Android 15/16'ya (One UI 7 dahil) kadar %100 engelleyen sistem:
     * - WindowCompat.setDecorFitsSystemWindows(window, true) uygular.
     * - Eğer cihaz/ROM edge-to-edge zorlaması nedeniyle rootView'u y=0'da (saat panelinin altında)
     *   bırakırsa, durum çubuğu yüksekliği kadar otomatik üst boşluk (paddingTop) ekler.
     */
    fun applyWindowColors(activity: Activity, rootView: View, toolbarView: View? = null, extraInsetTarget: View? = null) {
        val bg = getBackgroundColor(activity)
        val toolbarColor = getToolbarColor(activity)
        rootView.setBackgroundColor(bg)
        toolbarView?.setBackgroundColor(toolbarColor)
        adaptViewTreeColors(activity, rootView, toolbarView)

        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.statusBarColor = Color.BLACK
            activity.window.navigationBarColor = if (getThemeMode(activity) == AppThemeMode.AMOLED) Color.BLACK else toolbarColor
        }
        WindowInsetsControllerCompat(activity.window, rootView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ensureStatusBarClearance(activity, rootView, extraInsetTarget)
    }

    private fun ensureStatusBarClearance(activity: Activity, rootView: View, extraInsetTarget: View? = null) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyTopBottomSafePadding(activity, v, sysBars.top, sysBars.bottom)
            if (extraInsetTarget != null) {
                applyTopBottomSafePadding(activity, extraInsetTarget, sysBars.top, sysBars.bottom)
            }
            windowInsets
        }
        rootView.post {
            val statusBarH = getStatusBarHeightPx(activity)
            if (statusBarH > 0) {
                val loc = IntArray(2)
                rootView.getLocationOnScreen(loc)
                // Eğer rootView ekranın en tepesindeyse (loc[1] == 0) ve henüz padding verilmediyse:
                if (loc[1] < statusBarH / 2 && rootView.paddingTop < statusBarH / 2) {
                    applyTopBottomSafePadding(activity, rootView, statusBarH, 0)
                    if (extraInsetTarget != null) {
                        applyTopBottomSafePadding(activity, extraInsetTarget, statusBarH, 0)
                    }
                }
            }
        }
    }

    private fun applyTopBottomSafePadding(activity: Activity, view: View, insetTop: Int, insetBottom: Int) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val statusBarH = insetTop.coerceAtLeast(getStatusBarHeightPx(activity))
        // Eğer DecorView zaten view'u status bar'ın altına itmişse (loc[1] >= statusBarH), ekstra üst padding ekleme
        val neededTop = if (loc[1] >= statusBarH / 2 && loc[1] > 0) 0 else statusBarH
        if (neededTop > 0 || insetBottom > 0) {
            view.setPadding(view.paddingLeft, neededTop, view.paddingRight, insetBottom)
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
                val isAccent = cur == Color.parseColor("#26A69A") ||
                    cur == Color.parseColor("#008578") ||
                    cur == Color.parseColor("#FF6D00") ||
                    cur == Color.parseColor("#3F51B5") ||
                    cur == Color.parseColor("#80CBC4")
                if (!isAccent) {
                    if (cur == Color.parseColor("#757575") || cur == Color.parseColor("#777777") ||
                        cur == Color.parseColor("#9E9E9E") || cur == Color.parseColor("#B0BEC5") ||
                        cur == Color.parseColor("#B8C4CC") || cur == Color.parseColor("#C8D2D8")
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
