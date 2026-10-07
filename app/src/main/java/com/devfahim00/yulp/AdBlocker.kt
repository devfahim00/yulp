package com.devfahim00.yulp

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Ad / tracker blocker.
 *
 * - Thread-safe: [blocked] is called from WebView background threads
 *   (shouldInterceptRequest) so the hot path only touches immutable data.
 * - History recording happens on a single background executor so the
 *   WebView worker threads are never blocked by disk IO.
 */
object AdBlocker {

    data class BlockedEntry(val host: String, val url: String, val time: Long)

    @Volatile var enabled: Boolean = true
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<BlockedEntry>()
    private val totalBlocked = AtomicLong(0)
    private lateinit var prefs: android.content.SharedPreferences
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MutableList<BlockedEntry>) -> Unit>()

    /** Hostname -> rule that matched. Immutable after load. */
    private val exactHosts = HashSet<String>()
    private val hostSuffixes = HashSet<String>()
    private val urlContains = listOf(
        "/pagead/", "adsbygoogle", "googlesyndication", "google_ads", "doubleclick",
        "ad_banner", "/adserver", "adframe", "popunder", "/popads", "/ads.js",
        "prebid", "/adunit", "/banner/ad", "analytics.js", "gtag/js",
        "beacon.gv", "/pixel?", "trackingpixel", "/track.gif", "/adv?"
    )

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("adblock", Context.MODE_PRIVATE)
        loadBlocklist()
        enabled = prefs.getBoolean("enabled", true)
        totalBlocked.set(prefs.getLong("total", 0L))
        try {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                history.add(BlockedEntry(o.getString("h"), o.getString("u"), o.getLong("t")))
            }
        } catch (_: Exception) {
        }
    }

    private fun loadBlocklist() {
        // Exact hosts (any subdomain via suffix match below is enough for domains)
        val d = listOf(
            // Google ads / analytics
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com", "admob.com",
            "app-measurement.com", "firebase-settings.crashlytics.com", "pagead2.googlesyndication.com",
            "partner.googleadservices.com", "googlecommerce.com", "ad.doubleclick.net",
            // Meta / social trackers
            "connect.facebook.net", "graph.facebook.com", "pixel.facebook.com", "ads.facebook.com",
            "analytics.tiktok.com", "ads.tiktok.com", "business-api.tiktok.com", "analytics.twitter.com",
            "static.ads-twitter.com", "ads.linkedin.com", "snap.licdn.com", "px.ads.linkedin.com",
            // Big ad exchanges / SSP / DSP
            "adnxs.com", "adnxs-simple.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
            "criteo.net", "taboola.com", "outbrain.com", "openx.net", "casalemedia.com",
            "bidswitch.net", "smartadserver.com", "teads.tv", "adform.net", "33across.com",
            "sharethrough.com", "spotxchange.com", "spotx.tv", "springserve.com", "adsrvr.org",
            "2mdn.net", "moatads.com", "adsafeprotected.com", "doubleverify.com", "iasds01.com",
            "indexexchange.com", "appnexus.com", "yieldmo.com", "sonobi.com", "gumgum.com",
            "undertone.com", "exponential.com", "conversantmedia.com", "media.net", "servenobid.com",
            "cnt.informer.com", "adhigh.net", "adpone.com", "adtelligent.com", "advertising.com",
            "amazon-adsystem.com", "aan.amazon.com", "c.amazon-adsystem.com", "assoc-amazon.com",
            // Mobile ad networks
            "adcolony.com", "applovin.com", "inmobi.com", "mopub.com", "vungle.com",
            "chartboost.com", "unityads.unity3d.com", "unity3d.com", "tapjoy.com", "ironsrc.com",
            "fyber.com", "aerserv.com", "chartboost.net", "startappservice.com", "appodeal.com",
            "admob.alibaba.com", "ads.yahoo.com", "advertising.yandex.ru", "an.yandex.ru",
            // Pop / redirect networks
            "popads.net", "propellerads.com", "propellerclick.com", "exoclick.com", "exosrv.com",
            "hilltopads.net", "clickadu.com", "adskeeper.com", "mgid.com", "revcontent.com",
            "taboola.com", "zergnet.com", "engagetechnologies", "popcash.net", "adcash.com",
            "coinzilla.com", "a-ads.com", "adskeeper.co.uk", "onclickalgo.com", "onclasrv.com",
            // Analytics / tracking / fingerprinting
            "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com",
            "chartbeat.net", "mixpanel.com", "segment.io", "segment.com", "amplitude.com",
            "hotjar.com", "mouseflow.com", "fullstory.com", "clarity.ms", "budimedia.net",
            "branch.io", "appsflyer.com", "kochava.com", "adjust.com", "singular.net",
            "tenjin.io", "apsalar.com", "flurry.com", "localytics.com", "swrve.com",
            "crashlytics.com", "firebaseinstallations.googleapis.com", "demdex.net",
            "everesttech.net", "omtrdc.com", "2o7.net", "dpm.demdex.net", "krxd.net",
            "adsymptotic.com", "agkn.com", "pvtag.com", "ipify.org", "bfad.io", "bluekai.com",
            "id5-sync.com", "rlcdn.com", "crwdcntrl.net", "adsymptotic.net", "tapad.com",
            "ads.yieldmo.net", "eyeota.net", "adroll.com", "inspectlet.com", "luckyorange.com",
            // Ad-blocking bait / malvertising domains
            "adprotected.org", "ad-recovery.com", "anticheat.click", "gloatington.com",
            "jsecoin.com", "minero.cc", "coinhive.com", "crypto-loot.com", "authedmine.com",
            "adverpanic.com", "onclickmega.com", "hwfls.com", "broim.xyz", "pemsrv.com",
            "tsyndicate.com", "adcalls.ru", "adfox.ru", "adriver.ru"
        )
        for (h in d) {
            exactHosts.add(h)
            hostSuffixes.add(h)
        }
        // Hosts that must match exactly
        exactHosts.clear()
        val e = listOf("ads", "ad", "adservice", "adserver", "adsystem", "track", "tracker",
            "tracking", "analytics", "telemetry", "beacon", "pixel", "tags", "mtag")
        exactHosts.addAll(e)
    }

    /** @return matched rule string if this request must be blocked, else null. */
    fun blocked(url: String): String? {
        if (!enabled) return null
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val host = run {
            val noScheme = lower.substringAfter("://", lower)
            val h = noScheme.substringBefore('/').substringBefore(':')
            h
        }
        if (host.isEmpty()) return null

        // Never block the page the user is navigating to (main frame is not routed here,
        // but a few sites serve subresources from the same host).
        if (host == "localhost" || host.endsWith(".start")) return null

        if (exactHosts.contains(host)) return host
        // suffix match: "ads.example.com".endsWith(".adnxs.com") OR host == "adnxs.com"
        for (suf in hostSuffixes) {
            if (host == suf || host.endsWith(".$suf")) return suf
        }
        // quick path check (cheap contains on few literals)
        val path = lower.substringAfter("://", lower).substringAfter('/', "")
        if (path.isNotEmpty()) {
            for (p in urlContains) {
                if (lower.contains(p)) return p
            }
        }
        return null
    }

    /** Record a blocked request (called from IO executor only). */
    fun record(url: String, rule: String) {
        io.execute {
            val host = url.substringAfter("://").substringBefore('/')
            synchronized(history) {
                history.add(0, BlockedEntry(rule, url, System.currentTimeMillis()))
                if (history.size > 500) while (history.size > 500) history.removeAt(history.size - 1)
            }
            totalBlocked.incrementAndGet()
            persistAsync()
            notifyListeners()
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
    }

    fun toggle(ctx: Context): Boolean {
        setEnabled(ctx, !enabled)
        return enabled
    }

    fun total(): Long = totalBlocked.get()

    fun snapshot(): MutableList<BlockedEntry> = synchronized(history) { history.toMutableList() }

    fun clearHistory() {
        io.execute {
            synchronized(history) { history.clear() }
            persistAsync()
            notifyListeners()
        }
    }

    fun addListener(l: (MutableList<BlockedEntry>) -> Unit) { listeners.add(l); l(snapshot()) }
    fun removeListener(l: (MutableList<BlockedEntry>) -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        val snap = snapshot()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            listeners.forEach { it(snap) }
        }
    }

    private fun persistAsync() {
        val a = JSONArray()
        synchronized(history) {
            history.forEach { a.put(JSONObject().put("h", it.host).put("u", it.url).put("t", it.time)) }
        }
        prefs.edit()
            .putString("history", a.toString())
            .putLong("total", totalBlocked.get())
            .apply()
    }

    /** Empty 1x1 gif response fed to the WebView instead of the ad. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("image/gif", "base64",
            java.io.ByteArrayInputStream(
                android.util.Base64.decode(
                    "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
                    android.util.Base64.DEFAULT)))
}
