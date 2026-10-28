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
import com.maya.app.actions.ActionBody
import com.maya.app.voice.VoiceInput
import com.maya.core.Act
import com.maya.core.Action
import com.maya.core.Animate
import com.maya.core.Audio
import com.maya.core.Behavior
import com.maya.core.Body
import com.maya.core.BodyMoment
import com.maya.core.BodyReporter
import com.maya.core.BodySnapshot
import com.maya.core.Box
import com.maya.core.ErrorMsg
import com.maya.core.Event
import com.maya.core.Gesture
import com.maya.core.HOLD_TO_TALK_MS
import com.maya.core.Layout
import com.maya.core.Look
import com.maya.core.Move
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
 * Maya's floating body: a sprite-sized overlay window that roams the whole screen (the floor near
 * the bottom, plus the platforms of the current app's [Layout]), moved by a
 * ~30 fps Choreographer loop that drives [Behavior], plus her speech bubble and the chat window.
 * The brain steers her with [Move] and sets how playful she is with [State]; every frame she
 * reports her body ([Body], throttled by [BodyReporter]) and what happened to it ([BodyMoment]).
 * The loop only runs while she is visible and the screen is on and unlocked ([setScreenOn]).
 * Holding her still for [HOLD_TO_TALK_MS] starts listening ([VoiceInput]); [onListening] reports
 * when listening starts and ends. Lines (and typing dots) that arrive while she listens, or while
 * the bubble still shows what she heard, wait and are shown right after. As the action runner's
 * [ActionBody] she asks the user in her bubble (lines wait meanwhile too), walks to a button and
 * pokes it, and holds a pose while she types. Must be created and used on the main thread.
 */
