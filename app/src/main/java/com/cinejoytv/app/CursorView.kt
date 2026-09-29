package com.cinejoytv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** On-screen pointer driven by the D-pad, for sites that were built for mouse/touch. */
class CursorView(context: Context) : View(context) {

    var cx = -1f
        private set
    var cy = -1f
        private set

    private val density = resources.displayMetrics.density
    private val radius = 11 * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0xFF000000.toInt()
    }
    private val fade = Runnable { animate().alpha(0f).setDuration(400) }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (cx < 0) { cx = w / 2f; cy = h / 2f }
        cx = cx.coerceIn(0f, w.toFloat())
        cy = cy.coerceIn(0f, h.toFloat())
    }

    /** Moves the cursor and returns how far the move overshot the screen edge (x, y). */
    fun moveBy(dx: Float, dy: Float): Pair<Float, Float> {
        val tx = cx + dx
        val ty = cy + dy
        cx = tx.coerceIn(0f, width - 1f)
        cy = ty.coerceIn(0f, height - 1f)
        wake()
        invalidate()
        return Pair(tx - cx, ty - cy)
    }

    fun wake() {
        animate().cancel()
        alpha = 1f
        removeCallbacks(fade)
        postDelayed(fade, 5000)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, outline)
    }
}
