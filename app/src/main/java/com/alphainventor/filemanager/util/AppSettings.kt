package com.alphainventor.filemanager.util

import android.content.Context
import com.alphainventor.filemanager.FileManagerApp

object AppSettings {
    private const val PREF = "transferable_prefs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // 0: Ad, 1: Boyut, 2: Tarih, 3: Tür, 4: Sıralama yok, 5: Çekim Tarihi
    var sortBy: Int
        get() = prefs(FileManagerApp.instance).getInt("sort_by", 2)
        set(v) = prefs(FileManagerApp.instance).edit().putInt("sort_by", v).apply()

    var sortAscending: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("sort_ascending", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("sort_ascending", v).apply()

    // 0: Liste, 1: Izgara (Kare), 2: Kompakt/Detaylı Liste
    var viewTypeMode: Int
        get() = prefs(FileManagerApp.instance).getInt("view_type_mode", 0)
        set(v) = prefs(FileManagerApp.instance).edit().putInt("view_type_mode", v).apply()

    // 0: Normal/Küçük Simge, 1: Büyük Simge
    var iconSizeMode: Int
        get() = prefs(FileManagerApp.instance).getInt("icon_size_mode", 0)
        set(v) = prefs(FileManagerApp.instance).edit().putInt("icon_size_mode", v).apply()

    var applyViewToAllFolders: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("apply_view_to_all", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("apply_view_to_all", v).apply()

    fun getViewTypeForLocation(locationKey: String, defaultViewType: Int): Int {
        val p = prefs(FileManagerApp.instance)
        val locKey = "loc_view_type_$locationKey"
        if (p.contains(locKey)) {
            return p.getInt(locKey, defaultViewType)
        }
        if (applyViewToAllFolders && p.contains("view_type_mode")) {
            return p.getInt("view_type_mode", defaultViewType)
        }
        return defaultViewType
    }

    fun setViewTypeForLocation(locationKey: String, mode: Int, applyToAll: Boolean) {
        val p = prefs(FileManagerApp.instance)
        val editor = p.edit()
        editor.putInt("loc_view_type_$locationKey", mode)
        if (applyToAll) {
            editor.putInt("view_type_mode", mode)
            for (k in p.all.keys) {
                if (k.startsWith("loc_view_type_") && k != "loc_view_type_$locationKey") {
                    editor.putInt(k, mode)
                }
            }
        }
        editor.apply()
    }

    fun getIconSizeForLocation(locationKey: String): Int {
        val p = prefs(FileManagerApp.instance)
        val locKey = "loc_icon_size_$locationKey"
        if (p.contains(locKey)) {
            return p.getInt(locKey, iconSizeMode)
        }
        return iconSizeMode
    }

    fun setIconSizeForLocation(locationKey: String, sizeMode: Int, applyToAll: Boolean) {
        val p = prefs(FileManagerApp.instance)
        val editor = p.edit()
        editor.putInt("loc_icon_size_$locationKey", sizeMode)
        editor.putInt("icon_size_mode", sizeMode)
        if (applyToAll) {
            for (k in p.all.keys) {
                if (k.startsWith("loc_icon_size_")) {
                    editor.putInt(k, sizeMode)
                }
            }
        }
        editor.apply()
    }

    fun getSortForLocation(locationKey: String): Pair<Int, Boolean> {
        val p = prefs(FileManagerApp.instance)
        val sKey = "loc_sort_by_$locationKey"
        val aKey = "loc_sort_asc_$locationKey"
        val sb = if (p.contains(sKey)) p.getInt(sKey, sortBy) else sortBy
        val sa = if (p.contains(aKey)) p.getBoolean(aKey, sortAscending) else sortAscending
        return Pair(sb, sa)
    }

    fun setSortForLocation(locationKey: String, newSortBy: Int, newSortAsc: Boolean, applyToAll: Boolean) {
        val p = prefs(FileManagerApp.instance)
        val editor = p.edit()
        editor.putInt("loc_sort_by_$locationKey", newSortBy)
        editor.putBoolean("loc_sort_asc_$locationKey", newSortAsc)
        editor.putInt("sort_by", newSortBy)
        editor.putBoolean("sort_ascending", newSortAsc)
        if (applyToAll) {
            for (k in p.all.keys) {
                if (k.startsWith("loc_sort_by_")) editor.putInt(k, newSortBy)
                if (k.startsWith("loc_sort_asc_")) editor.putBoolean(k, newSortAsc)
            }
        }
        editor.apply()
    }

    var showThumbnails: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_thumbnails", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_thumbnails", v).apply()

    var useInternalImageViewer: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("use_internal_image_viewer", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("use_internal_image_viewer", v).apply()

    var useInternalVideoPlayer: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("use_internal_video_player", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("use_internal_video_player", v).apply()

    var useInternalMusicPlayer: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("use_internal_music_player", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("use_internal_music_player", v).apply()

    var useInternalTextEditor: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("use_internal_text_editor", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("use_internal_text_editor", v).apply()

    var storageFullThreshold: Int
        get() = prefs(FileManagerApp.instance).getInt("storage_full_threshold", 96)
        set(v) = prefs(FileManagerApp.instance).edit().putInt("storage_full_threshold", v).apply()

    var useRecycleBin: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("use_recycle_bin", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("use_recycle_bin", v).apply()

    var recycleBinConfirmation: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("recycle_bin_confirmation_2", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("recycle_bin_confirmation_2", v).apply()

    var showDrawerAlways: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_drawer_always", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_drawer_always", v).apply()

    var detectUsbAttached: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("detect_usb_attached", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("detect_usb_attached", v).apply()

    var showHistory: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_history", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_history", v).apply()

    var showSystemStorage: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_system", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_system", v).apply()

    var showHiddenFiles: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_hidden", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_hidden", v).apply()

    var fileSizeUnitBase10: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("file_size_unit_gb", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("file_size_unit_gb", v).apply()

    var showAddToFavorite: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_add_to_favorite", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_add_to_favorite", v).apply()

    var showAddToHomeScreen: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_add_to_home_screen", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_add_to_home_screen", v).apply()

    var showHideUnhide: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_hide_unhide", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_hide_unhide", v).apply()

    var showOpenAs: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("show_open_as", false)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("show_open_as", v).apply()
}
