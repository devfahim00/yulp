package com.devfahim00.yulp

import android.content.Context

/** App-wide settings backed by SharedPreferences. */
object Settings {
    private lateinit var prefs: android.content.SharedPreferences

    var nightMode: Boolean
        get() = prefs.getBoolean("night", false)
        set(v) = prefs.edit().putBoolean("night", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepon", false)
        set(v) = prefs.edit().putBoolean("keepon", v).apply()

    var searchEngine: String   // google | bing | duckduckgo
        get() = prefs.getString("engine", "google") ?: "google"
        set(v) = prefs.edit().putString("engine", v).apply()

    fun searchUrl(q: String): String = when (searchEngine) {
        "bing" -> "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
        "duckduckgo" -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(q)
        else -> "https://www.google.com/search?q=" + android.net.Uri.encode(q)
    }

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("yulp_settings", Context.MODE_PRIVATE)
    }
}