class OverlayBody(
    context: Context,
    private val emit: (Event) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) : OverlayHook, ActionBody {
    private val ctx: Context = overlayContext(context)
    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val sheet = SpriteSheet(ctx.assets)
    // Integer nearest-neighbour scale so a frame is about 64 dp tall (64 px art -> x3 at density 3);
    // the same rule the screen sense uses to decide which edges she fits on.
    private val metrics = SpriteMetrics.forContext(ctx)
    private val scale = metrics.scale
    private val spriteSize = metrics.width
    private val spriteH = metrics.height
    /** Her feet within the window: the sheet's anchor row (60 of 64), scaled. */
    private val footY = (sheet.anchorY ?: (sheet.frameH - DEFAULT_FOOT_INSET)) * scale
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val choreographer = Choreographer.getInstance()
    private val main = Handler(Looper.getMainLooper())

    private var screenW = 0
    private var screenH = 0
    private var behavior: Behavior
    private var connected = false
    private val reporter = BodyReporter(BODY_THROTTLE_MS)

    // What the body was told, kept so a new Behavior (rotation) starts from the same place.
    private var layout: Layout = Layout.EMPTY
    private var restricted = false
    private var mood: String? = null
    private var tier: String? = null

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
    private var speaking = false // her voice is playing
    private var thinking = false // the brain is working on a reply
    private var looking = false // she just sent a screenshot and waits for what she makes of it
    private val endLook = Runnable {
        looking = false
        updatePose()
    }
    private val holdRunnable = Runnable { onHoldTimer() }
    private val listenCap = Runnable { if (listening) voice.stop() }
    /** The mic is capturing in the current session ([VoiceInput.Listener.onReady] arrived). */
    private var micReady = false
    /** Until this uptime the bubble keeps what listening ended with (heard text or a problem). */
    private var heardUntilMs = 0L
    /** The latest line that arrived while the bubble was held; shown when it is released. */
    private var deferredSay: Say? = null
    /** The latest typing state that arrived while the bubble was held (null: none). */
    private var deferredTyping: Boolean? = null
    private val releaseBubble = Runnable { showDeferred() }

    // action state (ActionBody)
    /** The runner's callback for the question in her bubble; null when not asking. */
    private var confirmAnswer: ((Boolean?) -> Unit)? = null
    /** The runner's callback for the press she is walking to; null when none. */
    private var pressResult: ((Boolean?) -> Unit)? = null
    /** A pose the runner holds (e.g. `type`). */
    private var actPose: String? = null
    /** She holds the `poke` pose (her press lands) until [endPoke]. */
    private var poking = false
    private val endPoke = Runnable {
        poking = false
        updatePose()
    }
    private val restoreTouches = Runnable { setTouchable(true) }

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
        behavior = newBehavior() // napping on the floor until the connection says otherwise
        params.x = windowX()
        params.y = windowY()
        spriteView.setOnTouchListener { _, event -> onTouch(event) }
    }

    // ---- OverlayHook ----

    override fun handle(a: Action) {
        when (a) {
            is Animate -> behavior.play(a.name)
            // While listening the bubble shows what she hears; replies come after it.
            is Say -> {
                thinking = false
                stopLooking()
                updatePose()
                if (bubbleHeld()) {
                    deferredSay = a
                    deferredTyping = null // the line replaces the dots that came before it
                } else if (visible) {
                    bubble.say(a.text, a.bubbleMs)
                }
            }
            is Typing -> {
                thinking = a.on
                if (!a.on) stopLooking()
                updatePose()
                if (bubbleHeld()) deferredTyping = a.on else if (visible) bubble.typing(a.on)
            }
            // Mood and closeness set how often she jumps around.
            is State -> {
                mood = a.mood
                tier = a.closenessTier
                behavior.setTemper(a.mood, a.closenessTier)
            }
            is Audio -> Unit // played by the service's VoicePlayer
            is ErrorMsg -> Unit // the service handles auth errors
            // Ignored by Behavior while she is held, thrown or disconnected.
            is Move -> behavior.command(a.intent, a.target)
            is Look -> Unit // handled by the service (screenshot)
            is Act -> Unit // handled by the service (ActionRunner)
        }
    }

    /** Her voice started playing: she talks (mouth frames loop) until it ends. */
    override fun onVoiceStarted() {
        speaking = true
        updatePose()
    }

    override fun onVoiceEnded() {
        speaking = false
        updatePose()
    }

    /**
     * The current app's platforms and tease targets. An open keyboard does not move her floor: she
     * keeps resting behind it, out of the way of the compose bar and Send (restricted keeps her off
     * the buttons above it). Restriction goes first, so platforms that vanish because she may not
     * use them now (typing, a call, a do-not-watch app) never count as her falling off; nor do
     * those cut away by Maya's own window ([quiet]).
     */
    override fun setScreenLayout(layout: Layout, keyboardTop: Int?, restricted: Boolean, quiet: Boolean) {
        this.layout = layout
        this.restricted = restricted
        behavior.setFloor(floorY())
        behavior.setRestricted(restricted)
        behavior.setLayout(layout, quiet)
    }

    /**
     * She just sent a screenshot to the brain: she holds the `look` pose (think's row until the art
     * has one) until her next line, the typing dots going away, or [LOOK_POSE_MS].
     */
    fun lookedAtScreen() {
        if (!connected) return
        looking = true
        main.removeCallbacks(endLook)
        main.postDelayed(endLook, LOOK_POSE_MS)
        updatePose()
    }

    private fun stopLooking() {
        looking = false
        main.removeCallbacks(endLook)
    }

    /**
     * Sustained pose from what's going on: listening beats asking, then her poke as a press lands,
     * then walking to a press (no pose: she must move), then the runner's pose (typing), then
     * talking, looking and thinking.
     */
    private fun updatePose() {
        behavior.setPose(
            when {
                listening -> "listen"
                confirmAnswer != null -> "ask"
                poking -> "poke"
                pressResult != null -> null
                actPose != null -> actPose
                speaking -> "talk"
                looking && connected -> "look"
                thinking && connected -> "think"
                else -> null
            },
        )
    }

    override fun setConnected(on: Boolean) {
        connected = on
        behavior.setConnected(on)
        if (on) reporter.reset() // the brain hears where she is right away
        if (!on) {
            thinking = false
            stopLooking()
            updatePose()
            deferredTyping = null
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
            dropActions()
        }
    }

    // ---- ActionBody ----

    override val isShowing: Boolean get() = visible && !destroyed

    override fun confirm(question: String, onAnswer: (Boolean?) -> Unit): Boolean {
        if (!visible || destroyed || listening) return false
        answerConfirm(null) // an older question goes away
        // The question replaces what she heard; lines that wait are shown after the answer.
        main.removeCallbacks(releaseBubble)
        heardUntilMs = 0L
        confirmAnswer = onAnswer
        bubble.ask(question, { answerConfirm(true) }, { answerConfirm(false) })
        updatePose()
        return true
    }

    override fun cancelConfirm() {
        if (confirmAnswer == null) return
        confirmAnswer = null
        endConfirm()
    }

    override fun press(box: Box, onResult: (Boolean?) -> Unit): Boolean {
        if (!visible || destroyed || !looping) return false
        pressResult = null
        if (restricted || box.top >= floorY()) {
            // She can't walk there now (keyboard open, a call) or it is below her floor (a bottom
            // bar): she pokes where she stands, and the press goes ahead.
            holdPoke()
            onResult(true)
            return true
        }
        pressResult = onResult
        updatePose() // no pose while she walks
        behavior.commandPress(box)
        return true
    }

    override fun cancelPress() {
        if (pressResult == null) return
        pressResult = null
        updatePose()
    }

    override fun setActPose(name: String?) {
        if (name == actPose) return
        actPose = name
        updatePose()
    }

    override fun letTouchesThrough(ms: Long) {
        setTouchable(false)
        main.removeCallbacks(restoreTouches)
        main.postDelayed(restoreTouches, ms)
    }

    /** Her window takes touches, or lets them through to the app (see-through enough for Android 12+ to pass them). */
    private fun setTouchable(on: Boolean) {
        val flags = if (on) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val alpha = if (on) 1f else MAX_PASS_THROUGH_ALPHA
        if (flags == params.flags && alpha == params.alpha) return
        params.flags = flags
        params.alpha = alpha
        if (visible) updateSafely(wm, spriteView, params)
    }

    /** Ends the question with [yes] (null: it went away with her) and tells the runner. */
    private fun answerConfirm(yes: Boolean?) {
        val callback = confirmAnswer ?: return
        confirmAnswer = null
        endConfirm()
        callback(yes)
    }

    /** The bubble is plain again; lines that waited for the answer are shown. */
    private fun endConfirm() {
        bubble.cancelAsk()
        updatePose()
        if (!bubbleHeld()) showDeferred()
    }

    /** Every frame: tells the runner when her poke on the press box starts, or that she can't get there. */
    private fun checkPress() {
        val callback = pressResult ?: return
        val done = behavior.pressDone()
        if (!done && !behavior.pressTargetLost()) return
        pressResult = null
        if (done) holdPoke() else updatePose()
        callback(done)
    }

    /** The `poke` pose for a moment (her poke must show, whatever else she is doing meanwhile). */
    private fun holdPoke() {
        poking = true
        main.removeCallbacks(endPoke)
        main.postDelayed(endPoke, POKE_POSE_MS)
        updatePose()
    }

    /** She went away (hidden, screen off): a question is withdrawn and a press is lost (as "she went away"). */
    private fun dropActions() {
        answerConfirm(null)
        losePress(null)
    }

    /** The press under way (if any) can't happen: the runner hears false (lost) or null (she went away). */
    private fun losePress(why: Boolean?) {
        val callback = pressResult ?: return
        pressResult = null
        updatePose()
        callback(why)
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
        dropActions()
        main.removeCallbacks(restoreTouches)
        setTouchable(true)
        stopLoop()
        cancelThrow()
        if (dragging) releaseAtWindow()
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
        stopLooking()
        main.removeCallbacks(releaseBubble)
        main.removeCallbacks(endPoke)
        deferredSay = null
        deferredTyping = null
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
        checkPress()
        spriteView.setAnimation(behavior.animation)
        spriteView.facingLeft = behavior.facingLeft
        spriteView.advance(dtMs)
        if (!held()) moveWindow(windowX(), windowY())
        bubble.follow(params.x, params.y, spriteSize, screenW)
        report()
    }

    /** Sends the moments since the last call (dropped while disconnected). */
    private fun emitMoments() {
        val moments = behavior.drainMoments()
        if (!connected) return
        for (m in moments) emit(BodyMoment(m.kind, m.target))
    }

    /** True while the finger or a throw places the window (Behavior is in its dragged state). */
    private fun held(): Boolean = dragging || throwAnim != null

    /**
     * Tells the brain what happened to her ([BodyMoment]) and, throttled, what she is doing and
     * where ([Body]: her feet as fractions of the screen). Nothing is reported while disconnected
     * (moments from then are dropped); [setConnected] makes the next report go out at once.
     */
    private fun report() {
        emitMoments()
        if (!connected) return
        val s = behavior.state
        // While held, Behavior's feet stay where she was picked up: report the window's instead.
        val feetX = if (held()) params.x + spriteSize / 2f else s.feetX
        val feetY = if (held()) (params.y + footY).toFloat() else s.feetY
        val snap = BodySnapshot(s.doing, s.where, s.target, fraction(feetX, screenW), fraction(feetY, screenH))
        val now = SystemClock.elapsedRealtime()
        val out = reporter.update(snap, now) ?: reporter.poll(now) ?: return
        emit(Body(out.doing, out.where, out.target, out.x, out.y))
    }

    private fun fraction(v: Float, of: Int): Double = if (of > 0) (v.toDouble() / of).coerceIn(0.0, 1.0) else 0.0

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
                            releaseAtWindow()
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
                if (wasDragging) releaseAtWindow()
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
        if (wasDragging) releaseAtWindow()
        // While she asks, a tap on her is a no.
        if (confirmAnswer != null) {
            answerConfirm(false)
            return
        }
        // A tap while she teases a button shoos her off it: the brain still hears the tap, but
        // no chat window opens.
        val shooed = behavior.tapped()
        if (shooed) emitMoments() // the brain hears it was a shoo before it hears the tap
        emit(UserTap())
        if (shooed) return
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
        if (confirmAnswer != null) return // her bubble holds a question: answer it with ✔ / ✖ (or tap her)
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
        listening = true
        updatePose()
        micReady = false
        // Lines still waiting from the last session keep waiting until this one ends.
        main.removeCallbacks(releaseBubble)
        heardUntilMs = 0L
        onListening(true) // stops her voice
        // Not "🎤" yet: the mic may take a moment to start (Android 14+ opens an activity first).
        // onReady buzzes and shows the mic, so the user knows when to speak.
        bubble.hold(WAITING_BUBBLE)
        main.postDelayed(listenCap, MAX_LISTEN_MS)
        voice.start() // may end the session synchronously (no permission, no recognizer)
    }

    private fun endListening() {
        if (!listening) return
        listening = false
        updatePose()
        main.removeCallbacks(listenCap)
        onListening(false)
    }

    /** Drops a running voice session (hidden, screen off): nothing is sent. */
    private fun cancelListening() {
        if (!listening) return
        voice.cancel()
        endListening()
        bubble.hide()
        showDeferred()
    }

    /** True while lines must wait: she is listening or asking, or the bubble shows how listening ended. */
    private fun bubbleHeld(): Boolean = listening || confirmAnswer != null || SystemClock.uptimeMillis() < heardUntilMs

    /** Listening ended with a bubble ([HEARD_MIN_MS] to read it); waiting lines follow it. */
    private fun holdEndBubble() {
        heardUntilMs = SystemClock.uptimeMillis() + HEARD_MIN_MS
        main.removeCallbacks(releaseBubble)
        main.postDelayed(releaseBubble, HEARD_MIN_MS)
    }

    /** Shows the line (or typing dots) that waited while the bubble was held. */
    private fun showDeferred() {
        if (confirmAnswer != null) return // shown once the question is answered
        main.removeCallbacks(releaseBubble)
        heardUntilMs = 0L
        val say = deferredSay
        val typing = deferredTyping
        deferredSay = null
        deferredTyping = null
        if (!visible) return
        if (say != null) {
            bubble.say(say.text, say.bubbleMs)
        } else if (typing != null) {
            bubble.typing(typing)
        }
    }

    private inner class VoiceListener : VoiceInput.Listener {
        override fun onReady() {
            if (!listening || micReady) return
            micReady = true
            if (!visible) return
            spriteView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            bubble.hold(LISTENING_PREFIX + "…")
        }

        override fun onPartial(text: String) {
            if (!listening) return
            micReady = true
            if (visible) bubble.hold(LISTENING_PREFIX + text)
        }

        override fun onFinal(text: String) {
            if (!listening) return
            endListening()
            if (!connected) {
                bubble.say(ctx.getString(R.string.bubble_unreachable), UNREACHABLE_BUBBLE_MS)
                holdEndBubble()
                return
            }
            emit(UserMessage(text, voice = true))
            if (visible) bubble.say(LISTENING_PREFIX + text, HEARD_BUBBLE_MS)
            holdEndBubble()
        }

        override fun onNoMatch() {
            if (!listening) return
            endListening()
            if (visible) bubble.say(ctx.getString(R.string.bubble_no_match), VOICE_PROBLEM_BUBBLE_MS)
            holdEndBubble()
        }

        override fun onUnavailable(reason: VoiceInput.Reason) {
            // BACKGROUND_DENIED is only a notice: VoiceInput carries on through its activity.
            if (reason == VoiceInput.Reason.BACKGROUND_DENIED || !listening) return
            endListening()
            val res = when (reason) {
                VoiceInput.Reason.PERMISSION -> R.string.bubble_mic_failed
                VoiceInput.Reason.MIC_BUSY -> R.string.bubble_mic_busy
                VoiceInput.Reason.NO_RECOGNIZER -> R.string.bubble_no_recognizer
                VoiceInput.Reason.OFFLINE_MISSING -> R.string.bubble_offline_missing
                VoiceInput.Reason.RECOGNITION_FAILED -> R.string.bubble_recognition_failed
                VoiceInput.Reason.BACKGROUND_DENIED -> return
            }
            if (visible) bubble.say(ctx.getString(res), VOICE_PROBLEM_BUBBLE_MS)
            holdEndBubble()
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
                releaseAtWindow()
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
        releaseAtWindow()
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

    /** Her window follows [behavior] (the sprite box), kept on screen. */
    private fun windowX(): Int = behavior.x.roundToInt().coerceIn(0, maxX())

    private fun windowY(): Int = behavior.y.roundToInt().coerceIn(0, maxY())

    /** Lets go of her where her window is now; she falls (or lands at once) from there. */
    private fun releaseAtWindow() = behavior.dragEnd(params.x.toFloat(), params.y.toFloat())

    /**
     * Where her feet rest on the floor: the line they always rested on, [BOTTOM_MARGIN_DP]
     * above the screen bottom (the window's bottom edge) less the gap under her feet.
     */
    private fun defaultFloorY(): Int {
        val restingTop = (screenH - spriteH - (BOTTOM_MARGIN_DP * density).roundToInt()).coerceIn(0, maxY())
        return restingTop + footY
    }

    /** Her floor: always the usual resting line (behind the keyboard while one is open). */
    private fun floorY(): Int = defaultFloorY()

    /** A body for the current screen size that knows everything the old one was told. */
    private fun newBehavior(): Behavior {
        val b = Behavior(
            screenW,
            screenH,
            spriteSize,
            spriteH,
            footY,
            Random.Default,
            SPEED_DP_PER_S * density,
            GRAVITY_DP_PER_S2 * density,
        )
        b.setFloor(floorY()) // a floor above the start carries her up onto it
        b.setRestricted(restricted)
        b.setLayout(layout)
        val m = mood
        val t = tier
        if (m != null && t != null) b.setTemper(m, t)
        b.setConnected(connected)
        return b
    }

    /**
     * Rotation / resolution change: rebuild the behaviour for the new size and drop her in from
     * where she was (clamped to the new screen), so she lands on whatever is below.
     */
    private fun refreshScreenSize() {
        val (w, h) = screenSize()
        if (w == screenW && h == screenH) return
        val oldX = params.x
        val oldY = params.y
        screenW = w
        screenH = h
        losePress(false) // the old body's press can't finish
        behavior = newBehavior()
        behavior.dragEnd(oldX.coerceIn(0, maxX()).toFloat(), oldY.coerceIn(0, maxY()).toFloat())
        if (held()) behavior.dragStart()
        updatePose() // the new body starts without the old one's pose
        if (held()) {
            moveWindow(params.x.coerceIn(0, maxX()), params.y.coerceIn(0, maxY()))
        } else {
            moveWindow(windowX(), windowY())
        }
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
        const val GRAVITY_DP_PER_S2 = 3000f
        const val DEFAULT_FOOT_INSET = 4 // feet this many art px above the frame bottom (no anchor)
        const val BODY_THROTTLE_MS = 5000L
        const val BOTTOM_MARGIN_DP = 48f
        const val THROW_MS = 350L
        /** After she sent a screenshot she holds the `look` pose until her answer comes, at most this long. */
        const val LOOK_POSE_MS = 15_000L
        const val THROW_SECONDS = 0.25f
        const val UNREACHABLE_BUBBLE_MS = 3000L
        const val TOGGLE_WINDOW_MS = 500L
        const val LONG_PRESS_MS = 1000L // a stationary press this long is ignored
        const val LISTENING_PREFIX = "🎤 "
        const val WAITING_BUBBLE = "…" // hold recognised, mic not capturing yet
        const val MAX_LISTEN_MS = 30_000L
        const val HEARD_BUBBLE_MS = 2500L
        const val HEARD_MIN_MS = 1500L // nothing replaces the heard text (or a problem) before this
        const val VOICE_PROBLEM_BUBBLE_MS = 4000L
        /** Her poke on a pressed button shows at least this long. */
        const val POKE_POSE_MS = 800L
        /** Android 12+ passes touches through an overlay only up to this opacity. */
        const val MAX_PASS_THROUGH_ALPHA = 0.8f
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
