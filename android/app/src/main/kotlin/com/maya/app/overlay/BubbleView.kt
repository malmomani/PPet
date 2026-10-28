package com.maya.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Maya's speech bubble: its own small overlay window (not focusable, not touchable) that sits above
 * the sprite, centred on it and clamped to the screen. [follow] is called every frame with the
 * sprite's position; the window is only attached while a bubble is showing. While she [ask]s, the
 * bubble also shows ✔ and ✖ and is the only time the window takes touches.
 */
class BubbleView(context: Context, private val wm: WindowManager) {
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private val textView = TextView(context).apply {
        textSize = 14f // sp
        setTextColor(TEXT_COLOR)
    }

    private val yesButton = answerButton(context, YES, YES_COLOR) { answer(true) }
    private val noButton = answerButton(context, NO, NO_COLOR) { answer(false) }

    /** ✔ and ✖, only shown while asking. */
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        visibility = View.GONE
        addView(yesButton)
        addView(noButton)
    }

    /** The window's view: the text, and under it the answer buttons. */
    private val bubbleRoot = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), BORDER_COLOR)
        }
        addView(textView)
        addView(buttons)
    }

    private val params = overlayParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        PASSIVE_FLAGS,
    ).apply {
        // Android 12+ drops touches that pass through another app's overlay unless its alpha is
        // <= InputManager.getMaximumObscuringOpacityForTouch() (0.8, inclusive); without this the
        // bubble would silently swallow taps meant for the app underneath.
        alpha = MAX_PASS_THROUGH_ALPHA
    }

    private var attached = false
    private var bubbleW = 0
    private var bubbleH = 0
    private var anchorX = 0
    private var anchorY = 0
    private var anchorSize = 0
    private var screenW = 0

    // The question being asked (both null when not asking).
    private var onYes: (() -> Unit)? = null
    private var onNo: (() -> Unit)? = null
    /** ✔ / ✖ ignore taps until this uptime: just after the question appears or the bubble moved. */
    private var armedAtMs = 0L
    /** Where the bubble was when the buttons were last (re)armed. */
    private var armX = 0
    private var armY = 0
    /** When the finger went down for the latest touch on ✔ or ✖ (uptime). */
    private var lastDownMs = 0L

    val isShowing: Boolean get() = attached

    /** True while a question with ✔ / ✖ is up. */
    val isAsking: Boolean get() = onYes != null

    /** Shows [text] for [ms] milliseconds (replacing whatever the bubble showed). */
    fun say(text: String, ms: Long) {
        show(text)
        main.postDelayed(hideRunnable, if (ms > 0) ms else DEFAULT_BUBBLE_MS)
    }

    /** Shows [text] until replaced or hidden (no timeout), e.g. live speech while she listens. */
    fun hold(text: String) = show(text)

    /** `true` shows "…" until replaced; `false` hides the bubble only if it is showing "…". */
    fun typing(on: Boolean) {
        if (on) {
            show(TYPING)
        } else if (attached && !isAsking && textView.text.toString() == TYPING) {
            hide()
        }
    }

    /**
     * Shows [text] with ✔ and ✖ until one is tapped (the bubble then closes and [yes] or [no] is
     * called, once) or the question is withdrawn ([cancelAsk], [hide], or anything else shown in
     * the bubble: then neither is called). The window takes touches only while asking.
     */
    fun ask(text: String, yes: () -> Unit, no: () -> Unit) {
        onYes = yes
        onNo = no
        buttons.visibility = View.VISIBLE
        params.flags = ASKING_FLAGS
        params.alpha = 1f // nothing passes through it while it takes touches
        showContent(text)
        rearm()
    }

    /** Withdraws the question (if any) and closes the bubble; neither answer is called. */
    fun cancelAsk() {
        if (isAsking) hide()
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
        if (attached && place()) updateSafely(wm, bubbleRoot, params)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        if (attached) {
            removeSafely(wm, bubbleRoot)
            attached = false
        }
        endAsk()
    }

    fun destroy() = hide()

    private fun answer(yes: Boolean) {
        // A tap meant for the app (the bubble just appeared or moved under the finger) is no answer:
        // the touch must have started after the buttons were armed.
        if (lastDownMs < armedAtMs || SystemClock.uptimeMillis() < armedAtMs) return
        val callback = (if (yes) onYes else onNo) ?: return
        hide() // ends the question first, so the callback is called once
        callback()
    }

    /** Back to a plain bubble: no buttons, no touches, see-through enough for taps to pass. */
    private fun endAsk() {
        if (!isAsking) return
        onYes = null
        onNo = null
        buttons.visibility = View.GONE
        params.flags = PASSIVE_FLAGS
        params.alpha = MAX_PASS_THROUGH_ALPHA
        if (attached) {
            measure()
            place()
            updateSafely(wm, bubbleRoot, params)
        }
    }

    /** Plain text replaces whatever was up, a question included (withdrawn without an answer). */
    private fun show(text: String) {
        endAsk()
        showContent(text)
    }

    private fun showContent(text: String) {
        main.removeCallbacks(hideRunnable)
        textView.text = text
        measure()
        place()
        if (attached) {
            updateSafely(wm, bubbleRoot, params)
        } else {
            attached = addSafely(wm, bubbleRoot, params)
        }
    }

    private fun measure() {
        val maxW = if (screenW > 0) (screenW * MAX_WIDTH_FRACTION).toInt() else Int.MAX_VALUE / 2
        bubbleRoot.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        bubbleW = bubbleRoot.measuredWidth
        bubbleH = bubbleRoot.measuredHeight
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
        if (isAsking && hypot((x - armX).toFloat(), (y - armY).toFloat()) > MOVE_REARM_PX) rearm()
        return true
    }

    /** ✔ / ✖ take taps again only [ARM_MS] from now, at the bubble's current place. */
    private fun rearm() {
        armedAtMs = SystemClock.uptimeMillis() + ARM_MS
        armX = params.x
        armY = params.y
    }

    /** A big round-ish tap target for ✔ or ✖ (the touch listener only notes when a touch starts; clicks stay normal). */
    @SuppressLint("ClickableViewAccessibility")
    private fun answerButton(context: Context, label: String, color: Int, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 20f // sp
            setTextColor(color)
            gravity = Gravity.CENTER
            minWidth = dp(BUTTON_DP)
            minHeight = dp(BUTTON_DP)
            isClickable = true
            // no answer through another app's window drawn over the bubble (tapjacking)
            filterTouchesWhenObscured = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) lastDownMs = event.downTime
                false
            }
            setOnClickListener { onTap() }
        }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val TYPING = "…"
        const val YES = "✔"
        const val NO = "✖"
        const val DEFAULT_BUBBLE_MS = 4000L
        const val MAX_WIDTH_FRACTION = 0.7f
        const val MAX_PASS_THROUGH_ALPHA = 0.8f
        const val BUTTON_DP = 48
        /** ✔ / ✖ ignore taps this long after the question appears or the bubble moves. */
        const val ARM_MS = 500L
        /** Moving more than this many px re-arms the buttons. */
        const val MOVE_REARM_PX = 8f
        const val TEXT_COLOR = 0xFF222222.toInt()
        const val BORDER_COLOR = 0xFFCCCCCC.toInt()
        const val YES_COLOR = 0xFF2E7D32.toInt()
        const val NO_COLOR = 0xFFC62828.toInt()
        const val PASSIVE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Touchable, still never focused; touches outside it go to the app behind. */
        const val ASKING_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
