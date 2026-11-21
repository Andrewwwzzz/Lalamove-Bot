package com.zx.stacker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Floating, draggable verdict bubble drawn over Lalamove. Tap to collapse/expand. */
class Overlay(private val ctx: Context) {
    data class Row(val text: String, val color: Int)

    companion object {
        const val GREEN = 0xFF1E9E5A.toInt()
        const val AMBER = 0xFFC88A00.toInt()
        const val RED = 0xFFC2362B.toInt()
        const val GREY = 0xFF3A3A3A.toInt()
        const val ORANGE = 0xFFF26A21.toInt()
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var shown = false
    private var collapsed = false
    private var rows: List<Row> = emptyList()

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        background = GradientDrawable().apply {
            setColor(0xEE141414.toInt())
            cornerRadius = ctx.dp(14).toFloat()
        }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ctx.dp(8)
        y = ctx.dp(90)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(root, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 12 && abs(e.rawY - downY) < 12) { collapsed = !collapsed; draw() }
                    true
                }
                else -> false
            }
        }
        wm.addView(root, params)
        shown = true
    }

    fun setRows(r: List<Row>) {
        rows = r
        draw()
    }

    private fun draw() {
        root.removeAllViews()
        val list = if (collapsed) rows.take(1) else rows
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.82).toInt()
        list.forEachIndexed { i, row ->
            val tv = TextView(ctx).apply {
                text = if (collapsed) row.text.lineSequence().first() else row.text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxWidth = maxW
                setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
                background = GradientDrawable().apply { setColor(row.color); cornerRadius = ctx.dp(10).toFloat() }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = ctx.dp(6)
            root.addView(tv, lp)
        }
    }

    /** Where the bubble is on screen, so its own text is not read as an offer. */
    fun rect(): Rect {
        if (!shown) return Rect()
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    fun remove() {
        if (shown) try { wm.removeView(root) } catch (_: Exception) {}
        shown = false
    }
}
