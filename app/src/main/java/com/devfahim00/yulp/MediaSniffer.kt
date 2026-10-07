package com.devfahim00.yulp

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Media sniffer: collects downloadable video/audio URLs for each tab from
 * two sources:
 *  1. DOM scan (injected JS): <video>/<audio>/<source> elements with quality info.
 *  2. Network sniff (shouldInterceptRequest): URLs that look like media
 *     (.mp4/.webm/.m3u8/.mp3/... or YouTube googlevideo videoplayback).
 *
 * The floating download button shows/hides based on [get] and opens the
 * media picker (all qualities) for the current tab.
 */
object MediaSniffer {

    data class Media(
        val url: String,
        val type: String,      // video | audio | hls | dash
        var label: String,     // quality label e.g. "1080p", "audio 128 kbps"
        var w: Int = 0,
        var h: Int = 0,
        var kbps: Long = 0,    // bandwidth (hls variants)
        var dur: Double = 0.0, // seconds (if known)
        var size: Long = -1L,  // bytes (if known)
        val src: String = "dom" // dom | net
    ) {
        fun isHls() = type == "hls" || url.substringBefore('?').endsWith(".m3u8")
        fun niceName(): String {
            val path = url.substringBefore('?').substringAfterLast('/', "video")
            return java.net.URLDecoder.decode(path, "UTF-8").ifBlank { "media" }
        }
    }

    private const val MAX_PER_TAB = 48

