package com.zx.stacker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Full-screen, touch-through layer that draws a coloured frame around good offer cards
 * in Lalamove, so you can see where to tap at a glance. It never taps anything.
 * No text is drawn, so the screen reader can't mistake it for an offer.
 */
class Highlighter(private val ctx: Context) {
    data class Box(val top: Int, val bottom: Int, val color: Int)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var shown = false
    private var boxes: List<Box> = emptyList()

    private val view = object : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(5).toFloat()
        }
        private val loc = IntArray(2)
        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val inset = ctx.dp(10).toFloat()
            val r = ctx.dp(16).toFloat()
            for (b in boxes) {
                paint.color = b.color
                val rect = RectF(inset, (b.top - loc[1]).toFloat() - inset, width - inset, (b.bottom - loc[1]).toFloat() + inset)
                c.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Android 12+ only lets touches pass through overlays at or below 0.8 opacity.
        alpha = 0.8f
    }

    fun show() {
        if (shown) return
        wm.addView(view, params)
        shown = true
    }

    fun setBoxes(b: List<Box>) {
        if (b == boxes) return
        boxes = b
        view.invalidate()
    }

    fun remove() {
        if (shown) try { wm.removeView(view) } catch (_: Exception) {}
        shown = false
    }
}
