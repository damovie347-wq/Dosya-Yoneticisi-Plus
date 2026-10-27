package com.alphainventor.filemanager

import android.app.Application
import android.content.ComponentCallbacks2
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.ThumbnailCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FileManagerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemeManager.applySavedTheme(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            ThumbnailCacheManager.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ThumbnailCacheManager.clear()
    }

    companion object {
        lateinit var instance: FileManagerApp
            private set
    }
}
