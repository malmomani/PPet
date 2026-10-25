package com.maya.core

import kotlin.random.Random

const val DECISION_MIN_MS = 3000
const val DECISION_MAX_MS = 8000
const val WALK_MIN_MS = 2000
const val WALK_MAX_MS = 6000
const val SIT_MIN_MS = 5000
const val SIT_MAX_MS = 15000
const val ONE_SHOT_MS = 2500
const val WALK_CHANCE_PCT = 40
const val SIT_CHANCE_PCT = 25 // remainder (35 %) stays idle

/** Sustained poses the app holds while something is going on (her voice, listening, thinking). */
val BODY_POSES = listOf("talk", "listen", "think")

/** Animations that `play` shows for [ONE_SHOT_MS] and then return to idle. */
private val ONE_SHOTS = setOf("wave", "peek", "happy", "sulk", "surprised")

/**
 * Maya's body: decides what the character is doing (idle / walking / sitting / napping / one-shot
 * reactions) and where she is horizontally. The view drives it with [tick] and reads [animation],
 * [x] and [facingLeft]; y is the view's concern.
 */
class Behavior(
    private val screenW: Int,
    private val spriteW: Int,
    private val rng: Random,
    private val speedPxPerS: Float,
) {
    private enum class Mode { IDLE, WALK, SIT, NAP, ONE_SHOT }

    private val maxX: Float get() = (screenW - spriteW).coerceAtLeast(0).toFloat()

    private var mode = Mode.IDLE
    private var oneShot = "idle"
    private var remainingMs = 0L
    private var connected = true
    private var dragging = false
    private var direction = -1 // -1 = left, +1 = right
    private var pose: String? = null

    var x: Float = maxX
        private set

    /** Starts facing left, toward the screen centre; follows the walking direction afterwards. */
    var facingLeft: Boolean = true
        private set

    val animation: String
        get() = when {
            !connected -> "nap"
            dragging -> "surprised"
            pose != null -> pose!!
            else -> when (mode) {
                Mode.IDLE -> "idle"
                Mode.WALK -> "walk"
                Mode.SIT -> "sit"
                Mode.NAP -> "nap"
                Mode.ONE_SHOT -> oneShot
            }
        }

    init {
        goIdle()
    }

    fun tick(dtMs: Long) {
        if (!connected || dragging || pose != null || dtMs <= 0) return
        if (mode == Mode.WALK) walk(dtMs)
        remainingMs -= dtMs
        if (remainingMs > 0) return
        if (mode == Mode.IDLE) decide() else goIdle()
    }

    fun play(name: String) {
        if (!connected || dragging) return
        when {
            name in ONE_SHOTS -> { mode = Mode.ONE_SHOT; oneShot = name; remainingMs = ONE_SHOT_MS.toLong() }
            name == "walk" -> startWalk()
            name == "sit" -> startSit()
            name == "nap" -> { mode = Mode.NAP; remainingMs = rangeMs(SIT_MIN_MS, SIT_MAX_MS) }
            name == "idle" -> goIdle()
            // unknown names are ignored
        }
    }

    /**
     * Holds [name] (e.g. "talk" while her voice plays) until cleared with null; she stands still
     * meanwhile. Disconnected napping and being dragged still take precedence.
     */
    fun setPose(name: String?) {
        if (name == pose) return
        pose = name
        if (name == null) goIdle()
    }

    fun setConnected(on: Boolean) {
        if (on == connected) return
        connected = on
        if (on) goIdle()
    }

    fun dragStart() {
        dragging = true
    }

    fun dragEnd(x: Float) {
        dragging = false
        this.x = x.coerceIn(0f, maxX)
        goIdle()
    }

    private fun walk(dtMs: Long) {
        x += direction * speedPxPerS * dtMs / 1000f
        if (x <= 0f) { x = 0f; direction = 1 } else if (x >= maxX) { x = maxX; direction = -1 }
        facingLeft = direction < 0
    }

    private fun decide() {
        val roll = rng.nextInt(100)
        when {
            roll < WALK_CHANCE_PCT -> startWalk()
            roll < WALK_CHANCE_PCT + SIT_CHANCE_PCT -> startSit()
            else -> goIdle()
        }
    }

    private fun startWalk() {
        mode = Mode.WALK
        direction = if (rng.nextBoolean()) 1 else -1
        facingLeft = direction < 0
        remainingMs = rangeMs(WALK_MIN_MS, WALK_MAX_MS)
    }

    private fun startSit() {
        mode = Mode.SIT
        remainingMs = rangeMs(SIT_MIN_MS, SIT_MAX_MS)
    }

    private fun goIdle() {
        mode = Mode.IDLE
        remainingMs = rangeMs(DECISION_MIN_MS, DECISION_MAX_MS)
    }

    private fun rangeMs(min: Int, max: Int): Long = rng.nextInt(min, max + 1).toLong()
}
