package com.devfahim00.yulp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Full-screen tab switcher: 2-column thumbnail grid (no more popup dialog).
 * Closes / selection is reported back to MainActivity via the activity result.
 */
class TabActivity : AppCompatActivity() {

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val badge: ImageView = v.findViewById(R.id.incognitoBadge)
        val close: ImageButton = v.findViewById(R.id.close)
        val card: View = v.findViewById(R.id.card)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_tab, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.title.text = item.title.ifBlank { "New tab" }
            h.badge.visibility = if (item.incognito) View.VISIBLE else View.GONE
            h.thumb.setImageBitmap(Tabs.thumb(item.id))
            val isCur = item.id == originalCurrentId
            h.card.setBackgroundResource(if (isCur) R.drawable.bg_tab_current else R.drawable.bg_tab_card)
            h.card.setOnClickListener { result(selectId = item.id) }
            h.close.setOnClickListener { v ->
                val i = h.bindingAdapterPosition
                if (i < 0 || i >= items.size) return@setOnClickListener
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                items.removeAt(i)
                closedIds.add(item.id)
                h.thumb.setImageBitmap(null)
                notifyItemRemoved(i)
                if (items.isEmpty()) result(action = "new")
            }
        }
    }

    private val items = mutableListOf<Tabs.TabInfo>()
    private val closedIds = mutableSetOf<Int>()
    private var originalCurrentId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs)

        items.addAll(Tabs.snapshot)
        originalCurrentId = Tabs.currentId

        // system back must also deliver closes + selection, otherwise edits are lost
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { result(selectId = originalCurrentId) }
        })

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { result(selectId = originalCurrentId) }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.actNew -> { result(action = "new"); true }
                R.id.actNewIncognito -> { result(action = "newIncognito"); true }
                R.id.actCloseAll -> { result(action = "closeAll"); true }
                else -> false
            }
        }

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = Adapter()
        updateEmpty()
    }

    private fun updateEmpty() {
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<MaterialToolbar>(R.id.toolbar).subtitle = "${items.size} open"
    }

    private fun result(action: String? = null, selectId: Int = -1) {
        val data = Intent().apply {
            putExtra("closedIds", closedIds.toIntArray())
            action?.let { putExtra("action", it) }
            if (selectId >= 0) putExtra("selectId", selectId)
        }
        setResult(RESULT_OK, data)
        finish()
    }
}
