package com.maya.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.maya.app.R

/**
 * A small focusable overlay at the bottom of the screen with a text field and a Send button.
 * Send hands the trimmed text to [onSend] (if non-blank) and closes; back or a touch outside closes.
 */
class ChatWindow(
    context: Context,
    private val wm: WindowManager,
    private val onSend: (String) -> Unit,
) {
    private val ctx: Context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    private val density = ctx.resources.displayMetrics.density
    private val imm: InputMethodManager? = ctx.getSystemService(InputMethodManager::class.java)

    private var root: ChatRoot? = null
    private var input: EditText? = null

    /** Uptime (ms) of the last close caused by a touch outside the window. */
    var lastOutsideCloseMs: Long = 0L
        private set

    val isOpen: Boolean get() = root != null

    private inner class ChatRoot : LinearLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                lastOutsideCloseMs = SystemClock.uptimeMillis()
                close()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }

    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE: still honoured for overlay windows
    fun open() {
        if (root != null) return
        val view = build()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        params.softInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        if (!addSafely(wm, view, params)) {
            input = null
            return
        }
        root = view
        val edit = input ?: return
        edit.requestFocus()
        edit.postDelayed({
            if (root === view) imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, KEYBOARD_DELAY_MS)
    }

    fun close() {
        val view = root ?: return
        root = null
        input = null
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        removeSafely(wm, view)
    }

    private fun send() {
        val text = input?.text?.toString()?.trim()?.take(MAX_CHARS) ?: return
        if (text.isBlank()) return
        onSend(text)
        close()
    }

    private fun build(): ChatRoot {
        val view = ChatRoot()
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        view.setPadding(pad, pad, pad, pad)
        val r = dp(16).toFloat()
        view.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.elevation = dp(8).toFloat()

        val edit = EditText(ctx)
        edit.setHint(R.string.chat_hint)
        edit.setTextColor(TEXT_COLOR)
        edit.setHintTextColor(HINT_COLOR)
        edit.textSize = 16f
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.imeOptions = EditorInfo.IME_ACTION_SEND
        // Wrap long messages instead of scrolling sideways, while keeping the IME's Send key.
        edit.setHorizontallyScrolling(false)
        edit.maxLines = 4
        edit.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_CHARS))
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        view.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val button = Button(ctx)
        button.setText(R.string.chat_send)
        button.setOnClickListener { send() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(8)
        view.addView(button, lp)

        input = edit
        return view
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val MAX_CHARS = 2000
        const val KEYBOARD_DELAY_MS = 100L
        const val TEXT_COLOR = 0xFF222222.toInt()
        const val HINT_COLOR = 0xFF888888.toInt()
    }
}
