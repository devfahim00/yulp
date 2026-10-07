package com.devfahim00.yulp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private class Tab(val id: Int, val incognito: Boolean) {
        lateinit var web: WebView
        var title = "New tab"
        var desktop = false
    }

    /** JS bridge: receives media scan reports from pages. */
    private class MediaBridge(val tabId: Int) {
        @JavascriptInterface
        fun report(json: String) {
            MediaSniffer.parseReport(tabId, json)
        }
    }

    private companion object {
        const val HOME = "https://yulp.start/"
        var nextId = 1
        const val NIGHT_CSS =
            "html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}" +
                "img,video,picture,canvas,svg,iframe,embed,object{filter:invert(1) hue-rotate(180deg)!important}"
    }

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private val current: Tab? get() = tabs.getOrNull(cur)

    private lateinit var store: Store
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tabCount: TextView
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var incognitoIcon: ImageButton
    private lateinit var mobileUa: String

    /** WebView renders content dark natively (algorithmic darkening) when supported. */
    private val algoDark: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    }

    private var fullscreen = false

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var filePathCb: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            filePathCb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(it.resultCode, it.data)
            )
            filePathCb = null
        }

    private val tabLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val data = res.data ?: return@registerForActivityResult
            val closed = data.getIntArrayExtra("closedIds") ?: IntArray(0)
            val action = data.getStringExtra("action")

            if (action == "closeAll") {
                tabs.forEach { container.removeView(it.web); it.web.destroy() }
                tabs.clear()
                cur = -1
                Tabs.thumbnails.clear()
                newTab()
                return@registerForActivityResult
            }
            if (closed.isNotEmpty()) {
                container.removeAllViews()
                closed.forEach { id ->
                    val i = tabs.indexOfFirst { it.id == id }
                    if (i >= 0) {
                        val t = tabs.removeAt(i)
                        t.web.destroy()
                        Tabs.thumbnails.remove(id)
                        MediaSniffer.clear(id)
                    }
                }
                cur = -1
            }
            when (action) {
                "new" -> newTab()
                "newIncognito" -> newTab(null, true)
                else -> {
                    val sel = data.getIntExtra("selectId", -1)
                    val idx = if (sel >= 0) tabs.indexOfFirst { it.id == sel } else -1
                    when {
                        idx >= 0 -> switchTo(idx)
                        tabs.isEmpty() -> newTab()
                        else -> switchTo(0)
                    }
                }
            }
            updateTabSnapshot()
        }

    private val listLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val url = res.data?.getStringExtra("url")
            if (!url.isNullOrBlank()) {
                if (res.data?.getBooleanExtra("newTab", false) == true) newTab(url)
                else current?.web?.loadUrl(url)
            }
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Settings.init(this)
        AdBlocker.init(this)
        DownloadEngine.init(this, autoResume = false)
        mobileUa = WebSettings.getDefaultUserAgent(this)

        // first-party cookies must be ON for Google search etc. to work
        CookieManager.getInstance().setAcceptCookie(true)

        container = findViewById(R.id.container)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        tabCount = findViewById(R.id.tabCount)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        incognitoIcon = findViewById(R.id.icIncognito)

        if (Settings.keepScreenOn) window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        urlBar.setOnEditorActionListener { v, actionId, e ->
            if (actionId == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(v.text.toString()); true
            } else false
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { current?.web?.reload() }
        findViewById<View>(R.id.btnBack).setOnClickListener { current?.web?.let { if (it.canGoBack()) it.goBack() } }
        findViewById<View>(R.id.btnForward).setOnClickListener { current?.web?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.btnHome).setOnClickListener { current?.web?.let { loadHome(it) } }
        findViewById<View>(R.id.btnTabs).setOnClickListener { openTabGrid() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }

        findInput.doAfterTextChanged { current?.web?.findAllAsync(it.toString()) }
        findViewById<View>(R.id.findNext).setOnClickListener { current?.web?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { current?.web?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustom()
                    fullscreen -> toggleFullscreen()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    current?.web?.canGoBack() == true -> current?.web?.goBack()
                    tabs.size > 1 -> closeTab(cur)
                    else -> finish()
                }
            }
        })

        val restored = restoreTabs(savedInstanceState)
        if (!restored) newTab(intent?.data?.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { newTab(it.toString()) }
    }

    /**
     * Keep tabs across theme toggles (night mode) and process death.
     * Incognito URLs are deliberately NOT persisted.
     */
    private fun restoreTabs(state: Bundle?): Boolean {
        val saved = state?.getStringArrayList("tabs") ?: return false
        if (saved.isEmpty()) return false
        val savedCur = state.getInt("cur", -1)
        var firstIndex = -1
        for (entry in saved) {
            val p = entry.split('\u0000')
            val url = p.getOrNull(0) ?: continue
            val incognito = p.getOrNull(1) == "1"
            val desktop = p.getOrNull(2) == "1"
            val t = Tab(nextId++, incognito)
            t.desktop = desktop
            t.web = makeWebView(t)
            tabs.add(t)
            if (firstIndex < 0) firstIndex = tabs.lastIndex
            if (incognito || url.isBlank() || isHome(url)) loadHome(t.web) else t.web.loadUrl(url)
        }
        if (tabs.isEmpty()) return false
        switchTo(if (savedCur in tabs.indices) savedCur else firstIndex)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val arr = ArrayList<String>(tabs.size)
        tabs.forEach { t ->
            // never persist incognito URLs
            arr.add("${if (t.incognito) "" else (t.web.url ?: "")}\u0000${if (t.incognito) "1" else "0"}\u0000${if (t.desktop) "1" else "0"}")
        }
        outState.putStringArrayList("tabs", arr)
        outState.putInt("cur", cur)
    }

    override fun onPause() { captureThumb(current); current?.web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); current?.web?.onResume() }
    override fun onDestroy() { tabs.forEach { it.web.destroy() }; super.onDestroy() }

    // ---------- tabs ----------

    private fun newTab(url: String? = null, incognito: Boolean = false) {
        val t = Tab(nextId++, incognito)
        t.web = makeWebView(t)
        tabs.add(t)
        switchTo(tabs.lastIndex)
        if (url == null) loadHome(t.web) else t.web.loadUrl(url)
    }

    private fun switchTo(i: Int) {
        closeFind()
        captureThumb(current)
        tabs.getOrNull(cur)?.web?.onPause()
        container.removeAllViews()
        cur = i
        val t = tabs[i]
        container.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        t.web.onResume()
        tabCount.text = tabs.size.toString()
        urlBar.hint = if (t.incognito) "Incognito · search or type URL" else "Search or type URL"
        showUrl(t.web.url ?: "")
        progress.visibility = View.INVISIBLE
        updateIncognitoBadge()
        updateTabSnapshot()
    }

    private fun updateIncognitoBadge() {
        val t = current
        incognitoIcon.visibility = if (t?.incognito == true) View.VISIBLE else View.GONE
    }

    private fun updateTabSnapshot() {
        Tabs.snapshot = tabs.map {
            Tabs.TabInfo(it.id, it.title.ifBlank { "New tab" }, it.web.url ?: "", it.incognito)
        }
        Tabs.currentId = current?.id ?: -1
    }

    private fun captureThumb(t: Tab?) {
        t ?: return
        try {
            val w = t.web
            if (w.width <= 0 || w.height <= 0) return
            val tw = 320
            val th = (tw.toLong() * w.height / w.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(tw.toFloat() / w.width, th.toFloat() / w.height)
            w.draw(c)
            Tabs.putThumb(t.id, bmp)
        } catch (_: Exception) {
        }
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        Tabs.thumbnails.remove(t.id)
        MediaSniffer.clear(t.id)
        container.removeView(t.web)
        t.web.destroy()
        cur = -1
        if (tabs.isEmpty()) newTab() else switchTo(minOf(i, tabs.lastIndex))
    }

    private fun openTabGrid() {
        captureThumb(current)
        updateTabSnapshot()
        tabLauncher.launch(Intent(this, TabActivity::class.java))
    }

    // ---------- webview ----------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun makeWebView(t: Tab): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            safeBrowsingEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        // Let the WebView render web content dark together with the app's night
        // mode (preferred over CSS inversion on modern WebViews).
        try {
            if (algoDark) WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, true)
        } catch (_: Exception) {
        }
        // Google serves a "unusual traffic / captcha" page when it sees the
        // X-Requested-With header on direct SERP loads -> empty allow-list
        // strips the header entirely (WebView 118+).
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, setOf())
            }
        } catch (_: Exception) {
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !t.incognito)

        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        w.addJavascriptInterface(MediaBridge(t.id), "YulpMedia")

        w.setOnLongClickListener {
            val r = w.hitTestResult
            val x = r.extra ?: return@setOnLongClickListener false
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                    AlertDialog.Builder(this).setTitle(x)
                        .setItems(arrayOf("Open in new tab", "Open in incognito", "Copy link", "Share link", "Download link")) { _, i ->
                            when (i) {
                                0 -> newTab(x)
                                1 -> newTab(x, true)
                                2 -> copy(x)
                                3 -> share(x)
                                else -> download(x, null, null, null)
                            }
                        }.show()
                WebView.HitTestResult.IMAGE_TYPE ->
                    AlertDialog.Builder(this).setTitle("Image")
                        .setItems(arrayOf("Open image in new tab", "Download image")) { _, i ->
                            if (i == 0) newTab(x) else download(x, null, null, null)
                        }.show()
                else -> return@setOnLongClickListener false
            }
            true
        }

        w.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.isForMainFrame) return null
                val u = r.url.toString()
                val rule = AdBlocker.blocked(u)
                if (rule != null) {
                    // ads are blocked in incognito too, but nothing is recorded
                    if (!t.incognito) AdBlocker.record(u, rule)
                    // Failing stream -> the request dies with a network error,
                    // exactly like uBlock's ERR_BLOCKED_BY_CLIENT.
                    return AdBlocker.blockedResponse()
                }
                // media sniffing (video/audio found via network requests)
                try {
                    val type = MediaSniffer.sniffUrl(u)
                    if (type != null) {
                        val label = if ("videoplayback" in u || "googlevideo" in u)
                            MediaSniffer.itagLabel(u) else ""
                        MediaSniffer.put(t.id, MediaSniffer.Media(u, type, label, src = "net"))
                    }
                } catch (_: Exception) {
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (t === current) { showUrl(url); progress.visibility = View.VISIBLE }
                if (url.startsWith("http") && !isHome(url)) {
                    MediaSniffer.clear(t.id)
                    injectScripts(view)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.title = view.title ?: url
                if (t === current) {
                    showUrl(url)
                    progress.visibility = View.INVISIBLE
                    captureThumb(t)
                }
                if (!t.incognito && url.startsWith("http") && !isHome(url)) {
                    store.addHistory(Store.Item(t.title, url))
                }
                injectScripts(view)
                updateTabSnapshot()
            }

            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme in listOf("http", "https", "about", "data", "blob")) return false
                try {
                    startActivity(Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                    })
                } catch (_: Exception) {
                }
                return true
            }

            override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    view.loadDataWithBaseURL(
                        r.url.toString(), errorHtml(e.description.toString()),
                        "text/html", "utf-8", r.url.toString()
                    )
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (t === current) {
                    progress.setProgressCompat(p, true)
                    progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
                if (t === current) updateTabSnapshot()
            }

            override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) = showCustom(view, cb)
            override fun onHideCustomView() = hideCustom()

            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCb?.onReceiveValue(null)
                filePathCb = cb
                return try { fileChooser.launch(p.createIntent()); true }
                catch (e: Exception) { filePathCb = null; false }
            }
        }
        return w
    }

    // ---------- script injection (cosmetic ad filter + media sniff + night fallback) ----------

    private fun injectScripts(w: WebView) {
        try {
            if (AdBlocker.enabled) {
                val payload = JSONObject().put("css", AdBlocker.cosmeticSelectors()).toString()
                w.evaluateJavascript(
                    """!function(){try{if(document.getElementById('yulp-cosmetic'))return;
var o=$payload;var st=document.createElement('style');st.id='yulp-cosmetic';
st.textContent=o.css;(document.head||document.documentElement).appendChild(st);}catch(e){}}();""",
                    null
                )
                w.evaluateJavascript(AdBlocker.cosmeticJs(), null)
            }
            w.evaluateJavascript(MediaSniffer.sniffJs(), null)
            // CSS-invert night mode only as a fallback for old WebViews without
            // algorithmic darkening; never applied to our own start page.
            if (!algoDark) applyNight(w, Settings.nightMode)
        } catch (_: Exception) {
        }
    }

    private fun applyNight(w: WebView, on: Boolean) {
        try {
            val url = w.url ?: ""
            if (isHome(url)) return // start page themes itself from Settings
            if (on) {
                val payload = JSONObject().put("css", NIGHT_CSS).toString()
                w.evaluateJavascript(
                    """!function(){try{if(document.getElementById('yulp-night'))return;
var o=$payload;var st=document.createElement('style');st.id='yulp-night';
st.textContent=o.css;(document.head||document.documentElement).appendChild(st);}catch(e){}}();""",
                    null
                )
            } else {
                w.evaluateJavascript(
                    """!function(){try{var s=document.getElementById('yulp-night');if(s)s.remove();}catch(e){}}();""",
                    null
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Night mode now applies app-wide: the setting drives the Android theme
     * (activities re-create with the right palette) and WebView content
     * (algorithmic darkening on modern WebViews).
     */
    private fun toggleNight() {
        Settings.nightMode = !Settings.nightMode
        AppCompatDelegate.setDefaultNightMode(
            if (Settings.nightMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        toast(if (Settings.nightMode) "Night mode on" else "Night mode off")
    }

    // ---------- media picker (via Tools menu; no floating button) ----------

    private fun openMediaPicker() {
        val t = current ?: return
        val url = t.web.url ?: ""
        if (!url.startsWith("http") || isHome(url)) { toast("Open a page first"); return }
        startActivity(
            Intent(this, MediaPickerActivity::class.java)
                .putExtra("tabId", t.id)
                .putExtra("pageUrl", url)
                .putExtra("ua", mobileUa)
        )
    }

    private fun showCustom(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) { cb.onCustomViewHidden(); return }
        v.setBackgroundColor(Color.BLACK)
        customView = v; customCb = cb
        (window.decorView as FrameLayout).addView(v, FrameLayout.LayoutParams(-1, -1))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customCb?.onCustomViewHidden(); customCb = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ---------- navigation helpers ----------

    private fun isHome(url: String) = url.startsWith(HOME)

    private fun showUrl(url: String) {
        if (!urlBar.hasFocus()) urlBar.setText(if (isHome(url) || url == "about:blank") "" else url)
    }

    private fun toUrl(s: String): String {
        val t = s.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> Settings.searchUrl(t)
            else -> "https://$t"
        }
    }

    private fun go(text: String) {
        if (text.isBlank()) return
        hideKb(urlBar)
        urlBar.clearFocus()
        current?.web?.loadUrl(toUrl(text))
    }

    private fun loadHome(w: WebView) = w.loadDataWithBaseURL(HOME, homeHtml(), "text/html", "utf-8", null)

    private fun hideKb(v: View) =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s))
        toast("Copied")
    }

    private fun share(s: String) = startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), "Share")
    )

    // ---------- find in page ----------

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        current?.web?.clearMatches()
        findInput.setText("")
        findBar.visibility = View.GONE
        hideKb(findInput)
    }

    // ---------- Via-style bottom sheet menu ----------

    private fun showMenu() {
        val t = current ?: return
        val url = t.web.url ?: ""

        val page1 = listOf(
            MenuSheet.Item("Night mode", R.drawable.ic_night) { toggleNight() },
            MenuSheet.Item("Bookmarks", R.drawable.ic_bookmark) {
                listLauncher.launch(Intent(this, ListPageActivity::class.java).putExtra("mode", "bookmarks"))
            },
            MenuSheet.Item("History", R.drawable.ic_history) {
                listLauncher.launch(Intent(this, ListPageActivity::class.java).putExtra("mode", "history"))
            },
            MenuSheet.Item("Downloads", R.drawable.ic_download) {
                startActivity(Intent(this, DownloadsActivity::class.java))
            },
            MenuSheet.Item("Incognito mode", R.drawable.ic_incognito) { newTab(null, true) },
            MenuSheet.Item("Share", R.drawable.ic_share) {
                if (url.startsWith("http") && !isHome(url)) share(url) else toast("Nothing to share")
            },
            MenuSheet.Item(
                if (store.isBookmarked(url)) "Remove bookmark" else "Add bookmark",
                R.drawable.ic_add_bookmark
            ) {
                if (url.startsWith("http") && !isHome(url))
                    toast(if (store.toggleBookmark(Store.Item(t.title, url))) "Bookmarked" else "Bookmark removed")
                else toast("Open a page first")
            },
            MenuSheet.Item("Desktop site", R.drawable.ic_desktop) { setDesktop(t, !t.desktop) },
            MenuSheet.Item("Tools", R.drawable.ic_tools) { showTools() },
            MenuSheet.Item("Settings", R.drawable.ic_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )

        val page2 = listOf(
            MenuSheet.Item("Ad blocker", R.drawable.ic_shield) {
                startActivity(Intent(this, AdBlockActivity::class.java))
            },
            MenuSheet.Item("Find in page", R.drawable.ic_find) { openFind() },
            MenuSheet.Item("Save page", R.drawable.ic_save) { savePage() },
            MenuSheet.Item("Translate", R.drawable.ic_translate) { translatePage() },
            MenuSheet.Item("Clear data", R.drawable.ic_clear) { clearData() },
            MenuSheet.Item("New tab", R.drawable.ic_newtab) { newTab() },
            MenuSheet.Item("Close tab", R.drawable.ic_close) { closeTab(cur) },
            MenuSheet.Item("Screenshot", R.drawable.ic_screenshot) { screenshot() },
            MenuSheet.Item("Fullscreen", R.drawable.ic_fullscreen) { toggleFullscreen() },
            MenuSheet.Item("About", R.drawable.ic_info) { aboutDialog() }
        )

        MenuSheet.show(this, page1, page2) {
            toast("Goodbye")
            finishAffinity()
        }
    }

    private fun showTools() {
        val t = current
        val mediaCount = t?.let { MediaSniffer.get(it.id).size } ?: 0
        val items = arrayOf(
            "Find in page", "Translate page", "Save page",
            if (mediaCount > 0) "Download media ($mediaCount)" else "Download media",
            if (Settings.keepScreenOn) "Keep screen on: ON" else "Keep screen on: OFF",
            "Add to home screen"
        )
        AlertDialog.Builder(this).setTitle("Tools")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> openFind()
                    1 -> translatePage()
                    2 -> savePage()
                    3 -> openMediaPicker()
                    4 -> toggleKeepOn()
                    else -> addToHome()
                }
            }.show()
    }

    private fun toggleKeepOn() {
        Settings.keepScreenOn = !Settings.keepScreenOn
        if (Settings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toast(if (Settings.keepScreenOn) "Keep screen on" else "Keep screen off")
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun aboutDialog() {
        AlertDialog.Builder(this).setTitle("Yulp")
            .setMessage(
                "Yulp browser v1.3.0\n\nBuilt-in features:\n" +
                    "• EXTREME ad blocker (166k domains, EasyList +\n  EasyPrivacy + AdGuard + StevenBlack engine)\n" +
                    "• Multi-thread background downloads\n" +
                    "• Media sniffer with quality picker (Tools menu)\n" +
                    "• Incognito tabs, night mode, tools\n\ngithub.com/devfahim00/yulp"
            )
            .setPositiveButton("OK", null).show()
    }

    private fun translatePage() {
        val t = current ?: return
        val u = t.web.url ?: return
        if (!u.startsWith("http") || isHome(u)) { toast("Open a page first"); return }
        try {
            val p = Uri.parse(u)
            val host = p.host ?: return
            val link = "https://" + host.replace(".", "-") + ".translate.goog" +
                (p.path ?: "") + "?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"
            current?.web?.loadUrl(link)
        } catch (_: Exception) {
            toast("Cannot translate this page")
        }
    }

    private fun addToHome() {
        val t = current ?: return
        val url = t.web.url ?: return
        if (!url.startsWith("http") || isHome(url)) { toast("Open a page first"); return }
        try {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(
                this, "yulp_${kotlin.math.abs(url.hashCode())}"
            )
                .setShortLabel(t.title.ifBlank { "Yulp" }.take(20))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))
                .build()
            if (androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            } else toast("Not supported by this launcher")
        } catch (_: Exception) {
            toast("Cannot add shortcut")
        }
    }

    /** Save the current page as a single-file web archive into Downloads. */
    private fun savePage() {
        val t = current ?: return
        val w = t.web
        val url = w.url ?: ""
        if (!url.startsWith("http") || isHome(url)) { toast("Open a page first"); return }
        val base = (t.title.ifBlank { "page" }).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val name = "$base.mht"
        toast("Saving $name …")
        Thread {
            try {
                val tmp = File(cacheDir, "arch_${System.currentTimeMillis()}.mht")
                w.saveWebArchive(tmp.absolutePath)
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "multipart/related")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IOException("Cannot create file")
                contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Cannot write file")
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                tmp.delete()
                runOnUiThread { toast("Saved $name to Downloads") }
            } catch (e: Exception) {
                runOnUiThread { toast("Save failed: ${e.message}") }
            }
        }.start()
    }

    /** Screenshot of the current page into Pictures. */
    private fun screenshot() {
        val t = current ?: return
        val w = t.web
        if (w.width <= 0 || w.height <= 0) { toast("Nothing to capture"); return }
        try {
            val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            w.draw(c)
            Thread {
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "yulp_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: throw IOException("Cannot create file")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    runOnUiThread { toast("Screenshot saved to Pictures") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Screenshot failed") }
                } finally {
                    bmp.recycle()
                }
            }.start()
        } catch (_: Exception) {
            toast("Screenshot failed")
        }
    }

    private fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.web.settings.apply {
            userAgentString = if (on) mobileUa
                .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "") else null
            useWideViewPort = on
            loadWithOverviewMode = on
        }
        t.web.reload()
    }

    private fun clearData() {
        store.clear("h")
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        tabs.forEach { it.web.clearCache(true); it.web.clearFormData() }
        toast("History, cookies and cache cleared")
    }

    // ---------- downloads (built-in multi-thread engine) ----------

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) { toast("Cannot download this link"); return }
        val guessed = try { URLUtil.guessFileName(url, cd, mime) } catch (_: Exception) { "download" }
        val headers = buildMap {
            ua?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            try {
                CookieManager.getInstance().getCookie(url)?.let { if (it.isNotBlank()) put("Cookie", it) }
            } catch (_: Exception) {}
            current?.web?.url?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
        }
        showDownloadDialog(url, guessed, mime, headers)
    }

    private fun showDownloadDialog(
        url: String, name: String, mime: String?, headers: Map<String, String>
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val nameInput = view.findViewById<EditText>(R.id.dlName)
        nameInput.setText(name)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.dlThreads)
        val btns = DownloadEngine.THREAD_OPTIONS.map { n ->
            group.getChildAt(DownloadEngine.THREAD_OPTIONS.indexOf(n)) as MaterialButton
        }
        val sel = DownloadEngine.THREAD_OPTIONS.indexOf(DownloadEngine.defaultThreads)
            .let { if (it >= 0) it else DownloadEngine.THREAD_OPTIONS.indexOf(4) }
        btns.getOrNull(sel)?.isChecked = true

        val dlg = AlertDialog.Builder(this)
            .setTitle("Download file")
            .setView(view)
            .setPositiveButton("Download") { _, _ ->
                val finalName = nameInput.text.toString().ifBlank { name }
                var threads = DownloadEngine.defaultThreads
                for (i in btns.indices) if (btns[i].isChecked) threads = DownloadEngine.THREAD_OPTIONS[i]
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                DownloadEngine.init(this, false)
                    .enqueue(url, finalName, mime, headers, threads)
                toast("Downloading $finalName · $threads thread" + if (threads > 1) "s" else "")
            }
            .setNegativeButton("Cancel", null)
            .show()
        dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------- built-in pages ----------

    private fun errorHtml(msg: String) = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;padding:32px;color:#444;text-align:center;margin-top:20vh}h2{margin-bottom:4px}</style></head>