    /** tab id -> LinkedHashMap<urlKey, Media> (insertion order preserved). */
    private val store = HashMap<Int, LinkedHashMap<String, Media>>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    fun addListener(l: (Int) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Int) -> Unit) { listeners.remove(l) }
    private fun notifyChange(tabId: Int) {
        main.post { listeners.forEach { it(tabId) } }
    }

    fun put(tabId: Int, m: Media): Boolean {
        var changed = false
        synchronized(store) {
            var map = store[tabId]
            if (map == null) { map = LinkedHashMap(); store[tabId] = map }
            val key = urlKey(m.url)
            val old = map[key]
            if (old == null) {
                if (map.size < MAX_PER_TAB) { map[key] = m; changed = true }
            } else {
                // keep the richer info
                if (m.label.isNotBlank() && old.label.isBlank()) { old.label = m.label; changed = true }
                if (m.w > old.w) { old.w = m.w; changed = true }
                if (m.h > old.h) { old.h = m.h; changed = true }
                if (m.kbps > old.kbps) { old.kbps = m.kbps; changed = true }
                if (m.dur > old.dur) { old.dur = m.dur; changed = true }
                if (m.size > old.size) { old.size = m.size; changed = true }
            }
        }
        if (changed) notifyChange(tabId)
        return changed
    }

    fun putAll(tabId: Int, list: List<Media>) {
        var changed = false
        for (m in list) if (putSilent(tabId, m)) changed = true
        if (changed) notifyChange(tabId)
    }

    private fun putSilent(tabId: Int, m: Media): Boolean {
        synchronized(store) {
            var map = store[tabId]
            if (map == null) { map = LinkedHashMap(); store[tabId] = map }
            val key = urlKey(m.url)
            val old = map[key]
            if (old == null) {
                if (map.size < MAX_PER_TAB) { map[key] = m; return true }
                return false
            }
            var ch = false
            if (m.label.isNotBlank() && old.label.isBlank()) { old.label = m.label; ch = true }
            if (m.w > old.w) { old.w = m.w; ch = true }
            if (m.h > old.h) { old.h = m.h; ch = true }
            if (m.kbps > old.kbps) { old.kbps = m.kbps; ch = true }
            if (m.dur > old.dur) { old.dur = m.dur; ch = true }
            if (m.size > old.size) { old.size = m.size; ch = true }
            return ch
        }
    }

    fun get(tabId: Int): List<Media> = synchronized(store) {
        store[tabId]?.values?.toList() ?: emptyList()
    }

    fun clear(tabId: Int) {
        synchronized(store) { store.remove(tabId) }
        notifyChange(tabId)
    }

    private fun urlKey(u: String): String {
        // strip volatile query parts for dedupe (keep essentials)
        val base = u.substringBefore('#')
        return if (base.length > 300) base.substring(0, 300) else base
    }

    // ------------------------------------------------------------- network sniff

    private val mediaExt = Regex(
        "\\.(mp4|webm|mkv|mov|avi|flv|m4v|3gp|ts|mp3|m4a|aac|ogg|oga|opus|flac|wav|m3u8|mpd)(\\?|&|\$)",
        RegexOption.IGNORE_CASE
    )

    /** @return media type if this URL looks like a media resource, else null. */
    fun sniffUrl(url: String): String? {
        val u = url.lowercase()
        if (!u.startsWith("http")) return null
        if ("videoplayback" in u || "googlevideo.com" in u) {
            val itag = Regex("[?&]itag=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            return if (itag in AUDIO_ITAGS) "audio" else "video"
        }
        if (u.contains(".m3u8")) return "hls"
        if (u.contains(".mpd")) return "dash"
        val m = mediaExt.find(u) ?: return null
        return when (m.groupValues[1].lowercase()) {
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav" -> "audio"
            else -> "video"
        }
    }

    /** YouTube itag -> label. */
    fun itagLabel(url: String): String {
        val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        return ITAG_LABELS[itag] ?: ""
    }

    private val AUDIO_ITAGS = setOf(139, 140, 141, 171, 249, 250, 251, 256, 258, 327, 599, 600, 774)

    private val ITAG_LABELS = mapOf(
        17 to "144p · 3GP", 18 to "360p · MP4", 22 to "720p · MP4", 37 to "1080p · MP4",
        43 to "360p · WebM", 44 to "480p · WebM", 45 to "720p · WebM", 46 to "1080p · WebM",
        59 to "480p · MP4", 78 to "480p · MP4", 82 to "360p 3D", 83 to "480p 3D",
        84 to "720p 3D", 85 to "1080p 3D", 91 to "144p · HLS", 92 to "240p · HLS",
        93 to "360p · HLS", 94 to "480p · HLS", 95 to "720p · HLS", 96 to "1080p · HLS",
        100 to "360p 3D WebM", 101 to "480p 3D WebM", 102 to "720p 3D WebM",
        133 to "240p · video only", 134 to "360p · video only", 135 to "480p · video only",
        136 to "720p · video only", 137 to "1080p · video only", 138 to "2160p · video only",
        139 to "audio 48 kbps · M4A", 140 to "audio 128 kbps · M4A", 141 to "audio 256 kbps · M4A",
        160 to "144p · video only", 171 to "audio 128 kbps · WebM",
        242 to "240p · WebM vo", 243 to "360p · WebM vo", 244 to "480p · WebM vo",
        245 to "480p · WebM vo", 246 to "480p · WebM vo", 247 to "720p · WebM vo",
        248 to "1080p · WebM vo", 249 to "audio 50 kbps · Opus", 250 to "audio 70 kbps · Opus",
        251 to "audio 128 kbps · Opus", 256 to "audio 192 kbps", 258 to "audio 384 kbps",
        264 to "1440p · video only", 266 to "2160p · video only",
        271 to "1440p · WebM vo", 272 to "2160p · WebM vo",
        298 to "720p60 · video only", 299 to "1080p60 · video only",
        302 to "720p60 · WebM vo", 303 to "1080p60 · WebM vo",
        308 to "1440p60 · WebM vo", 313 to "2160p · WebM vo", 315 to "2160p60 · WebM vo",
        599 to "audio 30 kbps", 600 to "audio 35 kbps", 774 to "audio Opus"
    )

    // ------------------------------------------------------------- injected JS

    /** JS injected into every page: scans DOM media and reports via YulpMedia bridge. */
    fun sniffJs(): String = """
!function(){
if(window.__yulpSniff)return;window.__yulpSniff=1;
function typeOf(u,t){
 u=String(u||'');
 if(/\.m3u8(\?|&|#|$)/i.test(u))return 'hls';
 if(/\.mpd(\?|&|#|$)/i.test(u))return 'dash';
 if(t&&/audio/i.test(t))return 'audio';
 if(/\.(mp3|m4a|aac|ogg|opus|flac|wav)(\?|&|#|$)/i.test(u))return 'audio';
 return 'video';
}
function ok(u){return u&&!/^(blob|data|mediasource):/i.test(u)&&/^https?:/i.test(u)}
function q(){
 var out=[];
 try{
  var vs=document.querySelectorAll('video');
  for(var i=0;i<vs.length;i++){
   var v=vs[i];
   var vu=v.currentSrc||v.src||'';
   if(ok(vu)){
    var hh=v.videoHeight||0,ww=v.videoWidth||0;
    out.push({u:vu,t:typeOf(vu,v.querySelector('source')?v.querySelector('source').type:''),w:ww,h:hh,d:v.duration||0,l:hh?hh+'p':''});
   }
   var ss=v.querySelectorAll('source');
   for(var j=0;j<ss.length;j++){
    var su=ss[j].src||ss[j].getAttribute('src')||'';
    if(ok(su)){
     var l=ss[j].getAttribute('label')||ss[j].getAttribute('res')||ss[j].getAttribute('data-quality')||ss[j].getAttribute('title')||'';
     out.push({u:su,t:typeOf(su,ss[j].type),l:l,d:v.duration||0});
    }
   }
  }
  var as=document.querySelectorAll('audio');
  for(var k=0;k<as.length;k++){
   var a=as[k];
   var au=a.currentSrc||a.src||'';
   if(ok(au))out.push({u:au,t:'audio',d:a.duration||0,l:''});
   var as2=a.querySelectorAll('source');
   for(var m=0;m<as2.length;m++){
    var u2=as2[m].src||'';
    if(ok(u2))out.push({u:u2,t:'audio',d:a.duration||0,l:''});
   }
  }
 }catch(e){}
 return out;
}
function rep(){
 try{
  var a=q();
  if(a.length&&window.YulpMedia&&window.YulpMedia.report)window.YulpMedia.report(JSON.stringify(a));
 }catch(e){}
}
rep();
var tm=setInterval(rep,2500);
setTimeout(function(){clearInterval(tm)},120000);
try{
 var mo=new MutationObserver(function(){rep()});
 mo.observe(document.documentElement,{childList:true,subtree:true});
 setTimeout(function(){try{mo.disconnect()}catch(e){}},120000);
}catch(e){}
}();
"""

    /** Parse the JSON reported by the injected JS. */
    fun parseReport(tabId: Int, json: String) {
        try {
            val a = JSONArray(json)
            val list = mutableListOf<Media>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val u = o.optString("u")
                if (u.isBlank()) continue
                list.add(
                    Media(
                        url = u,
                        type = o.optString("t", "video"),
                        label = o.optString("l", ""),
                        w = o.optInt("w", 0),
                        h = o.optInt("h", 0),
                        dur = o.optDouble("d", 0.0),
                        src = "dom"
                    )
                )
            }
            if (list.isNotEmpty()) putAll(tabId, list)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- HLS variants

    /**
     * Fetch an HLS master playlist and report its variants (qualities) back on
     * the main thread. Used by the picker to show all qualities.
     */
    fun expandHls(
        masterUrl: String, headers: Map<String, String>,
        cb: (List<Media>) -> Unit
    ) {
        io.execute {
            val out = mutableListOf<Media>()
            try {
                val text = fetchText(masterUrl, headers)
                if (text != null) {
                    val lines = text.lines()
                    var i = 0
                    while (i < lines.size) {
                        val l = lines[i].trim()
                        if (l.startsWith("#EXT-X-STREAM-INF")) {
                            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)?.groupValues
                            var j = i + 1
                            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                            if (j < lines.size) {
                                val uri = java.net.URI(masterUrl).resolve(lines[j].trim()).toString()
                                val h = res?.get(2)?.toIntOrNull() ?: 0
                                val w = res?.get(1)?.toIntOrNull() ?: 0
                                val label = buildString {
                                    if (h > 0) append("${h}p") else append("HLS")
                                    if (bw > 0) append(" · ${String.format("%.1f", bw / 1000.0 / 1000.0)} Mbps")
                                }
                                out.add(Media(uri, "hls", label, w, h, bw, src = "net"))
                            }
                            i = j
                        }
                        i++
                    }
                }
            } catch (_: Exception) {
            }
            main.post { cb(out) }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.addRequestProperty(k, v) }
            if (c.responseCode !in 200..399) { c.disconnect(); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
