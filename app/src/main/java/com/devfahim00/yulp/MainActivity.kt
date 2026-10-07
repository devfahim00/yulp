package com.devfahim00.yulp

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private class Tab(val incognito: Boolean) {
        lateinit var web: WebView
        var title = "New tab"
        var desktop = false
    }

    private companion object {
        const val HOME = "https://yulp.start/"
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
    private lateinit var mobileUa: String

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        mobileUa = WebSettings.getDefaultUserAgent(this)

        container = findViewById(R.id.container)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        tabCount = findViewById(R.id.tabCount)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)

        urlBar.setOnEditorActionListener { v, actionId, e ->
            if (actionId == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(v.text.toString()); true
            } else false
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { current?.web?.reload() }
        findViewById<View>(R.id.btnBack).setOnClickListener { current?.web?.let { if (it.canGoBack()) it.goBack() } }
        findViewById<View>(R.id.btnForward).setOnClickListener { current?.web?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.btnHome).setOnClickListener { current?.web?.let { loadHome(it) } }
        findViewById<View>(R.id.btnTabs).setOnClickListener { showTabs() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        findInput.doAfterTextChanged { current?.web?.findAllAsync(it.toString()) }
        findViewById<View>(R.id.findNext).setOnClickListener { current?.web?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { current?.web?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustom()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    current?.web?.canGoBack() == true -> current?.web?.goBack()
                    tabs.size > 1 -> closeTab(cur)
                    else -> finish()
                }
            }
        })

        newTab(intent?.data?.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { newTab(it.toString()) }
    }

    override fun onPause() { current?.web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); current?.web?.onResume() }
    override fun onDestroy() { tabs.forEach { it.web.destroy() }; super.onDestroy() }

    // ---------- tabs ----------

    private fun newTab(url: String? = null, incognito: Boolean = false) {
        val t = Tab(incognito)
        t.web = makeWebView(t)
        tabs.add(t)
        switchTo(tabs.lastIndex)
        if (url == null) loadHome(t.web) else t.web.loadUrl(url)
    }

    private fun switchTo(i: Int) {
        closeFind()
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
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        container.removeView(t.web)
        t.web.destroy()
        cur = -1
        if (tabs.isEmpty()) newTab() else switchTo(minOf(i, tabs.lastIndex))
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t ->
            (if (i == cur) "● " else "") + (if (t.incognito) "🕶 " else "") + t.title.ifBlank { "New tab" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(names) { _, i -> switchTo(i) }
            .setPositiveButton("New tab") { _, _ -> newTab() }
            .setNegativeButton("Incognito") { _, _ -> newTab(null, true) }
            .setNeutralButton("Close tab") { _, _ -> closeTab(cur) }
            .show()
    }

    // ---------- webview ----------

    @SuppressLint("SetJavaScriptEnabled")
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
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !t.incognito)

        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }

        w.setOnLongClickListener {
            val r = w.hitTestResult
            val x = r.extra ?: return@setOnLongClickListener false
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                    AlertDialog.Builder(this).setTitle(x)
                        .setItems(arrayOf("Open in new tab", "Open in incognito", "Copy link", "Share link")) { _, i ->
                            when (i) {
                                0 -> newTab(x)
                                1 -> newTab(x, true)
                                2 -> copy(x)
                                else -> share(x)
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
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (t === current) { showUrl(url); progress.visibility = View.VISIBLE }
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.title = view.title ?: url
                if (t === current) { showUrl(url); progress.visibility = View.INVISIBLE }
                if (!t.incognito && url.startsWith("http") && !isHome(url)) {
                    store.addHistory(Store.Item(t.title, url))
                }
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

            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: "" }

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
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
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

    // ---------- menu ----------

    private fun showMenu(anchor: View) {
        val t = current ?: return
        val url = t.web.url ?: ""
        val m = PopupMenu(this, anchor)
        m.menu.apply {
            add(0, 1, 0, "New tab")
            add(0, 2, 0, "New incognito tab")
            add(0, 3, 0, "Close tab")
            add(0, 4, 0, if (store.isBookmarked(url)) "Remove bookmark" else "Add bookmark")
            add(0, 5, 0, "Bookmarks")
            add(0, 6, 0, "History")
            add(0, 7, 0, "Downloads")
            add(0, 8, 0, "Find in page")
            add(0, 9, 0, "Desktop site").apply { isCheckable = true; isChecked = t.desktop }
            add(0, 10, 0, "Share")
            add(0, 11, 0, "Clear browsing data")
        }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newTab()
                2 -> newTab(null, true)
                3 -> closeTab(cur)
                4 -> if (url.startsWith("http") && !isHome(url))
                    toast(if (store.toggleBookmark(Store.Item(t.title, url))) "Bookmarked" else "Bookmark removed")
                else toast("Open a page first")
                5 -> showList("Bookmarks", "b")
                6 -> showList("History", "h")
                7 -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                8 -> openFind()
                9 -> setDesktop(t, !t.desktop)
                10 -> if (url.startsWith("http")) share(url) else toast("Nothing to share")
                11 -> clearData()
            }
            true
        }
        m.show()
    }

    private fun showList(title: String, key: String) {
        val l = store.get(key)
        if (l.isEmpty()) { toast("Nothing here yet"); return }
        val labels = l.map { it.title.ifBlank { it.url } + "\n" + it.url }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(labels) { _, i -> current?.web?.loadUrl(l[i].url) }
            .setNegativeButton("Clear all") { _, _ -> store.clear(key); toast("Cleared") }
            .setPositiveButton("Close", null)
            .show()
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

    // ---------- downloads ----------

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                mime?.let { setMimeType(it) }
                ua?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    // ---------- built-in pages ----------

    private fun errorHtml(msg: String) = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;padding:32px;color:#444;text-align:center;margin-top:20vh}h2{margin-bottom:4px}</style></head>
<body><h2>Can't reach this page</h2><p>$msg</p></body></html>"""

    private fun homeHtml(): String {
        val tiles = listOf(
            "Google" to "https://www.google.com", "YouTube" to "https://m.youtube.com",
            "Wikipedia" to "https://www.wikipedia.org", "GitHub" to "https://github.com",
            "Facebook" to "https://m.facebook.com", "X" to "https://x.com",
            "Reddit" to "https://www.reddit.com", "Gmail" to "https://mail.google.com"
        ).joinToString("") {
            "<a class=t href=\"${it.second}\"><b>${it.first.first()}</b><span>${it.first}</span></a>"
        }
        return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#1b1b1f;--card:#eceefa;--ac:#4f6bff}
@media(prefers-color-scheme:dark){:root{--bg:#121316;--fg:#e4e2e6;--card:#23252c;--ac:#9db0ff}}
body{margin:0;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;padding:12vh 20px 0}
h1{font-size:44px;margin:0 0 24px;letter-spacing:-1px;color:var(--ac)}
form{width:100%;max-width:520px}
input{width:100%;box-sizing:border-box;border:0;border-radius:28px;background:var(--card);color:var(--fg);padding:16px 22px;font-size:16px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:36px;width:100%;max-width:520px}
.t{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:var(--fg);font-size:12px}
.t b{width:52px;height:52px;border-radius:16px;background:var(--card);display:flex;align-items:center;justify-content:center;font-size:22px;color:var(--ac);margin-bottom:6px}
</style></head><body><h1>Yulp</h1>
<form action="https://www.google.com/search"><input name=q placeholder="Search the web" autocomplete=off></form>
<div class=g>$tiles</div></body></html>"""
    }
}
