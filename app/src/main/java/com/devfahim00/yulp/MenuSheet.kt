package com.devfahim00.yulp

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Via-style overflow menu: a rounded bottom sheet with a 5-column icon grid
 * spread over two swipeable pages and a footer (power | page dots | dismiss).
 */
object MenuSheet {

    class Item(val label: String, val icon: Int, val action: () -> Unit)

    fun show(
        activity: AppCompatActivity,
        page1: List<Item>,
        page2: List<Item>,
        onExit: () -> Unit
    ): BottomSheetDialog {
        val dp = activity.resources.displayMetrics.density
        val sheet = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.menuSheetBg))
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(activity.getColor(R.color.menuSheetBg))
            }
            background = shape
        }

        // drag handle
        val handle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(40 * dp.toInt(), 4 * dp.toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 10 * dp.toInt()
                bottomMargin = 6 * dp.toInt()
            }
            setBackgroundResource(R.drawable.bg_handle)
        }
        root.addView(handle)

        // pager with the two grid pages
        val pager = ViewPager2(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 210 * dp.toInt())
        }
        val pages = listOf(page1, page2)
        pager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(makePage(activity, parent)) {}

            override fun getItemCount() = pages.size

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                bindPage(holder.itemView as GridLayout, pages[position])
            }
        }
        root.addView(pager)

        // footer: power | dots | chevron
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20 * dp.toInt(), 4 * dp.toInt(), 20 * dp.toInt(), 12 * dp.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val power = makeFooterBtn(activity, R.drawable.ic_power, "Exit") { sheet.dismiss(); onExit() }
        footer.addView(power, LinearLayout.LayoutParams(0, -2, 1f))
        val dots = LinearLayout(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val dotViews = (0 until pages.size).map { i ->
            View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(8 * dp.toInt(), 8 * dp.toInt()).apply {
                    marginEnd = 6 * dp.toInt()
                }
                setBackgroundResource(R.drawable.bg_dot)
                alpha = if (i == 0) 1f else 0.3f
            }
        }
        dotViews.forEach { dots.addView(it) }
        footer.addView(dots, LinearLayout.LayoutParams(0, 48 * dp.toInt(), 1.2f).apply { gravity = Gravity.CENTER })
        val down = makeFooterBtn(activity, R.drawable.ic_chevron_down, "Close") { sheet.dismiss() }
        footer.addView(down, LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.END })
        root.addView(footer)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                dotViews.forEachIndexed { i, v -> v.animate().alpha(if (i == pos % dotViews.size) 1f else 0.3f).setDuration(150).start() }
            }
        })

        sheet.setContentView(root)
        sheet.show()
        return sheet
    }

    private fun makeFooterBtn(activity: AppCompatActivity, icon: Int, desc: String, onClick: () -> Unit): View =
        ImageView(activity).apply {
            setImageResource(icon)
            contentDescription = desc
            val pad = (14 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { onClick() }
            setBackgroundResource(R.drawable.bg_ripple)
        }

    private fun makePage(activity: AppCompatActivity, parent: android.view.ViewGroup): GridLayout =
        GridLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            columnCount = 5
            orientation = GridLayout.HORIZONTAL
            setUseDefaultMargins(false)
            val pad = (10 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

    private fun bindPage(grid: GridLayout, items: List<Item>) {
        grid.removeAllViews()
        val ctx = grid.context
        val dp = ctx.resources.displayMetrics.density
        for (it in items) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                isClickable = true
                setBackgroundResource(R.drawable.bg_ripple)
                setOnClickListener { _ -> it.action() }
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    width = 0
                    height = -2
                    setMargins(2, 4, 2, 4)
                }
            }
            val icon = ImageView(ctx).apply {
                setImageResource(it.icon)
                layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt())
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.menuItemIcon))
            }
            val label = TextView(ctx).apply {
                text = it.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(ctx.getColor(R.color.menuItemText))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = (6 * dp).toInt()
                }
            }
            cell.addView(icon)
            cell.addView(label)
            grid.addView(cell)
        }
    }
}
