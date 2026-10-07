package com.devfahim00.yulp

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Settings.init(this)
        AdBlocker.init(this)
        DownloadEngine.init(this, false)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.swNight).apply {
            isChecked = Settings.nightMode
            setOnCheckedChangeListener { _, v ->
                Settings.nightMode = v
                // applies to the whole app (toolbar, pages, dialogs) and
                // re-creates activities with the right palette
                YulpApp.applyNightMode()
            }
        }
        findViewById<MaterialSwitch>(R.id.swKeepOn).apply {
            isChecked = Settings.keepScreenOn
            setOnCheckedChangeListener { _, v -> Settings.keepScreenOn = v }
        }

        // search engine
        val engines = listOf("google", "bing", "duckduckgo")
        val tgEngine = findViewById<MaterialButtonToggleGroup>(R.id.tgEngine)
        val selEngine = engines.indexOf(Settings.searchEngine).coerceAtLeast(0)
        tgEngine.check(tgEngine.getChildAt(selEngine).id)
        tgEngine.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val idx = (0 until tgEngine.childCount).firstOrNull { tgEngine.getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                Settings.searchEngine = engines[idx]
            }
        }

        // ad blocker
        findViewById<MaterialSwitch>(R.id.swAdBlock).apply {
            isChecked = AdBlocker.enabled
            setOnCheckedChangeListener { _, v -> AdBlocker.setEnabled(this@SettingsActivity, v) }
        }
        findViewById<MaterialButton>(R.id.btnAdBlockPage).setOnClickListener {
            startActivity(android.content.Intent(this, AdBlockActivity::class.java))
        }

        // default download threads
        val tgThreads = findViewById<MaterialButtonToggleGroup>(R.id.tgThreads)
        for (n in DownloadEngine.THREAD_OPTIONS) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = n.toString()
                layoutParams = android.view.ViewGroup.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                (layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1f
            }
            tgThreads.addView(b)
        }
        val selT = DownloadEngine.THREAD_OPTIONS.indexOf(DownloadEngine.defaultThreads)
            .let { if (it >= 0) it else DownloadEngine.THREAD_OPTIONS.indexOf(4) }
        tgThreads.check(tgThreads.getChildAt(selT).id)
        tgThreads.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val idx = (0 until tgThreads.childCount).firstOrNull { tgThreads.getChildAt(it).id == checkedId } ?: return@addOnButtonCheckedListener
                DownloadEngine.defaultThreads = DownloadEngine.THREAD_OPTIONS[idx]
            }
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            Store(this).clear("h")
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            Toast.makeText(this, "History, cookies and cache cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.txtAbout).text =
            "Yulp v1.3.0 · github.com/devfahim00/yulp"
    }
}
