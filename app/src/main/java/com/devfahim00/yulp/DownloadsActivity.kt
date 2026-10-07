package com.devfahim00.yulp

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads page: live progress, speed, pause/resume/cancel/restart, thread info. */
class DownloadsActivity : AppCompatActivity(), DownloadEngine.Listener {

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val status: TextView = v.findViewById(R.id.status)
        val bar: LinearProgressIndicator = v.findViewById(R.id.bar)
        val threads: TextView = v.findViewById(R.id.threads)
        val action: ImageButton = v.findViewById(R.id.action)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_download, p, false))

        override fun getItemCount() = tasks.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val t = tasks.getOrNull(pos) ?: return
            h.name.text = t.name
            h.icon.setImageResource(iconFor(t))

            val sb = StringBuilder()
            when (t.status) {
                DownloadEngine.Status.RUNNING -> {
                    sb.append(if (t.total > 0) "${t.progressPct()}%" else DownloadEngine.fmt(t.downloaded))
                    DownloadEngine.fmtSpeed(t.speed).let { if (it.isNotEmpty()) sb.append(" · ").append(it) }
                    sb.append(" · ${DownloadEngine.fmt(t.downloaded)}")
                    if (t.total > 0) sb.append(" / ").append(DownloadEngine.fmt(t.total))
                }
                DownloadEngine.Status.PAUSED -> sb.append("Paused · ${DownloadEngine.fmt(t.downloaded)}")
                DownloadEngine.Status.COMPLETED -> sb.append("Done · ${DownloadEngine.fmt(t.downloaded)}")
                DownloadEngine.Status.FAILED -> sb.append("Failed · ${t.error ?: "error"}")
                DownloadEngine.Status.QUEUED -> sb.append("Queued")
            }
            h.status.text = sb.toString()
            h.threads.visibility =
                if (t.threads > 1 && t.status != DownloadEngine.Status.COMPLETED) View.VISIBLE else View.GONE
            h.threads.text = "×${t.threads}"

            if (t.total > 0 && t.status != DownloadEngine.Status.COMPLETED) {
                h.bar.visibility = View.VISIBLE
                h.bar.setIndeterminate(false)
                h.bar.max = 100
                h.bar.setProgressCompat(t.progressPct(), true)
            } else if (t.status == DownloadEngine.Status.RUNNING && t.total <= 0) {
                h.bar.visibility = View.VISIBLE
                h.bar.setIndeterminate(true)
            } else {
                h.bar.visibility = View.GONE
            }

            h.action.setImageResource(when (t.status) {
                DownloadEngine.Status.RUNNING -> R.drawable.ic_pause
                DownloadEngine.Status.PAUSED, DownloadEngine.Status.QUEUED, DownloadEngine.Status.FAILED -> R.drawable.ic_play
                else -> R.drawable.ic_open
            })
            h.action.setOnClickListener { quickAction(t) }
            h.itemView.setOnClickListener { itemMenu(t) }
            h.itemView.setOnLongClickListener { itemMenu(t); true }
        }
    }

    private val tasks = mutableListOf<DownloadEngine.Task>()
    private lateinit var engine: DownloadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        engine = DownloadEngine.init(this, autoResume = false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.actThreads -> { pickDefaultThreads(); true }
                R.id.actClear -> {
                    engine.clearFinished()
                    true
                }
                else -> false
            }
        }

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = Adapter()

        engine.addListener(this)
        updateSubtitle()
    }

    override fun onDestroy() {
        engine.removeListener(this)
        super.onDestroy()
    }

    override fun onChanged(list: List<DownloadEngine.Task>) {
        tasks.clear()
        tasks.addAll(list)
        findViewById<RecyclerView>(R.id.recycler).adapter?.notifyDataSetChanged()
        updateSubtitle()
        updateEmpty()
    }

    private fun updateSubtitle() {
        val active = tasks.count { it.isActive() }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = when {
            active > 0 -> "$active running · default ${engine.defaultThreads} threads"
            else -> "default ${engine.defaultThreads} threads"
        }
    }

    private fun updateEmpty() {
        findViewById<View>(R.id.empty).visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun quickAction(t: DownloadEngine.Task) {
        when (t.status) {
            DownloadEngine.Status.RUNNING -> engine.pause(t)
            DownloadEngine.Status.PAUSED, DownloadEngine.Status.QUEUED -> engine.resume(t)
            DownloadEngine.Status.FAILED -> engine.restart(t)
            DownloadEngine.Status.COMPLETED -> openFile(t)
        }
    }

    private fun itemMenu(t: DownloadEngine.Task) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        when (t.status) {
            DownloadEngine.Status.RUNNING -> {
                options.add("Pause"); actions.add { engine.pause(t) }
                options.add("Cancel & delete"); actions.add { confirmCancel(t) }
            }
            DownloadEngine.Status.PAUSED, DownloadEngine.Status.QUEUED -> {
                options.add("Resume"); actions.add { engine.resume(t) }
                options.add("Cancel & delete"); actions.add { confirmCancel(t) }
            }
            DownloadEngine.Status.FAILED -> {
                options.add("Retry"); actions.add { engine.restart(t) }
                options.add("Remove from list"); actions.add { engine.remove(t) }
            }
            DownloadEngine.Status.COMPLETED -> {
                options.add("Open file"); actions.add { openFile(t) }
                options.add("Remove from list"); actions.add { engine.remove(t) }
            }
        }
        options.add("Copy link"); actions.add { copyLink(t.url) }
        options.add("Restart with ${engine.defaultThreads} threads"); actions.add { engine.restart(t) }
        if (t.status == DownloadEngine.Status.PAUSED || t.status == DownloadEngine.Status.QUEUED
            || t.status == DownloadEngine.Status.FAILED
        ) {
            options.add("Change threads & resume"); actions.add { changeThreads(t) }
        }

        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(options.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmCancel(t: DownloadEngine.Task) {
        AlertDialog.Builder(this)
            .setTitle("Cancel download?")
            .setMessage("The partially downloaded file will be deleted.")
            .setPositiveButton("Cancel download") { _, _ -> engine.cancel(t) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun changeThreads(t: DownloadEngine.Task) {
        val opts = DownloadEngine.THREAD_OPTIONS
        val labels = opts.map { if (it == 1) "1 thread" else "$it threads" }.toTypedArray()
        val cur = opts.indexOf(t.threads).coerceAtLeast(0)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Threads for this download")
            .setSingleChoiceItems(labels, cur, null)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val sel = dlg.listView.checkedItemPosition
            if (sel in opts.indices) {
                engine.resplit(t, opts[sel])
                engine.resume(t)
            }
            dlg.dismiss()
        }
    }

    private fun pickDefaultThreads() {
        val opts = DownloadEngine.THREAD_OPTIONS
        val labels = opts.map { if (it == 1) "1 thread" else "$it threads" }.toTypedArray()
        val cur = opts.indexOf(engine.defaultThreads).coerceAtLeast(0)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Default thread count")
            .setSingleChoiceItems(labels, cur, null)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val sel = dlg.listView.checkedItemPosition
            if (sel in opts.indices) engine.defaultThreads = opts[sel]
            updateSubtitle()
            dlg.dismiss()
        }
    }

    private fun openFile(t: DownloadEngine.Task) {
        val intent = engine.openIntent(t)
        if (intent == null) {
            toast("File unavailable")
            return
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("No app can open this file") }
    }

    private fun copyLink(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        toast("Link copied")
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun iconFor(t: DownloadEngine.Task): Int = when {
        t.mime.startsWith("image") -> R.drawable.ic_img
        t.mime.startsWith("video") -> R.drawable.ic_video
        t.mime.startsWith("audio") -> R.drawable.ic_audio
        t.mime == "application/pdf" -> R.drawable.ic_pdf
        t.mime.contains("zip") || t.mime.contains("rar") || t.mime.contains("tar") -> R.drawable.ic_archive
        t.mime.startsWith("text") -> R.drawable.ic_doc
        else -> R.drawable.ic_file
    }
}
