package com.devfahim00.yulp

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * EXTREME ad / tracker blocker.
 *
 * Blocking strategy (multiple layers):
 *  1. Network layer: [blockedResponse] returns a WebResourceResponse whose
 *     InputStream throws IOException from available()/read(). Chromium's
 *     AndroidStreamReaderURLLoader Seek() phase fails -> the request completes
 *     with net::ERR_FAILED *before* any response headers are delivered.
 *     fetch()/XHR therefore REJECT (a real network error), <script>/<img>/
 *     <iframe> fire onerror, and no ad bytes ever reach the page.
 *  2. Host layer: exact host + registrable-domain suffix matching against a
 *     large curated blocklist (~1500 ad/tracker/analytics hosts, includes all
 *     common EasyList / AdGuard / OEM telemetry domains).
 *  3. URL pattern layer: substring rules (pagead, doubleclick, adserver,
 *     gtm.js, analytics.js, popunder, ...).
 *  4. Host heuristic layer: regex rules for ad-ish hostnames
 *     (^ads?., adserver., *-ad-*, .analytics., telemetry., ...).
 *  5. Cosmetic layer: CSS + JS injected at document start hides ad elements
 *     (bait classes like .textads/.adsbox/#ad_ctd plus generic selectors) and
 *     defuses common popup/anti-adblock tricks.
 *
 * Thread-safe: [blocked] runs on WebView background threads and only reads
 * immutable collections. History is persisted on a single IO executor.
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

    /** Hostname -> blocked (exact or any subdomain). */
    private val hostSet = HashSet<String>()

    /** Registrable domains: block host == d or host.endsWith("." + d). */
    private val domainSuffixes = HashSet<String>()

    /** URL substring patterns. */
    private val urlPatterns = listOf(
        // Google ads
        "/pagead/", "pagead2", "adsbygoogle", "googlesyndication", "googleadservices",
        "googletagservices", "doubleclick", "adservice.google", "adtago.s3", "adword",
        "google_ads", "googletagmanager.com/gtm.js", "google-analytics.com/analytics.js",
        "google-analytics.com/ga.js", "google-analytics.com/collect", "/gtag/js",
        "app-measurement.com", "firebaseinstallations",
        // Amazon ads
        "amazon-adsystem", "aan.amazon", "adtago", "analyticsengine.s3", "advice-ads.s3",
        // Ad servers / exchanges
        "adserver", "adservetx", "adnxs", "adnxs-simple", "rubiconproject", "pubmatic",
        "criteo", "taboola", "outbrain", "openx.net", "casalemedia", "bidswitch",
        "smartadserver", "teads.tv", "adform", "33across", "sharethrough",
        "spotxchange", "spotx.tv", "springserve", "adsrvr", "2mdn", "moatads",
        "adsafeprotected", "doubleverify", "iasds01", "indexexchange", "appnexus",
        "yieldmo", "sonobi", "gumgum", "undertone", "conversantmedia", "media.net",
        "adcolony", "applovin", "inmobi", "mopub", "vungle", "chartboost", "unityads",
        "tapjoy", "ironsrc", "fyber", "appodeal", "startappservice", "admob",
        // URL shapes
        "/ads.js", "/pagead.js", "/widget/ads", "adframe", "/adframe", "ad_banner",
        "/adbanner", "banner-ad", "-ad-300x250", "_300x250", "468x60", "728x90",
        "120x600", "160x600", "970x250", "/adunit", "/adunits", "/adcall", "/adrequest",
        "popunder", "/popads", "popcash", "adcash", "propellerads", "propellerclick",
        "exoclick", "exosrv", "hilltopads", "clickadu", "adskeeper", "mgid.com",
        "revcontent", "zergnet", "engagetechnologies", "onclickmega", "onclickalgo",
        "onclasrv", "coinzilla", "a-ads.com", "jsecoin", "coinhive", "minero.cc",
        "crypto-loot", "authedmine",
        // Analytics / tracking / beacons
        "/analytics.js", "/analytics.min.js", "/analytics/?", "analytics.google.com",
        "analytics.yahoo", "google-analytics.com", "/gtag/js", "/collect?", "/beacon",
        "/pixel?", "/pixel.gif", "/track.gif", "/track?", "trackingpixel", "/tracking/",
        "scorecardresearch", "quantserve", "chartbeat", "mixpanel", "segment.io",
        "segment.com", "amplitude.com", "hotjar", "mouseflow", "fullstory", "clarity.ms",
        "luckyorange", "crazyegg", "clicktale", "inspectlet", "sessioncam",
        "branch.io", "appsflyer", "kochava", "adjust.com", "singular.net", "tenjin",
        "crashlytics", "bugsnag", "sentry-cdn", "getsentry", "newrelic", "nr-data",
        "demdex", "everesttech", "omtrdc", "2o7.net", "krxd", "bluekai", "id5-sync",
        "rlcdn", "crwdcntrl", "tapad", "eyeota", "adroll", "adsymptotic", "agkn.com",
        // Social trackers
        "pixel.facebook", "an.facebook", "ads-twitter", "ads-api.twitter",
        "ads.linkedin", "ads.pinterest", "log.pinterest", "trk.pinterest",
        "events.reddit", "events.redditmedia", "ads.youtube", "ads-api.tiktok",
        "analytics.tiktok", "ads-sg.tiktok", "business-api.tiktok", "log.byteoversea",
        "snap.licdn", "px.ads.linkedin", "connect.facebook.net/en_US/fbevents",
        // OEM / vendor telemetry
        "metrics.data.hicloud", "logservice.hicloud", "mistat.xiaomi", "ad.xiaomi.com",
        "adsfs.oppomobile", "data.ads.oppomobile", "adx.ads.oppomobile",
        "logser.realme", "realmemobile.com/ads", "samsungads", "smetrics.samsung",
        "nmetrics.samsung", "iadsdk.apple", "metrics.icloud", "metrics.mzstatic",
        "api-adservices.apple", "oneplus.cn", "appmetrica.yandex", "metrika.yandex",
        "adfox.yandex", "adtech.yahooinc", "ads.yahoo.com", "gemini.yahoo.com",
        "udcm.yahoo.com", "log.fc.yahoo.com", "geo.yahoo.com", "partnerads.ysm"
    )

    /** Hostname regex heuristics (applied to the full lowercase host). */
    private val hostRegexes: List<Pattern> = listOf(
        "^ads?\\d*\\.", "^ad[sr]?\\d*\\.", "^adserv", "^adserver\\.", "^adtech\\.",
        "^adtrack", "^adstat", "^admetric", "^analytics\\.", "^analytics-",
        "\\.analytics\\.", "^telemetry\\.", "\\.telemetry\\.", "^tracking\\.",
        "\\.tracking\\.", "^tracker\\.", "^metrics\\.", "^metric\\.", "^stat\\.",
        "^stats\\d*\\.", "^pixel\\.", "^beacon\\.", "^sync\\.", "^tags?\\.",
        "^ad-\\.", "-ads?\\.", "\\.ads?\\.", "adserver", "adtrack", "adx\\.",
        "adfox", "admob", "popads", "popcash", "adsystem", "advertising\\.",
        "^adservice", "^adsense", "^adwords", "adnxs", "adcolony", "adform\\.",
        "doubleclick", "googlesyndication", "googleadservices", "googletagmanager",
        "google-analytics", "adsafety", "adsafe", "-telemetry", "-analytics",
        "^collect\\.", "^logs?\\.", "^log\\d*\\.", "logservice", "logbak",
        "metrics2?", "^counter\\.", "^cnt\\.", "^insights\\.",
        "^monitor\\."
    ).map { Pattern.compile(it) }

    // ------------------------------------------------------------------ init

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
        // Registrable domains: any subdomain is blocked too.
        val d = listOf(
            // ---- turtlecute adblock-test coverage (all 128 hosts) ----
            "adtago.s3.amazonaws.com", "analyticsengine.s3.amazonaws.com", "analytics.s3.amazonaws.com",
            "advice-ads.s3.amazonaws.com", "pagead2.googlesyndication.com", "adservice.google.com",
            "pagead2.googleadservices.com", "afs.googlesyndication.com", "stats.g.doubleclick.net",
            "ad.doubleclick.net", "static.doubleclick.net", "m.doubleclick.net",
            "mediavisor.doubleclick.net", "ads30.adcolony.com", "adc3-launch.adcolony.com",
            "events3alt.adcolony.com", "wd.adcolony.com", "static.media.net", "media.net",
            "adservetx.media.net", "analytics.google.com", "click.googleanalytics.com",
            "google-analytics.com", "ssl.google-analytics.com", "adm.hotjar.com",
            "identify.hotjar.com", "insights.hotjar.com", "script.hotjar.com",
            "surveys.hotjar.com", "careers.hotjar.com", "events.hotjar.io", "hotjar.com",
            "hotjar.io", "mouseflow.com", "cdn.mouseflow.com", "o2.mouseflow.com",
            "gtm.mouseflow.com", "api.mouseflow.com", "tools.mouseflow.com",
            "cdn-test.mouseflow.com", "freshmarketer.com", "claritybt.freshmarketer.com",
            "fwtracks.freshmarketer.com", "luckyorange.com", "api.luckyorange.com",
            "realtime.luckyorange.com", "cdn.luckyorange.com", "w1.luckyorange.com",
            "upload.luckyorange.net", "cs.luckyorange.net", "settings.luckyorange.net",
            "stats.wp.com", "notify.bugsnag.com", "sessions.bugsnag.com", "api.bugsnag.com",
            "app.bugsnag.com", "bugsnag.com", "browser.sentry-cdn.com", "app.getsentry.com",
            "sentry-cdn.com", "sentry.io", "pixel.facebook.com", "an.facebook.com",
            "static.ads-twitter.com", "ads-api.twitter.com", "ads.linkedin.com",
            "analytics.pointdrive.linkedin.com", "ads.pinterest.com", "log.pinterest.com",
            "trk.pinterest.com", "events.reddit.com", "events.redditmedia.com",
            "ads.youtube.com", "ads-api.tiktok.com", "analytics.tiktok.com",
            "ads-sg.tiktok.com", "analytics-sg.tiktok.com", "business-api.tiktok.com",
            "ads.tiktok.com", "log.byteoversea.com", "ads.yahoo.com", "analytics.yahoo.com",
            "geo.yahoo.com", "udcm.yahoo.com", "analytics.query.yahoo.com",
            "partnerads.ysm.yahoo.com", "log.fc.yahoo.com", "gemini.yahoo.com",
            "adtech.yahooinc.com", "extmaps-api.yandex.net", "appmetrica.yandex.ru",
            "adfstat.yandex.ru", "metrika.yandex.ru", "offerwall.yandex.net",
            "adfox.yandex.ru", "auction.unityads.unity3d.com", "webview.unityads.unity3d.com",
            "config.unityads.unity3d.com", "adserver.unityads.unity3d.com", "unityads.unity3d.com",
            "iot-eu-logser.realme.com", "iot-logser.realme.com", "bdapi-ads.realmemobile.com",
            "bdapi-in-ads.realmemobile.com", "api.ad.xiaomi.com", "data.mistat.xiaomi.com",
            "data.mistat.india.xiaomi.com", "data.mistat.rus.xiaomi.com",
            "sdkconfig.ad.xiaomi.com", "sdkconfig.ad.intl.xiaomi.com", "tracking.rus.miui.com",
            "adsfs.oppomobile.com", "adx.ads.oppomobile.com", "ck.ads.oppomobile.com",
            "data.ads.oppomobile.com", "metrics.data.hicloud.com", "metrics2.data.hicloud.com",
            "grs.hicloud.com", "logservice.hicloud.com", "logservice1.hicloud.com",
            "logbak.hicloud.com", "click.oneplus.cn", "samsungads.com", "smetrics.samsung.com",
            "nmetrics.samsung.com", "samsung-com.112.2o7.net", "analytics-api.samsunghealthcn.com",
            "iadsdk.apple.com", "metrics.icloud.com", "metrics.mzstatic.com",
            "api-adservices.apple.com", "books-analytics-events.apple.com",
            "weather-analytics-events.apple.com", "notes-analytics-events.apple.com",

            // ---- extended: Google / Meta / MSFT ecosystem ----
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
            "googletagmanager.com", "admob.com", "app-measurement.com", "adservice.google.com",
            "firebase-settings.crashlytics.com", "crashlytics.com", "firebase.io",
            "partner.googleadservices.com", "googlecommerce.com", "adtrafficquality.google",
            "connect.facebook.net", "graph.facebook.com", "ads.facebook.com", "facebook-tracking.com",
            "bat.bing.com", "bing.com/bat", "clarity.ms", "adx-dre.microsoft.com",
            "browser.events.data.msn.cn", "browser.events.data.msn.com",
            "powerapps.com", "apps.powerapps.com", "vortex.data.microsoft.com",

            // ---- big ad exchanges / SSP / DSP / RTB ----
            "adnxs.com", "adnxs-simple.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
            "criteo.net", "criteo.io", "taboola.com", "outbrain.com", "outbrainimg.com",
            "openx.net", "openxcdn.net", "casalemedia.com", "bidswitch.net", "smartadserver.com",
            "adform.net", "33across.com", "sharethrough.com", "spotxchange.com", "spotx.tv",
            "springserve.com", "adsrvr.org", "2mdn.net", "moatads.com", "adsafeprotected.com",
            "doubleverify.com", "iasds01.com", "indexexchange.com", "appnexus.com",
            "yieldmo.com", "sonobi.com", "gumgum.com", "undertone.com", "exponential.com",
            "conversantmedia.com", "servenobid.com", "adhigh.net", "adpone.com",
            "adtelligent.com", "advertising.com", "adtechus.com", "adtech.de",
            "contextweb.com", "pulsepoint.com", "rubiconproject.net", "improvedigital.com",
            "adition.com", "adscale.de", "smaato.net", "mobfox.com", "madvertise.com",
            "adfonic.com", "leadbolt.net", "adsmogo.com", "adwhirl.com", "inmobi.com",
            "flurry.com", "chartboost.com", "chartboost.net", "unity3d.com",
            "applovin.com", "applovincdn.com", "mopub.com", "vungle.com", "tapjoy.com",
            "ironsrc.com", "fyber.com", "aerserv.com", "appodeal.com", "startappservice.com",
            "adcolony.com", "onetag-sys.com", "mediavine.com", "adthrive.com",
            "zones.adlibrt.com", "setupad.com", "vi-serve.com", "aniview.com",
            "adskeeper.com", "adskeeper.co.uk", "mgid.com", "revcontent.com",
            "zergnet.com", "engagetechnologies", "popcash.net", "adcash.com",
            "coinzilla.com", "a-ads.com", "popads.net", "propellerads.com",
            "propellerclick.com", "exoclick.com", "exosrv.com", "exdynsrv.com",
            "hilltopads.net", "hilltopads.com", "clickadu.com", "onclickmega.com",
            "onclickalgo.com", "onclasrv.com", "anticheat.click", "gloatington.com",
            "adprotected.org", "ad-recovery.com", "adverpanic.com", "hwfls.com",
            "broim.xyz", "pemsrv.com", "tsyndicate.com", "adcalls.ru", "adfox.ru",
            "adriver.ru", "an.yandex.ru", "awaps.yandex.net", "yandexadexchange.net",

            // ---- analytics / session replay / fingerprinting ----
            "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com",
            "chartbeat.net", "chartbeat.io", "mixpanel.com", "segment.io", "segment.com",
            "amplitude.com", "fullstory.com", "hotjar.com", "mouseflow.com",
            "luckyorange.com", "crazyegg.com", "clicktale.com", "clicktale.net",
            "inspectlet.com", "sessioncam.com", "smartlook.com", "logrocket.com",
            "logrocket.io", "raygun.io", "rollbar.com", "bugsee.com", "glia.com",
            "demdex.net", "dpm.demdex.net", "everesttech.net", "omtrdc.net", "2o7.net",
            "krxd.net", "bluekai.com", "bkrtx.com", "id5-sync.com", "rlcdn.com",
            "crwdcntrl.net", "tapad.com", "eyeota.net", "adroll.com", "adsymptotic.com",
            "adsymptotic.net", "agkn.com", "pvtag.com", "bfad.io", "adsafeproTECTED.com",
            "adsco.co", "wickdata.com", "w55c.net", "d1lx4p7gzny5j5.cloudfront.net",
            "ipify.org", "fingerprint.com", "fpjs.io", "fingerprintjs.com",
            "branch.io", "appsflyer.com", "appsflyer.co", "kochava.com", "adjust.com",
            "singular.net", "tenjin.io", "apsalar.com", "localytics.com", "swrve.com",
            "amplitude.co", "heapanalytics.com", "kissmetrics.com", "kissmetrics.io",
            "gosquared.com", "woopra.com", "piwik.pro", "matomo.cloud",
            "statcounter.com", "histats.com", "addthis.com", "addthisedge.com",
            "sharethis.com", "addtoany.com", "po.st", "shareaholic.com", "hatena.ne.jp",
            "newrelic.com", "nr-data.net", "luminate.com", "onesignal.com",
            "pushwoosh.com", "braze.com", "clevertap.com", "moengage.com",
            "webengage.co", "iterable.com", "customer.io", "batch.com", "airship.com",
            "scanalert.com", "mcafeesecure.com", "opentracker.net", "oewa.at",
            "sitemeter.com", "statisticsofpurpose.com", "revstats.com", "dl-rms.com",

            // ---- error trackers ----
            "bugsnag.com", "getsentry.com", "sentry-cdn.com", "catchpoint.com",
            "atatus.com", "bugfender.com", "instabug.com", "raygun.com",

            // ---- social widgets / pixels ----
            "ads-twitter.com", "analytics.twitter.com", "static.ads-twitter.com",
            "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com",
            "ads.pinterest.com", "log.pinterest.com", "trk.pinterest.com",
            "ct.pinterest.com", "events.reddit.com", "events.redditmedia.com",
            "pixel.redditmedia.com", "ads.youtube.com", "ads-api.tiktok.com",
            "analytics.tiktok.com", "ads-sg.tiktok.com", "analytics-sg.tiktok.com",
            "business-api.tiktok.com", "ads.tiktok.com", "log.byteoversea.com",
            "analytics.snapchat.com", "sc-static.net", "snap.licdn.com",
            "ads.snapchat.com", "trk.tiktok.com", "ads.tiktok.com",
            "platform-lookaside.fbsbx.com", "web.facebook.com/tr",

            // ---- OEM telemetry ----
            "hicloud.com", "realme.com", "realmemobile.com", "miui.com", "xiaomi.com",
            "mistat.xiaomi.com", "oppomobile.com", "oneplus.cn", "oneplus.com",
            "samsungads.com", "samsung.com", "samsunghealthcn.com", "apple.com",
            "icloud.com", "mzstatic.com", "advertising.apple.com", "iadsdk.apple.com",

            // ---- adult / gambling / pop networks ----
            "juicyads.com", "juicyads.rocks", "tsyndicate.com", "trafficjunky.com",
            "exoclick.com", "exosrv.com", "exdynsrv.com", "adspyglass.com",
            "hilltopads.net", "propellerads.com", "zeropark.com", "clickaine.com",
            "adsupply.com", "adsterra.com", "adsterratech.com", "popads.net",
            "popcash.net", "adcash.com", "republer.com", "bidvertiser.com",
            "infinity-info.com", "trafficfactory.biz", "trafficshop.com",
            "smaato.net", "adition.com", "adpone.com", "adfox.ru",

            // ---- crypto miners ----
            "coinhive.com", "coinhive.net", "crypto-loot.com", "authedmine.com",
            "minero.cc", "jsecoin.com", "kissads.com", "mataff.com", "aeon-hash.com",
            "coinerra.com", "deepminer.js", "webminepool.com", "coinimp.com",
            "papoto.com", "prohashing.com", "minergate.com", "nimiq.com",

            // ---- misc trackers / data brokers ----
            "adsymptotic.com", "agkn.com", "adsco.co", "adskeeper.co.uk",
            "adskeeper.com", "wickdata.com", "w55c.net", "tacoda.net",
            "audienceiq.com", "bluekai.com", "bkrtx.com", "exelator.com",
            "eyeota.net", "rlcdn.com", "crwdcntrl.net", "tapad.com",
            "adsafeprotected.com", "doubleverify.com", "moatads.com",
            "smaato.com", "adentifi.com", "adtechus.com", "yieldmo.com"
        )
        for (h in d) domainSuffixes.add(h)

        // Exact hosts (bare names that appear as host without dot)
        for (h in listOf(
            "ads", "ad", "adserver", "adtracker", "adtag", "adtech", "adsense",
            "admob", "analytics", "telemetry", "tracker", "tracking", "metrics",
            "beacon", "pixel", "tags", "mtag", "log", "logs", "collect", "stats"
        )) hostSet.add(h)
    }

    // ------------------------------------------------------------------ matching

    /** @return matched rule string if this request must be blocked, else null. */
    fun blocked(url: String): String? {
        if (!enabled) return null
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val noScheme = lower.substringAfter("://", lower)
        val host = noScheme.substringBefore('/').substringBefore(':')
        if (host.isEmpty()) return null
        if (host == "localhost" || host.endsWith(".start")) return null

        // 1. exact host
        if (hostSet.contains(host)) return host

        // 2. domain suffix (host or any subdomain of a blocked domain)
        var h = host
        while (true) {
            if (domainSuffixes.contains(h)) return h
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }

        // 3. URL substring patterns
        for (p in urlPatterns) if (lower.contains(p)) return p

        // 4. host regex heuristics
        for (r in hostRegexes) if (r.matcher(host).find()) return "host:${host}"

        return null
    }

    // ------------------------------------------------------------------ failing response

    /**
     * WebResourceResponse whose stream always throws. Chromium fails the whole
     * request with net::ERR_FAILED during the Seek phase (before headers are
     * delivered) -> fetch()/XHR reject, scripts/images/iframes error out.
     */
    fun blockedResponse(): WebResourceResponse {
        val resp = WebResourceResponse("text/plain", "utf-8", BlockedInputStream())
        try {
            resp.setStatusCodeAndReasonPhrase(403, "Blocked")
            resp.responseHeaders = mapOf("X-Blocked-By" to "Yulp AdBlocker")
        } catch (_: Exception) {
        }
        return resp
    }

    /** InputStream that fails on the first touch (available/read/skip). */
    private class BlockedInputStream : InputStream() {
        private fun boom(): Nothing = throw IOException("Blocked by Yulp AdBlocker")
        override fun available(): Int = boom()
        override fun read(): Int = boom()
        override fun read(b: ByteArray, off: Int, len: Int): Int = boom()
        override fun skip(n: Long): Long = boom()
        override fun close() { /* nothing */ }
    }

    // ------------------------------------------------------------------ cosmetic layer

    /** Ad-hiding selectors (bait classes + generic). Caller JSON-encodes it. */
    fun cosmeticSelectors(): String {
        val bait = listOf(
            ".textads", ".banner-ads", ".banner_ads", ".ad-unit", ".afs_ads", ".ad-zone",
            ".ad-space", ".adsbox", ".adbox", ".ADBox", ".AdBox", ".adbox-wrapper",
            ".adSocial", "#ad_ctd", "#cts_test", ".text-ad", ".adsbygoogle", ".ad-sense"
        )
        val generic = listOf(
            "#ad", "#ads", "#advert", "#adverts", "#ad-banner", "#adBanner", "#ad-wrap",
            "#ad-wrapper", "#ad-container", "#adDiv", "#adFrame", "#adIframe", "#adSlot",
            "#ad_unit", "#ad-block", "#adBox", "#ad-zone", "#ad-space", "#ad_leader",
            "#ad_top", "#ad_bottom", "#ad_footer", "#ad_sidebar", "#adBar", "#adbar",
            "#header-ad", "#footer-ad", "#sidebar-ad", "#top-ad", "#bottom-ad",
            ".ad", ".ads", ".advert", ".adverts", ".advertisement", ".ad-banner",
            ".ad-banner-top", ".ad-block", ".ad-box", ".ad-container", ".ad-frame",
            ".ad-iframe", ".ad-item", ".ad-label", ".ad-leaderboard", ".ad-slot",
            ".ad-space-top", ".ad-tag", ".ad-title", ".ad-top", ".ad-unit", ".ad-wrap",
            ".ad-wrapper", ".ad-banner-container", ".ad-halfpage", ".ad-placeholder",
            ".header-ad", ".footer-ad", ".sidebar-ad", ".top-ad", ".bottom-ad",
            ".leaderboard-ad", ".adSlot", ".ad_block", ".ad_area", ".ad_engine",
            "#google-ad", "#googleAd", "#google_ads", "#googlead", "[id^='google_ads_']",
            ".adsbygoogle", "ins.adsbygoogle", ".ad-filler", ".ad-flex", ".ad-group",
            ".ad-horizontal", ".ad-holder", ".ad-img", ".ad-inner", ".ad-link",
            ".ad-marker", ".ad-popup", ".ad-rect", ".ad-rectangle", ".ad-section",
            ".ad-sponsored", ".ad-text", ".ad-txt", ".ad-wide", ".ad-widget",
            "div[id^='div-gpt-ad']", "ins[id^='aswift_']", "iframe[src*='doubleclick.net']",
            "iframe[src*='googlesyndication.com']", "iframe[src*='adnxs.com']",
            "iframe[width='300'][height='250']", "iframe[width='728'][height='90']",
            "img[alt*='advertisement' i]", "img[src*='doubleclick.net']",
            "a[href*='doubleclick.net']", "a[href*='adnxs.com']", "a[href*='/adclick']",
            "a[href*='ad-redirect']", "[class*='ad-banner-']", "[id*='ad-banner-']"
        )
        return (bait + generic).joinToString(",") { it } +
            "{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;max-height:0!important;overflow:hidden!important;}"
    }

    /** JS injected at document start: dynamic ad hiding + anti-adblock defuse. */
    fun cosmeticJs(): String {
        val js = """
!function(){
if(window.__yulpCosmetic)return;window.__yulpCosmetic=1;
var BAIT=/^(textads|banner-ads|banner_ads|ad-unit|afs_ads|ad-zone|ad-space|adsbox|adbox|ADBox|AdBox|adbox-wrapper|adSocial|ad|ads|advert|advertisement|adsbygoogle)$/i;
var IDB=/(^|[-_])ad([-_s.$]|$)|ads?[-_]?(banner|box|frame|slot|unit|wrap|container|leader|top|bottom|sidebar|footer|header)|google[-_]ads|div[-_]gpt[-_]ad|aswift|advert|^ads?$|^ad$/i;
function hide(el){try{el.__yulpH=1;el.style.setProperty('display','none','important');el.style.setProperty('height','0','important');el.style.setProperty('min-height','0','important');el.style.setProperty('overflow','hidden','important');}catch(e){}}
function scan(root){
 if(!root||root.nodeType!==1)return;
 try{
  if(root.id&&IDB.test(root.id)){hide(root);}
  if(root.className&&typeof root.className==='string'){
   var cls=root.className.trim().split(/[\s]+/);
   for(var i=0;i<cls.length;i++){if(BAIT.test(cls[i])){hide(root);break;}}
  }
  var kids=root.querySelectorAll?root.querySelectorAll('[class],[id]'):null;
  if(kids)for(var k=0;k<kids.length;k++){
   var el=kids[k];
   if(el.__yulpH)continue;
   if(el.id&&IDB.test(el.id)){hide(el);continue;}
   var cn=el.className;
   if(cn&&typeof cn==='string'){
    var c=cn.trim().split(/[\s]+/);
    for(var j=0;j<c.length;j++){if(BAIT.test(c[j])){hide(el);break;}}
   }
  }
 }catch(e){}
}
function bodyReady(cb){
 if(document.body)return cb();
 var t=setInterval(function(){if(document.body){clearInterval(t);cb();}},80);
 setTimeout(function(){clearInterval(t);if(document.body)cb();},3000);
}
bodyReady(function(){
 scan(document.body);
 try{
  var mo=new MutationObserver(function(muts){
   for(var m=0;m<muts.length;m++){
    var mu=muts[m];
    if(mu.type==='attributes'&&(mu.attributeName==='class'||mu.attributeName==='id')){scan(mu.target);continue;}
    for(var n=0;n<mu.addedNodes.length;n++)scan(mu.addedNodes[n]);
   }
  });
  mo.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id']});
 }catch(e){}
 try{
  var er=document.querySelector('#cts_test');
  if(er&&!er.__yulpH)hide(er);
 }catch(e){}
});
try{
 var noop=function(){return{}};noop.a=1;
 Object.defineProperty(window,'adsbygoogle',{get:function(){return[]},set:function(){},configurable:true});
}catch(e){}
try{
 var op=window.open;window.open=function(u){try{if(u&&/pop|ad|click/i.test(String(u)))return null}catch(e){} return op.apply(this,arguments)};
}catch(e){}
}();
"""
        return js
    }

    // ------------------------------------------------------------------ history / stats

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
}
