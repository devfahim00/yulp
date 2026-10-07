package com.devfahim00.yulp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dedicated page for bookmarks and history (mode extra: "bookmarks" | "history"). */
class ListPageActivity : AppCompatActivity() {

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val title: TextView = v.findViewById(R.id.title)
        val url: TextView = v.findViewById(R.id.url)
        val time: TextView = v.findViewById(R.id.time)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_entry, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it2 = items[pos]
            h.avatar.text = (it2.title.ifBlank { it2.url }).trim().uppercase().take(1).ifEmpty { "?" }
            h.title.text = it2.title.ifBlank { it2.url }
            h.url.text = it2.url
            h.time.visibility = if (it2.time > 0) View.VISIBLE else View.GONE
            if (it2.time > 0) h.time.text = fmtTime(it2.time)
            h.itemView.setOnClickListener { open(it2.url) }
            h.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@ListPageActivity)
                    .setTitle(it2.title.ifBlank { it2.url })
                    .setItems(arrayOf("Open", "Open in new tab", "Copy link", "Delete")) { _, w ->
                        when (w) {
                            0 -> open(it2.url)
                            1 -> openNewTab(it2.url)
                            2 -> copyLink(it2.url)
                            else -> {
                                items.removeAt(pos); notifyItemRemoved(pos); deleteFromStore(it2); updateEmpty()
                            }
                        }
                    }.show()
                true
            }
        }
    }

    private lateinit var store: Store
    private val items = mutableListOf<Store.Item>()
    private var mode = "bookmarks"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        store = Store(this)
        mode = intent.getStringExtra("mode") ?: "bookmarks"
        val isHistory = mode == "history"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (isHistory) "History" else "Bookmarks"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.actClear -> {
                    AlertDialog.Builder(this)
                        .setTitle("Clear ${if (isHistory) "history" else "bookmarks"}?")
                        .setMessage(if (isHistory) "All history will be deleted." else "All bookmarks will be deleted.")
                        .setPositiveButton("Clear") { _, _ ->
                            store.clear(if (isHistory) "h" else "b")
                            items.clear()
                            findViewById<RecyclerView>(R.id.recycler).adapter?.notifyDataSetChanged()
                            updateEmpty()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                else -> false
            }
        }

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = Adapter()
        refresh()
    }

    private fun refresh() {
        items.clear()
        items.addAll(store.get(if (mode == "history") "h" else "b"))
        findViewById<RecyclerView>(R.id.recycler).adapter?.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        val empty = findViewById<View>(R.id.empty)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        (empty as TextView).text = if (mode == "history") "No history yet" else "No bookmarks yet"
    }

    private fun open(url: String) {
        setResult(RESULT_OK, Intent().putExtra("url", url))
        finish()
    }

    private fun openNewTab(url: String) {
        setResult(RESULT_OK, Intent().putExtra("url", url).putExtra("newTab", true))
        finish()
    }

    private fun copyLink(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun deleteFromStore(item: Store.Item) {
        store.delete(if (mode == "history") "h" else "b", item)
    }

    private fun fmtTime(t: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = SimpleDateFormat("d MMM", Locale.getDefault()).format(cal.time)
        val d = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        val timePart = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        return if (d == today) timePart else "$d · $timePart"
    }
}
