package com.devfahim00.yulp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * Lists every video/audio resource detected on the current page (DOM scan +
 * network sniff), expands HLS master playlists into their quality variants
 * and hands the chosen one to the multi-thread download engine.
 */
class MediaPickerActivity : AppCompatActivity() {

    private val media = mutableListOf<MediaSniffer.Media>()
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private var tabId = -1
    private var pageUrl = ""
    private var ua: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_picker)
        tabId = intent.getIntExtra("tabId", -1)
        pageUrl = intent.getStringExtra("pageUrl") ?: ""
        ua = intent.getStringExtra("ua") ?: ""

        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = Adapter()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
            subtitle = pageUrl.substringAfter("://").substringBefore('/')
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_refresh -> { reload(true); true }
                    else -> false
                }
            }
        }
        reload(expand = true)
    }

    private fun reload(expand: Boolean) {
        media.clear()
        media.addAll(MediaSniffer.get(tabId))
        recycler.adapter?.notifyDataSetChanged()
        empty.visibility = if (media.isEmpty()) View.VISIBLE else View.GONE

        // expand HLS master playlists into quality variants
        if (expand) {
            val headers = requestHeaders()
            val hlsItems = media.filter { it.isHls() }.toList()
            for (h in hlsItems) {
                MediaSniffer.expandHls(h.url, headers) { variants ->
                    if (variants.isNotEmpty()) {
                        // replace the master entry with its variants
                        val idx = media.indexOfFirst { it.url == h.url }
                        if (idx >= 0) {
                            media.removeAt(idx)
                            media.addAll(idx.coerceAtMost(media.size), variants)
                            recycler.adapter?.notifyDataSetChanged()
                            empty.visibility = if (media.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun requestHeaders(): Map<String, String> = buildMap {
        if (ua.isNotBlank()) put("User-Agent", ua)
        try {
            CookieManager.getInstance().getCookie(pageUrl)?.let { if (it.isNotBlank()) put("Cookie", it) }
        } catch (_: Exception) {}
        if (pageUrl.startsWith("http")) put("Referer", pageUrl)
    }

    // ---------------------------------------------------------------- adapter

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

        override fun getItemCount(): Int = media.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = media[pos]
            h.icon.setImageResource(
                when {
                    m.isHls() -> R.drawable.ic_video
                    m.type == "audio" -> R.drawable.ic_audio
                    else -> R.drawable.ic_video
                }
            )
            h.title.text = guessName(m)
            h.sub.text = buildString {
                if (m.label.isNotBlank()) append(m.label)
                else if (m.h > 0) append("${m.h}p")
                else if (m.isHls()) append("HLS")
                else append(if (m.type == "audio") "Audio" else "Video")
                if (m.dur > 0) append(" · ${fmtDur(m.dur)}")
                if (m.src == "net") append(" · network")
                append(" · ${m.url.substringAfter("://").substringBefore('/')}")
            }
            h.dl.setOnClickListener { showDownloadDialog(m) }
            h.itemView.setOnClickListener { showDownloadDialog(m) }
        }
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.mIcon)
        val title: TextView = v.findViewById(R.id.mTitle)
        val sub: TextView = v.findViewById(R.id.mSub)
        val dl: ImageButton = v.findViewById(R.id.mDownload)
    }

    private fun fmtDur(d: Double): String {
        val s = d.toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun guessName(m: MediaSniffer.Media): String {
        if (m.isHls()) {
            val base = m.niceName().removeSuffix(".m3u8").ifBlank { "video" }
            return "$base.ts"
        }
        var n = m.niceName()
        if (!n.contains('.')) n += if (m.type == "audio") ".mp3" else ".mp4"
        return n
    }

    // ---------------------------------------------------------------- download

    @SuppressLint("InflateParams")
    private fun showDownloadDialog(m: MediaSniffer.Media) {
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val nameInput = view.findViewById<EditText>(R.id.dlName)
        nameInput.setText(guessName(m))
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.dlThreads)
        val btns = DownloadEngine.THREAD_OPTIONS.map { n ->
            group.getChildAt(DownloadEngine.THREAD_OPTIONS.indexOf(n)) as MaterialButton
        }
        val sel = DownloadEngine.THREAD_OPTIONS.indexOf(DownloadEngine.defaultThreads)
            .let { if (it >= 0) it else DownloadEngine.THREAD_OPTIONS.indexOf(4) }
        btns.getOrNull(sel)?.isChecked = true

        val isHls = m.isHls()
        AlertDialog.Builder(this)
            .setTitle(if (isHls) "Download HLS stream" else "Download media")
            .setMessage(if (isHls) "Segments are fetched in parallel and merged into one file." else null)
            .setView(view)
            .setPositiveButton("Download") { _, _ ->
                val name = nameInput.text.toString().ifBlank { guessName(m) }
                var threads = DownloadEngine.defaultThreads
                for (i in btns.indices) if (btns[i].isChecked) threads = DownloadEngine.THREAD_OPTIONS[i]
                val mime = when {
                    isHls -> "video/mp2t"
                    m.type == "audio" -> "audio/mpeg"
                    else -> "video/mp4"
                }
                DownloadEngine.init(this, false)
                    .enqueue(m.url, name, mime, requestHeaders(), threads, hls = isHls)
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
