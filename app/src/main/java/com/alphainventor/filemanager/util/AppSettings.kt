package com.alphainventor.filemanager.util

import android.content.Context
import com.alphainventor.filemanager.FileManagerApp

object AppSettings {
    private const val PREF = "transferable_prefs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var sortBy: Int
        get() = prefs(FileManagerApp.instance).getInt("sort_by", 0)
        set(v) = prefs(FileManagerApp.instance).edit().putInt("sort_by", v).apply()

    var sortAscending: Boolean
        get() = prefs(FileManagerApp.instance).getBoolean("sort_ascending", true)
        set(v) = prefs(FileManagerApp.instance).edit().putBoolean("sort_ascending", v).apply()

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
