package com.devfahim00.yulp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application entry point: initialises global settings and pins the app theme
 * to the in-app night-mode setting (NOT the system dark mode), so the toolbar,
 * pages and dialogs always follow the user's toggle.
 */
class YulpApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        applyNightMode()
    }

    companion object {
        /** Must be called whenever Settings.nightMode changes. */
        fun applyNightMode() {
            AppCompatDelegate.setDefaultNightMode(
                if (Settings.nightMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}
