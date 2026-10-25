package com.maya.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.maya.app.OverlayHook
import com.maya.app.R
import com.maya.app.voice.VoiceInput
import com.maya.core.Action
import com.maya.core.Animate
import com.maya.core.Audio
import com.maya.core.Behavior
import com.maya.core.ErrorMsg
import com.maya.core.Event
import com.maya.core.Gesture
import com.maya.core.HOLD_TO_TALK_MS
import com.maya.core.Say
import com.maya.core.State
import com.maya.core.Typing
import com.maya.core.UserDrag
import com.maya.core.UserMessage
import com.maya.core.UserTap
import com.maya.core.classifyGesture
import com.maya.core.isHoldToTalk
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "MayaOverlay"

/**
 * Maya's floating body: a sprite-sized overlay window near the bottom of the screen, moved by a
 * ~30 fps Choreographer loop that drives [Behavior], plus her speech bubble and the chat window.
 * The loop only runs while she is visible and the screen is on and unlocked ([setScreenOn]).
 * Holding her still for [HOLD_TO_TALK_MS] starts listening ([VoiceInput]); [onListening] reports
 * when listening starts and ends. Must be created and used on the main thread.
 */
class OverlayBody(
    context: Context,
    private val emit: (Event) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) : OverlayHook {
    private val ctx: Context = overlayContext(context)
    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val scale = (density * 1.4f).roundToInt().coerceIn(3, 6)
    private val sheet = SpriteSheet(ctx.assets)
    private val spriteSize = sheet.frameW * scale
    private val spriteH = sheet.frameH * scale
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val choreographer = Choreographer.getInstance()
    private val main = Handler(Looper.getMainLooper())

    private var screenW = 0
    private var screenH = 0
    private var behavior: Behavior
    private var connected = false

    private val spriteView = SpriteView(ctx, sheet)
    private val params = overlayParams(
        spriteSize,
        spriteH,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    private val bubble = BubbleView(ctx, wm)
    private val chat = ChatWindow(ctx, wm) { text -> emit(UserMessage(text)) }
    private val voice = VoiceInput(context, VoiceListener())

    private var visible = false
    private var destroyed = false
    private var looping = false
    private var screenOn = true
    private var lastFrameNs = 0L
    private var sizeCheckMs = 0L

    // touch state
    private var velocity: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var dragging = false
    private var throwAnim: ValueAnimator? = null
    private var downTimeMs = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

    // hold-to-talk state
    /** The hold timer fired during the current touch: the rest of it is not a tap or a drag. */
    private var holdFired = false
    /** A voice session is running (from listening start until its result arrives). */
    private var listening = false
    private val holdRunnable = Runnable { onHoldTimer() }
    private val listenCap = Runnable { if (listening) voice.stop() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            if (lastFrameNs == 0L) lastFrameNs = frameTimeNanos
            val dtMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
            if (dtMs > 0L) {
                lastFrameNs += dtMs * 1_000_000L // keep the sub-millisecond remainder
                step(dtMs.coerceAtMost(MAX_DT_MS))
            }
            // ~30 fps: wake up again after FRAME_MS instead of on every vsync.
            choreographer.postFrameCallbackDelayed(this, FRAME_MS)
        }
    }

    init {
        val (w, h) = screenSize()
        screenW = w
        screenH = h
        behavior = newBehavior()
        behavior.setConnected(false) // napping until the connection says otherwise
        params.x = behavior.x.roundToInt()
        params.y = restingY()
        spriteView.setOnTouchListener { _, event -> onTouch(event) }
    }

    // ---- OverlayHook ----

    override fun handle(a: Action) {
        when (a) {
            is Animate -> behavior.play(a.name)
            // While listening the bubble shows what she hears; replies come after it.
            is Say -> if (visible && !listening) bubble.say(a.text, a.bubbleMs)
            is Typing -> if (visible && !listening) bubble.typing(a.on)
            is State -> Unit // nothing visible in v1
            is Audio -> Unit // played by the service's VoicePlayer
            is ErrorMsg -> Unit // the service handles auth errors
        }
    }

    /** Her voice started playing: a little happy bounce (no lip-sync in v1). */
    override fun onVoiceStarted() {
        behavior.play("happy")
    }

    override fun setConnected(on: Boolean) {
        connected = on
        behavior.setConnected(on)
        if (!on) {
            bubble.typing(false) // a dropped link would otherwise leave "…" up forever
            chat.close() // messages typed now would be lost
        }
    }

    /** Screen off or locked: nobody can see her, so stop the frame loop until the user is back. */
    override fun setScreenOn(on: Boolean) {
        if (on == screenOn) return
        screenOn = on
        if (on) {
            if (visible) startLoop()
        } else {
            stopLoop()
            cancelListening()
        }
    }

    // ---- lifecycle ----

    fun show() {
        if (visible || destroyed) return
        refreshScreenSize()
        if (!addSafely(wm, spriteView, params)) return
        visible = true
        if (screenOn) startLoop()
    }

    fun hide() {
        stopLoop()
        cancelThrow()
        if (dragging) behavior.dragEnd(params.x.toFloat())
        endTouch()
        main.removeCallbacks(holdRunnable)
        holdFired = false
        cancelListening()
        chat.close()
        bubble.hide()
        if (visible) {
            removeSafely(wm, spriteView)
            visible = false
        }
    }

    fun destroy() {
        hide()
        voice.destroy()
        destroyed = true
    }

    // ---- frame loop ----

    private fun startLoop() {
        if (looping || destroyed) return
        looping = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun step(dtMs: Long) {
        sizeCheckMs += dtMs
        if (sizeCheckMs >= SIZE_CHECK_MS) {
            sizeCheckMs = 0L
            refreshScreenSize()
        }
        behavior.tick(dtMs)
        spriteView.setAnimation(behavior.animation)
        spriteView.facingLeft = behavior.facingLeft
        spriteView.advance(dtMs)
        if (!dragging && throwAnim == null) {
            val x = behavior.x.roundToInt()
            if (x != params.x) moveWindow(x, params.y)
        }
        bubble.follow(params.x, params.y, spriteSize, screenW)
    }

    // ---- touch ----

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelThrow()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(event)
                downRawX = event.rawX
                downRawY = event.rawY
                downWinX = params.x
                downWinY = params.y
                downTimeMs = event.downTime
                lastRawX = event.rawX
                lastRawY = event.rawY
                holdFired = false
                main.removeCallbacks(holdRunnable)
                main.postDelayed(holdRunnable, HOLD_TO_TALK_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                track(event)
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (holdFired) return true // she is listening (or tried to): moving does nothing
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    main.removeCallbacks(holdRunnable)
                    dragging = true
                    behavior.dragStart()
                }
                if (dragging) {
                    moveWindow(
                        (downWinX + dx.roundToInt()).coerceIn(0, maxX()),
                        (downWinY + dy.roundToInt()).coerceIn(0, maxY()),
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(holdRunnable)
                if (holdFired) {
                    endTouch()
                    holdFired = false
                    if (listening) voice.stop() // release sends
                    return true
                }
                track(event)
                val tracker = velocity
                var vx = 0f
                var vy = 0f
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000)
                    vx = tracker.xVelocity
                    vy = tracker.yVelocity
                }
                val distance = hypot(event.rawX - downRawX, event.rawY - downRawY)
                val duration = event.eventTime - event.downTime
                val gesture = classifyGesture(distance, duration, hypot(vx, vy), touchSlop.toFloat())
                val wasDragging = dragging
                endTouch()
                if (!wasDragging) {
                    // The finger never crossed touch slop, so she was never picked up: a short press
                    // is a tap, a long stationary press is nothing (not a drag).
                    if (duration < LONG_PRESS_MS) onTap(false)
                } else {
                    when (gesture) {
                        Gesture.TAP -> onTap(true)
                        Gesture.DRAG -> {
                            behavior.dragEnd(params.x.toFloat())
                            emit(UserDrag(false))
                        }
                        Gesture.FLING -> throwBody(vx, vy)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdRunnable)
                if (holdFired) {
                    // One UI may cancel the touch when the voice activity appears: keep listening.
                    // The session then ends on ~2 s of silence or the 30 s cap.
                    endTouch()
                    holdFired = false
                    return true
                }
                val wasDragging = dragging
                endTouch()
                if (wasDragging) behavior.dragEnd(params.x.toFloat())
            }
        }
        return true
    }

    /** Feeds the tracker in screen coordinates: the window moves with the finger. */
    private fun track(event: MotionEvent) {
        val tracker = velocity ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    /** Recycles the velocity tracker and clears the drag flag (the caller settles [behavior]). */
    private fun endTouch() {
        velocity?.recycle()
        velocity = null
        dragging = false
    }

    private fun onTap(wasDragging: Boolean) {
        // A tap that ended a drag-start (finger wandered and came back) still has to release her.
        if (wasDragging) behavior.dragEnd(params.x.toFloat())
        emit(UserTap())
        if (!connected) {
            bubble.say(ctx.getString(R.string.bubble_unreachable), UNREACHABLE_BUBBLE_MS)
            return
        }
        val justClosed = SystemClock.uptimeMillis() - chat.lastOutsideCloseMs < TOGGLE_WINDOW_MS
        if (chat.isOpen || justClosed) chat.close() else chat.open()
    }

    // ---- hold to talk ----

    private fun onHoldTimer() {
        if (dragging || velocity == null) return // she was picked up, or the finger already left
        val held = SystemClock.uptimeMillis() - downTimeMs
        val moved = hypot(lastRawX - downRawX, lastRawY - downRawY)
        if (!isHoldToTalk(moved, held, touchSlop.toFloat())) return
        holdFired = true
        if (listening) return // the previous session is still delivering its result
        startListening()
    }

    private fun startListening() {
        if (!connected) {
            bubble.say(ctx.getString(R.string.bubble_unreachable), UNREACHABLE_BUBBLE_MS)
            return
        }
        chat.close()
        spriteView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listening = true
        onListening(true) // stops her voice
        bubble.hold(LISTENING_PREFIX + "…")
        main.postDelayed(listenCap, MAX_LISTEN_MS)
        voice.start() // may end the session synchronously (no permission, no recognizer)
    }

    private fun endListening() {
        if (!listening) return
        listening = false
        main.removeCallbacks(listenCap)
        onListening(false)
    }

    /** Drops a running voice session (hidden, screen off): nothing is sent. */
    private fun cancelListening() {
        if (!listening) return
        voice.cancel()
        endListening()
        bubble.hide()
    }

    private inner class VoiceListener : VoiceInput.Listener {
        override fun onPartial(text: String) {
            if (listening && visible) bubble.hold(LISTENING_PREFIX + text)
        }

        override fun onFinal(text: String) {
            if (!listening) return
            endListening()
            if (!connected) {
                bubble.say(ctx.getString(R.string.bubble_unreachable), UNREACHABLE_BUBBLE_MS)
                return
            }
            emit(UserMessage(text, voice = true))
            if (visible) bubble.say(LISTENING_PREFIX + text, HEARD_BUBBLE_MS)
        }

        override fun onNoMatch() {
            if (!listening) return
            endListening()
            if (visible) bubble.say(ctx.getString(R.string.bubble_no_match), VOICE_PROBLEM_BUBBLE_MS)
        }

        override fun onUnavailable(reason: VoiceInput.Reason) {
            // BACKGROUND_DENIED is only a notice: VoiceInput carries on through its activity.
            if (reason == VoiceInput.Reason.BACKGROUND_DENIED || !listening) return
            endListening()
            val res = if (reason == VoiceInput.Reason.PERMISSION) R.string.bubble_mic_failed else R.string.bubble_no_recognizer
            if (visible) bubble.say(ctx.getString(res), VOICE_PROBLEM_BUBBLE_MS)
        }
    }

    private fun throwBody(vx: Float, vy: Float) {
        behavior.dragStart()
        val startX = params.x
        val startY = params.y
        val endX = (startX + vx * THROW_SECONDS).roundToInt().coerceIn(0, maxX())
        val endY = (startY + vy * THROW_SECONDS).roundToInt().coerceIn(0, maxY())
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = THROW_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            moveWindow(startX + ((endX - startX) * f).roundToInt(), startY + ((endY - startY) * f).roundToInt())
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (throwAnim === animation) throwAnim = null
                if (canceled) return
                moveWindow(endX, endY)
                behavior.dragEnd(params.x.toFloat())
                emit(UserDrag(true))
            }
        })
        throwAnim = anim
        anim.start()
    }

    /** Stops a throw in flight; she stays where she is (a new touch or hide takes over). */
    private fun cancelThrow() {
        val anim = throwAnim ?: return
        throwAnim = null
        anim.cancel()
        behavior.dragEnd(params.x.toFloat())
    }

    // ---- geometry ----

    private fun moveWindow(x: Int, y: Int) {
        if (x == params.x && y == params.y) return
        params.x = x
        params.y = y
        if (visible) updateSafely(wm, spriteView, params)
    }

    private fun maxX(): Int = (screenW - spriteSize).coerceAtLeast(0)

    private fun maxY(): Int = (screenH - spriteH).coerceAtLeast(0)

    private fun restingY(): Int = (screenH - spriteH - (BOTTOM_MARGIN_DP * density).roundToInt()).coerceIn(0, maxY())

    private fun newBehavior(): Behavior = Behavior(screenW, spriteSize, Random.Default, SPEED_DP_PER_S * density)

    /** Rotation / resolution change: rebuild the behaviour for the new width and keep her on screen. */
    private fun refreshScreenSize() {
        val (w, h) = screenSize()
        if (w == screenW && h == screenH) return
        val oldX = params.x
        val wasAtRest = params.y == restingY()
        screenW = w
        screenH = h
        behavior = newBehavior()
        behavior.setConnected(connected)
        behavior.dragEnd(oldX.coerceIn(0, maxX()).toFloat())
        if (dragging) behavior.dragStart()
        val y = if (wasAtRest) restingY() else params.y.coerceIn(0, maxY())
        moveWindow(behavior.x.roundToInt(), y)
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val dm = ctx.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private companion object {
        const val FRAME_MS = 33L // ~30 fps
        const val MAX_DT_MS = 250L
        const val SIZE_CHECK_MS = 1000L
        const val SPEED_DP_PER_S = 40f
        const val BOTTOM_MARGIN_DP = 48f
        const val THROW_MS = 350L
        const val THROW_SECONDS = 0.25f
        const val UNREACHABLE_BUBBLE_MS = 3000L
        const val TOGGLE_WINDOW_MS = 500L
        const val LONG_PRESS_MS = 1000L // a stationary press this long is ignored
        const val LISTENING_PREFIX = "🎤 "
        const val MAX_LISTEN_MS = 30_000L
        const val HEARD_BUBBLE_MS = 2500L
        const val VOICE_PROBLEM_BUBBLE_MS = 4000L
    }
}

