package com.devfahim00.yulp

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable

/** Shared tab snapshot + thumbnail store so TabActivity can render the grid. */
object Tabs {
    data class TabInfo(
        val id: Int,
        val title: String,
        val url: String,
        val incognito: Boolean
    ) : Parcelable {
        constructor(p: Parcel) : this(
            p.readInt(), p.readString() ?: "", p.readString() ?: "", p.readInt() == 1
        )

        override fun writeToParcel(p: Parcel, f: Int) {
            p.writeInt(id); p.writeString(title); p.writeString(url); p.writeInt(if (incognito) 1 else 0)
        }

        override fun describeContents() = 0

        companion object {
            @Suppress("unused")
            @JvmField
            val CREATOR = object : Parcelable.Creator<TabInfo> {
                override fun createFromParcel(p: Parcel) = TabInfo(p)
                override fun newArray(n: Int) = arrayOfNulls<TabInfo>(n)
            }
        }
    }

    /** id -> thumbnail bitmap (kept in-process only). */
    val thumbnails = HashMap<Int, Bitmap>()

    /** Live snapshot list, updated by MainActivity. */
    @Volatile var snapshot: List<TabInfo> = emptyList()
    @Volatile var currentId: Int = -1

    fun putThumb(id: Int, bmp: Bitmap?) {
        if (bmp == null) thumbnails.remove(id) else thumbnails[id] = bmp
    }

    fun thumb(id: Int): Bitmap? = thumbnails[id]
}