<body><h2>Can't reach this page</h2><p>$msg</p></body></html>"""

    private fun homeHtml(): String {
        val searchAction = when (Settings.searchEngine) {
            "bing" -> "https://www.bing.com/search"
            "duckduckgo" -> "https://duckduckgo.com/"
            else -> "https://www.google.com/search"
        }
        val tiles = listOf(
            "Google" to "https://www.google.com", "YouTube" to "https://m.youtube.com",
            "Wikipedia" to "https://www.wikipedia.org", "GitHub" to "https://github.com",
            "Facebook" to "https://m.facebook.com", "X" to "https://x.com",
            "Reddit" to "https://www.reddit.com", "Gmail" to "https://mail.google.com"
        ).joinToString("") {
            "<a class=t href=\"${it.second}\"><b>${it.first.first()}</b><span>${it.first}</span></a>"
        }
        // Start-page palette follows the app night-mode setting directly (not
        // prefers-color-scheme) so it always matches the surrounding app UI.
        val dark = Settings.nightMode
        val rootVars = if (dark)
            "--bg:#121316;--fg:#e4e2e6;--card:#23252c;--ac:#9db0ff"
        else
            "--bg:#fff;--fg:#1b1b1f;--card:#eceefa;--ac:#4f6bff"
        return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:${if (dark) "dark" else "light"};$rootVars}
body{margin:0;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;padding:12vh 20px 0}
h1{font-size:44px;margin:0 0 24px;letter-spacing:-1px;color:var(--ac)}
form{width:100%;max-width:520px}
input{width:100%;box-sizing:border-box;border:0;border-radius:28px;background:var(--card);color:var(--fg);padding:16px 22px;font-size:16px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:36px;width:100%;max-width:520px}
.t{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:var(--fg);font-size:12px}
.t b{width:52px;height:52px;border-radius:16px;background:var(--card);display:flex;align-items:center;justify-content:center;font-size:22px;color:var(--ac);margin-bottom:6px}
</style></head><body><h1>Yulp</h1>
<form action="$searchAction"><input name=q placeholder="Search the web" autocomplete=off></form>
<div class=g>$tiles</div></body></html>"""
    }
}