// ---- shared window helpers ----

/**
 * Layout params for a non-chat overlay window positioned in absolute screen pixels (gravity
 * top-left, not inset by the system bars or the display cutout).
 */
internal fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams {
    val p = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )
    p.gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        p.fitInsetsTypes = 0
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    return p
}

/**
 * On API 30+ overlay windows should be added through a window context for the display; falls back
 * to [context] itself (a Service) if that is not possible.
 */
private fun overlayContext(context: Context): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context
    return try {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return context
        context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } catch (e: RuntimeException) {
        Log.w(TAG, "no window context: ${e.javaClass.simpleName}")
        context
    }
}

// WindowManager calls throw (BadTokenException, IllegalStateException, IllegalArgumentException —
// all RuntimeExceptions) when the overlay permission was revoked or the view is not attached. A
// lost overlay must never crash the service.

internal fun addSafely(wm: WindowManager, view: View, params: WindowManager.LayoutParams): Boolean = try {
    wm.addView(view, params)
    true
} catch (e: RuntimeException) {
    Log.w(TAG, "addView failed: ${e.javaClass.simpleName}")
    false
}

internal fun removeSafely(wm: WindowManager, view: View) {
    try {
        wm.removeView(view)
    } catch (e: RuntimeException) {
        Log.w(TAG, "removeView failed: ${e.javaClass.simpleName}")
    }
}

internal fun updateSafely(wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
    try {
        wm.updateViewLayout(view, params)
    } catch (e: RuntimeException) {
        Log.w(TAG, "updateViewLayout failed: ${e.javaClass.simpleName}")
    }
}
