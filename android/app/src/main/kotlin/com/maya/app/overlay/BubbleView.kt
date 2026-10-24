package com.maya.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Maya's speech bubble: its own small overlay window (not focusable, not touchable) that sits above
 * the sprite, centred on it and clamped to the screen. [follow] is called every frame with the
 * sprite's position; the window is only attached while a bubble is showing.
 */
class BubbleView(context: Context, private val wm: WindowManager) {
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private val textView = TextView(context).apply {
        textSize = 14f // sp
        setTextColor(TEXT_COLOR)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), BORDER_COLOR)
        }
    }

    private val params = overlayParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )

    private var attached = false
    private var bubbleW = 0
    private var bubbleH = 0
    private var anchorX = 0
    private var anchorY = 0
    private var anchorSize = 0
    private var screenW = 0

    val isShowing: Boolean get() = attached

    /** Shows [text] for [ms] milliseconds (replacing whatever the bubble showed). */
    fun say(text: String, ms: Long) {
        show(text)
        main.postDelayed(hideRunnable, if (ms > 0) ms else DEFAULT_BUBBLE_MS)
    }

    /** `true` shows "…" until replaced; `false` hides the bubble only if it is showing "…". */
    fun typing(on: Boolean) {
        if (on) {
            show(TYPING)
        } else if (attached && textView.text.toString() == TYPING) {
            hide()
        }
    }

    /** Called every frame with the sprite window's position and the current screen width. */
    fun follow(spriteX: Int, spriteY: Int, spriteSize: Int, screenWidth: Int) {
        if (screenWidth != screenW) {
            screenW = screenWidth
            textView.maxWidth = (screenWidth * MAX_WIDTH_FRACTION).toInt()
            if (attached) measure()
        }
        anchorX = spriteX
        anchorY = spriteY
        anchorSize = spriteSize
        if (attached && place()) updateSafely(wm, textView, params)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        if (attached) {
            removeSafely(wm, textView)
            attached = false
        }
    }

    fun destroy() = hide()

    private fun show(text: String) {
        main.removeCallbacks(hideRunnable)
        textView.text = text
        measure()
        place()
        if (attached) {
            updateSafely(wm, textView, params)
        } else {
            attached = addSafely(wm, textView, params)
        }
    }

    private fun measure() {
        val maxW = if (screenW > 0) (screenW * MAX_WIDTH_FRACTION).toInt() else Int.MAX_VALUE / 2
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        bubbleW = textView.measuredWidth
        bubbleH = textView.measuredHeight
    }

    /** Computes the window position from the anchor; returns true if it changed. */
    private fun place(): Boolean {
        val gap = dp(4)
        val maxX = (screenW - bubbleW).coerceAtLeast(0)
        val x = (anchorX + anchorSize / 2 - bubbleW / 2).coerceIn(0, maxX)
        var y = anchorY - bubbleH - gap
        if (y < 0) y = anchorY + anchorSize + gap // no room above: show it below her
        if (x == params.x && y == params.y) return false
        params.x = x
        params.y = y
        return true
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val TYPING = "…"
        const val DEFAULT_BUBBLE_MS = 4000L
        const val MAX_WIDTH_FRACTION = 0.7f
        const val TEXT_COLOR = 0xFF222222.toInt()
        const val BORDER_COLOR = 0xFFCCCCCC.toInt()
    }
}
