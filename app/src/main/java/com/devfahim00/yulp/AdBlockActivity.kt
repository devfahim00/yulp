package com.devfahim00.yulp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ad blocker page: enable/disable switch, stats and the blocked-request history. */
class AdBlockActivity : AppCompatActivity() {

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val host: TextView = v.findViewById(R.id.host)
        val url: TextView = v.findViewById(R.id.url)
        val time: TextView = v.findViewById(R.id.time)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_blocked, p, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = entries[pos]
            h.host.text = e.url.substringAfter("://").substringBefore('/')
            h.url.text = e.url
            h.time.text = fmtTime(e.time)
            h.icon.setImageResource(
                if (AdBlocker.enabled) R.drawable.ic_shield else R.drawable.ic_shield_off
            )
        }
    }

    private val entries = mutableListOf<AdBlocker.BlockedEntry>()
    private val listener = { list: MutableList<AdBlocker.BlockedEntry> ->
        entries.clear(); entries.addAll(list)
        findViewById<RecyclerView>(R.id.recycler).adapter?.notifyDataSetChanged()
        updateStats()
        updateEmpty()
    }
    private lateinit var switch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adblock)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.actClear -> {
                    AlertDialog.Builder(this)
                        .setTitle("Clear blocked history?")
                        .setPositiveButton("Clear") { _, _ -> AdBlocker.clearHistory() }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                else -> false
            }
        }

        switch = findViewById(R.id.switchBlock)
        switch.isChecked = AdBlocker.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            AdBlocker.setEnabled(this, checked)
            findViewById<RecyclerView>(R.id.recycler).adapter?.notifyDataSetChanged()
            updateStats()
        }

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = Adapter()
        AdBlocker.addListener(listener)
    }

    override fun onDestroy() {
        AdBlocker.removeListener(listener)
        super.onDestroy()
    }

    private fun updateStats() {
        findViewById<TextView>(R.id.statTotal).text = formatNumber(AdBlocker.total())
        findViewById<TextView>(R.id.statToday).text = formatNumber(AdBlocker.today())
        findViewById<TextView>(R.id.blockedDesc).text = if (AdBlocker.enabled)
            "Blocking ads and trackers on every page you visit"
        else "Ad blocker is off — ads and trackers are loading normally"
    }

    private fun updateEmpty() {
        findViewById<View>(R.id.empty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatNumber(n: Long): String =
        when {
            n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
            n >= 10_000 -> "${n / 1000}k"
            else -> n.toString()
        }

    private fun fmtTime(t: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = SimpleDateFormat("d MMM", Locale.getDefault()).format(cal.time)
        val d = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        val timePart = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        return if (d == today) timePart else "$d · $timePart"
    }
}
