package com.devfahim00.yulp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny SharedPreferences-backed store for bookmarks ("b") and history ("h"). */
class Store(ctx: Context) {
    data class Item(val title: String, val url: String, val time: Long = 0L)

    private val p = ctx.getSharedPreferences("yulp", Context.MODE_PRIVATE)

    fun get(k: String): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val a = JSONArray(p.getString(k, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Item(o.getString("t"), o.getString("u"), o.optLong("tm", 0L)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun put(k: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("tm", it.time)) }
        p.edit().putString(k, a.toString()).apply()
    }

    fun addHistory(i: Item) {
        val l = get("h")
        if (l.firstOrNull()?.url == i.url) return
        l.add(0, i.copy(time = System.currentTimeMillis()))
        put("h", l.take(500))
    }

    fun isBookmarked(url: String) = get("b").any { it.url == url }

    /** @return true if bookmark was added, false if removed */
    fun toggleBookmark(i: Item): Boolean {
        val l = get("b")
        val ex = l.firstOrNull { it.url == i.url }
        return if (ex != null) {
            l.remove(ex); put("b", l); false
        } else {
            l.add(0, i.copy(time = System.currentTimeMillis())); put("b", l); true
        }
    }

    fun delete(k: String, item: Item) {
        val l = get(k)
        l.removeAll { it.url == item.url && it.title == item.title }
        put(k, l)
    }

    fun clear(k: String) = p.edit().remove(k).apply()
}
