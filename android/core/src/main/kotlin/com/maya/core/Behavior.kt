package com.maya.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

const val DECISION_MIN_MS = 3000
const val DECISION_MAX_MS = 8000
const val WALK_MIN_MS = 2000
const val WALK_MAX_MS = 6000
const val SIT_MIN_MS = 5000
const val SIT_MAX_MS = 15000
const val ONE_SHOT_MS = 2500

/** Share of the decisions left after jump/peek/drop that walk / sit; the rest (35 %) stays idle. */
const val WALK_CHANCE_PCT = 40
const val SIT_CHANCE_PCT = 25
const val PEEK_CHANCE_PCT = 5
const val DROP_CHANCE_PCT = 5

const val JUMP_MIN_MS = 400
const val JUMP_MAX_MS = 900
private const val JUMP_SPAN_MS = 500 // added for a jump across the whole screen diagonal
private const val JUMP_APEX = 0.25f // apex height above the higher end, × jump distance
const val FOLLOW_TOLERANCE_PX = 8

/** Sustained poses the app holds while something is going on (her voice, listening, thinking). */
val BODY_POSES = listOf("talk", "listen", "think")

/** Animations that `play` shows for [ONE_SHOT_MS] and then return to idle. */
private val ONE_SHOTS = setOf("wave", "peek", "happy", "sulk", "surprised")

/**
 * Renderer-agnostic snapshot of her body (spec §4.9), in screen pixels: feet position, velocity
 * (px/s, y down), facing, whether she stands on something, what she is doing and where (wire
 * vocabulary: `doing` idle|walk|sit|perch|jump|fall|nap|peek|tease|dragged, `where`
 * floor|platform|target), the tease target tag, the gesture on top (a one-shot such as `wave`, or
 * a pose such as `talk`) and the point she looks at.
 */
data class BodyState(
    val feetX: Float,
    val feetY: Float,
    val vx: Float,
    val vy: Float,
    val facingLeft: Boolean,
    val grounded: Boolean,
    val doing: String,
    val where: String,
    val target: String?,
    val gesture: String?,
    val lookX: Float,
    val lookY: Float,
)

/**
 * Maya's body in 2-D: she stands with her feet on the floor or on a platform (a top edge from
 * [Layout]), walks along it, jumps between platforms in parabolic arcs, falls under gravity when
 * the ground under her goes, and naps, sits, peeks and plays one-shot reactions. The view drives
 * it with [tick] and reads [animation], [x], [y] and [facingLeft] (the sprite box), or [state].
 *
 * All values are pixels: [speedPxPerS] and [gravityPxPerS2] come already scaled; the feet are at
 * `(x + spriteW / 2, y + footY)`. The floor is at [screenH] until [setFloor] moves it. Deterministic
 * for a given [rng].
 */
class Behavior(
    private val screenW: Int,
    private val screenH: Int,
    private val spriteW: Int,
    private val spriteH: Int,
    private val footY: Int,
    private val rng: Random,
    private val speedPxPerS: Float,
    private val gravityPxPerS2: Float,
) {
    private enum class Mode { IDLE, WALK, SIT, NAP, ONE_SHOT, JUMP, FALL }

    /** What a walk toward [dest] ends in. */
    private enum class Then { NONE, PEEK, DROP }

    private val half = spriteW / 2f
    private val maxX: Float get() = (screenW - spriteW).coerceAtLeast(0).toFloat()
    private val screenDiag = hypot(screenW.toFloat(), screenH.toFloat())

    private var mode = Mode.IDLE
    private var oneShot = "idle"
    private var remainingMs = 0L
    private var connected = true
    private var dragging = false
    private var direction = -1 // -1 = left, +1 = right
    private var pose: String? = null

    private var feetX = maxX + half
    private var feetY = screenH.toFloat()
    private var floorY = screenH.toFloat()
    private var platform: Platform? = null // null = the floor
    private var platforms: List<Platform> = emptyList()
    private var restricted = false
    private var mood = ""
    private var tier = "stranger"

    private var dest: Float? = null
    private var then = Then.NONE

    private var vx = 0f
    private var vy = 0f
    private var jumpTarget: Platform? = null
    private var jumpFromX = 0f
    private var jumpFromY = 0f
    private var jumpToX = 0f
    private var jumpElapsedMs = 0L
    private var jumpDurMs = 0L

    /** Sprite box left. */
    val x: Float get() = feetX - half

    /** Sprite box top. */
    val y: Float get() = feetY - footY

    /** Starts facing left, toward the screen centre; follows her movement afterwards. */
    var facingLeft: Boolean = true
        private set

    private val airborne: Boolean get() = mode == Mode.JUMP || mode == Mode.FALL

    val animation: String
        get() = when {
            dragging -> if (connected) "surprised" else "nap"
            mode == Mode.JUMP -> "jump"
            mode == Mode.FALL -> "fall"
            !connected -> "nap"
            pose != null -> pose!!
            else -> when (mode) {
                Mode.WALK -> "walk"
                Mode.SIT -> if (platform != null) "perch" else "sit"
                Mode.NAP -> "nap"
                Mode.ONE_SHOT -> oneShot
                else -> "idle"
            }
        }

    val state: BodyState
        get() {
            val walking = mode == Mode.WALK && connected && pose == null && !dragging
            val doing = when {
                dragging -> "dragged"
                mode == Mode.JUMP -> "jump"
                mode == Mode.FALL -> "fall"
                !connected -> "nap"
                pose != null -> "idle"
                else -> when (mode) {
                    Mode.WALK -> "walk"
                    Mode.SIT -> if (platform != null) "perch" else "sit"
                    Mode.NAP -> "nap"
                    Mode.ONE_SHOT -> if (oneShot == "peek") "peek" else "idle"
                    else -> "idle"
                }
            }
            val gesture = when {
                dragging || !connected -> null
                pose != null -> pose
                mode == Mode.ONE_SHOT && oneShot != "peek" -> oneShot
                else -> null
            }
            val d = dest
            val target = jumpTarget
            val (lookX, lookY) = when {
                mode == Mode.JUMP && target != null -> jumpToX to target.top.toFloat()
                walking && d != null -> d to feetY
                pose == "talk" -> screenW / 2f to screenH / 2f
                else -> (if (facingLeft) feetX - spriteW else feetX + spriteW) to feetY
            }
            return BodyState(
                feetX = feetX,
                feetY = feetY,
                vx = when {
                    dragging -> 0f
                    airborne -> vx
                    walking -> direction * speedPxPerS
                    else -> 0f
                },
                vy = if (airborne && !dragging) vy else 0f,
                facingLeft = facingLeft,
                grounded = !dragging && !airborne,
                doing = doing,
                where = if (platform != null) "platform" else "floor",
                target = null,
                gesture = gesture,
                lookX = lookX,
                lookY = lookY,
            )
        }

    init {
        goIdle()
    }

    fun tick(dtMs: Long) {
        if (dtMs <= 0 || dragging) return
        when (mode) {
            Mode.JUMP -> { stepJump(dtMs); return }
            Mode.FALL -> { stepFall(dtMs); return }
            else -> {}
        }
        if (!connected || pose != null) return
        if (mode == Mode.WALK && walk(dtMs)) return
        remainingMs -= dtMs
        if (remainingMs > 0) return
        if (mode == Mode.IDLE) decide() else goIdle()
    }

    fun play(name: String) {
        if (!connected || dragging || airborne) return
        when {
            name in ONE_SHOTS -> startOneShot(name)
            name == "walk" -> startWalk()
            name == "sit" -> startSit()
            name == "nap" -> { mode = Mode.NAP; dest = null; remainingMs = rangeMs(SIT_MIN_MS, SIT_MAX_MS) }
            name == "idle" -> goIdle()
            // unknown names are ignored
        }
    }

    /**
     * Holds [name] (e.g. "talk" while her voice plays) until cleared with null; she stands still
     * meanwhile (but still falls if the ground goes). Napping, being dragged and being in the air
     * still take precedence.
     */
    fun setPose(name: String?) {
        if (name == pose) return
        pose = name
        if (name == null && !airborne) goIdle()
    }

    /** Disconnected, she naps where she stands; jumps and falls still finish. */
    fun setConnected(on: Boolean) {
        if (on == connected) return
        connected = on
        if (on && !airborne) goIdle()
    }

    fun dragStart() {
        dragging = true
        platform = null
        jumpTarget = null
        dest = null
        mode = Mode.IDLE
        vx = 0f
        vy = 0f
    }

    /** Lets go of her with the sprite box at ([x], [y]) (clamped to the screen); she falls from there. */
    fun dragEnd(x: Float, y: Float) {
        dragging = false
        feetX = x.coerceIn(0f, maxX) + half
        feetY = y.coerceIn(0f, (floorY - footY).coerceAtLeast(0f)) + footY
        startFall(0f)
        stepFall(0) // released right on a surface: she stands there at once
    }

    /** Where her feet rest when on the floor (screen bottom, or the keyboard's top). */
    fun setFloor(feetY: Int) {
        val old = floorY
        floorY = feetY.toFloat()
        if (dragging) return
        if (!airborne && platform == null) {
            // a rising floor carries her; one that drops away more than the follow tolerance, she falls to
            if (floorY - old <= FOLLOW_TOLERANCE_PX) this.feetY = floorY else startFall(0f)
        }
        revalidate()
    }

    /** Replaces the platforms; she follows hers if it moved ≤ 8 px, else (or if gone) she falls. */
    fun setLayout(layout: Layout) {
        platforms = layout.platforms
        revalidate()
    }

    /** Restricted (keyboard, call, do-not-watch app): only the floor exists, she never jumps. */
    fun setRestricted(on: Boolean) {
        if (on == restricted) return
        restricted = on
        revalidate()
    }

    /** The brain's mood and closeness tier; they set how often she jumps. */
    fun setTemper(mood: String, tier: String) {
        this.mood = mood
        this.tier = tier
    }

    /** Platforms she could jump to from where her feet are now (excluding the one she is on). */
    internal fun reachable(): List<Platform> = usable().filter { it != platform && canReach(it) }

    /** Jumps to [p] if it is reachable and she is standing; returns whether she took off. */
    internal fun jumpTo(p: Platform): Boolean {
        if (dragging || airborne || p !in reachable()) return false
        val (lo, hi) = walkRange(p)
        val inset = min(half, (hi - lo) / 2f)
        val land = feetX.coerceIn(lo + inset, hi - inset)
        if (land != feetX) facingLeft = land < feetX
        mode = Mode.JUMP
        dest = null
        jumpTarget = p
        jumpFromX = feetX
        jumpFromY = feetY
        jumpToX = land
        jumpElapsedMs = 0
        val dist = hypot(land - feetX, p.top - feetY)
        jumpDurMs = (JUMP_MIN_MS + JUMP_SPAN_MS * dist / screenDiag).toLong()
            .coerceIn(JUMP_MIN_MS.toLong(), JUMP_MAX_MS.toLong())
        platform = null
        vx = 0f
        vy = 0f
        return true
    }

    /** One idle decision roll: walk, jump, sit, peek, drop or idle. */
    internal fun pickAction(): String {
        val jump = jumpChancePct()
        val rest = 100 - jump - PEEK_CHANCE_PCT - DROP_CHANCE_PCT
        val walk = rest * WALK_CHANCE_PCT / 100
        val sit = rest * SIT_CHANCE_PCT / 100
        val roll = rng.nextInt(100)
        var edge = jump
        if (roll < edge) return "jump"
        edge += PEEK_CHANCE_PCT
        if (roll < edge) return "peek"
        edge += DROP_CHANCE_PCT
        if (roll < edge) return "drop"
        edge += walk
        if (roll < edge) return "walk"
        edge += sit
        if (roll < edge) return "sit"
        return "idle"
    }

    /** Carries out a decision; a jump with nothing reachable, or a drop from the floor, is a walk. */
    internal fun act(choice: String) {
        when (choice) {
            "walk" -> startWalk()
            "jump" -> {
                val r = reachable()
                if (r.isEmpty()) startWalk() else jumpTo(r[rng.nextInt(r.size)])
            }
            "sit" -> startSit()
            "peek" -> startPeek()
            "drop" -> if (!startDrop()) startWalk()
            else -> goIdle()
        }
    }

    // ---- decisions ----

    private fun jumpChancePct(): Int {
        if (mood == "sleepy" || mood == "sulky") return 10
        val base = when (tier) {
            "friend" -> 25
            "close" -> 40
            else -> return 10 // stranger (or unknown): mostly the floor
        }
        return if (mood == "excited" || mood == "happy") base + 10 else base
    }

    private fun decide() = act(pickAction())

    private fun startWalk() {
        mode = Mode.WALK
        dest = null
        then = Then.NONE
        direction = if (rng.nextBoolean()) 1 else -1
        facingLeft = direction < 0
        remainingMs = rangeMs(WALK_MIN_MS, WALK_MAX_MS)
    }

    /** Walks to [to] (feet x on her current surface) facing [dir], then does [after]. */
    private fun walkTo(to: Float, dir: Int, after: Then) {
        mode = Mode.WALK
        dest = to
        then = after
        direction = dir
        facingLeft = dir < 0
        remainingMs = Long.MAX_VALUE
        if (feetX == to) arrive()
    }

    /** Peek over the nearer end of whatever she stands on. */
    private fun startPeek() {
        val (lo, hi) = walkRange(platform)
        if (feetX - lo <= hi - feetX) walkTo(lo, -1, Then.PEEK) else walkTo(hi, 1, Then.PEEK)
    }

    /** Walk to the nearer edge of her platform that has room beside it and step off; false on the floor. */
    private fun startDrop(): Boolean {
        val p = platform ?: return false
        val left = dropEdge(p, -1)
        val right = dropEdge(p, 1)
        val goLeft = when {
            left != null && right != null -> feetX - left <= right - feetX
            left != null -> true
            right != null -> false
            else -> return false
        }
        if (goLeft) walkTo(left!!, -1, Then.DROP) else walkTo(right!!, 1, Then.DROP)
        return true
    }

    /** The edge of [p] on side [dir] she can step off from, or null when the screen side is right there. */
    private fun dropEdge(p: Platform, dir: Int): Float? {
        val (screenLo, screenHi) = walkRange(null)
        return if (dir < 0) p.left.toFloat().takeIf { it - 1f >= screenLo }
        else p.right.toFloat().takeIf { it + 1f <= screenHi }
    }

    private fun arrive() {
        val after = then
        dest = null
        then = Then.NONE
        when (after) {
            Then.PEEK -> startOneShot("peek")
            Then.DROP -> { feetX += direction; startFall(0f) }
            Then.NONE -> goIdle()
        }
    }

    private fun startSit() {
        mode = Mode.SIT
        dest = null
        remainingMs = rangeMs(SIT_MIN_MS, SIT_MAX_MS)
    }

    private fun startOneShot(name: String) {
        mode = Mode.ONE_SHOT
        dest = null
        oneShot = name
        remainingMs = ONE_SHOT_MS.toLong()
    }

    private fun goIdle() {
        mode = Mode.IDLE
        dest = null
        then = Then.NONE
        remainingMs = rangeMs(DECISION_MIN_MS, DECISION_MAX_MS)
    }

    private fun rangeMs(min: Int, max: Int): Long = rng.nextInt(min, max + 1).toLong()

    // ---- movement ----

    /** Moves along her surface; true when she arrived somewhere and the mode changed. */
    private fun walk(dtMs: Long): Boolean {
        val (lo, hi) = walkRange(platform)
        feetX += direction * speedPxPerS * dtMs / 1000f
        val d = dest
        if (d != null && (if (direction < 0) feetX <= d else feetX >= d)) {
            feetX = d
            arrive()
            return true
        }
        if (feetX <= lo) { feetX = lo; direction = 1 } else if (feetX >= hi) { feetX = hi; direction = -1 }
        facingLeft = direction < 0
        return false
    }

    private fun stepJump(dtMs: Long) {
        val target = jumpTarget ?: return startFall(0f)
        jumpElapsedMs += dtMs
        if (jumpElapsedMs >= jumpDurMs) {
            feetX = jumpToX
            land(target)
            return
        }
        // y(t) = y0 + b t + a t², t in 0..1, through both ends with its top at the apex
        val y0 = jumpFromY
        val y1 = target.top.toFloat()
        val dist = hypot(jumpToX - jumpFromX, y1 - y0)
        val apex = min(y0, y1) - JUMP_APEX * dist
        val s0 = sqrt(y0 - apex)
        val s1 = sqrt(y1 - apex)
        val a = (s0 + s1) * (s0 + s1)
        val b = -2f * s0 * (s0 + s1)
        val t = jumpElapsedMs.toFloat() / jumpDurMs
        val durS = jumpDurMs / 1000f
        feetX = jumpFromX + (jumpToX - jumpFromX) * t
        feetY = y0 + b * t + a * t * t
        vx = (jumpToX - jumpFromX) / durS
        vy = (b + 2f * a * t) / durS
    }

    /** Falls (straight down) from where she is; [vy0] carries the upward/downward speed she had. */
    private fun startFall(vy0: Float) {
        mode = Mode.FALL
        dest = null
        then = Then.NONE
        platform = null
        jumpTarget = null
        vx = 0f
        vy = vy0
    }

    /** Gravity; she lands on the first platform top her feet cross within its x-range, else the floor. */
    private fun stepFall(dtMs: Long) {
        val s = dtMs / 1000f
        vy += gravityPxPerS2 * s
        val prev = feetY
        val next = feetY + vy * s
        val hit = usable()
            .filter { it.top >= prev && it.top <= next && feetX >= it.left && feetX <= it.right }
            .minByOrNull { it.top }
        when {
            hit != null -> land(hit)
            next >= floorY -> land(null)
            else -> feetY = next
        }
    }

    private fun land(p: Platform?) {
        platform = p
        jumpTarget = null
        feetY = p?.top?.toFloat() ?: floorY
        vx = 0f
        vy = 0f
        goIdle()
    }

    // ---- platforms ----

    private fun usable(): List<Platform> =
        if (restricted) emptyList() else platforms.filter { it.top < floorY }

    /** Feet x range on [p] (null = floor): the platform's span, kept so the sprite stays on screen. */
    private fun walkRange(p: Platform?): Pair<Float, Float> {
        val screenLo = half
        val screenHi = maxX + half
        if (p == null) return screenLo to screenHi
        val lo = max(p.left.toFloat(), screenLo)
        val hi = min(p.right.toFloat(), screenHi)
        if (lo <= hi) return lo to hi
        val mid = ((p.left + p.right) / 2f).coerceIn(screenLo, screenHi)
        return mid to mid
    }

    /** Δx to the nearest point of [p] ≤ 0.6 screenW and rise ≤ 0.45 screenH (any drop is fine). */
    private fun canReach(p: Platform): Boolean {
        val dx = when {
            feetX < p.left -> p.left - feetX
            feetX > p.right -> feetX - p.right
            else -> 0f
        }
        val rise = feetY - p.top
        return dx * 10f <= screenW * 6f && rise * 20f <= screenH * 9f
    }

    /** The platform in the current set that [p] became: itself, or the same tag overlapping in x within 8 px of y. */
    private fun follow(p: Platform): Platform? {
        val now = usable()
        if (p in now) return p
        return now
            .filter { it.tag == p.tag && it.left <= p.right && p.left <= it.right && abs(it.top - p.top) <= FOLLOW_TOLERANCE_PX }
            .minWithOrNull(compareBy<Platform>({ abs(it.top - p.top) }, { abs(it.left - p.left) + abs(it.right - p.right) }))
    }

    /** Horizontal shift of a followed platform: only when it slid as a whole (same width). */
    private fun shiftX(from: Platform, to: Platform): Int =
        if (to.right - to.left == from.right - from.left) to.left - from.left else 0

    /** After the platforms, floor or restriction changed: follow hers, or fall. */
    private fun revalidate() {
        if (dragging) return
        when (mode) {
            Mode.FALL -> {}
            Mode.JUMP -> {
                val t = jumpTarget ?: return
                val m = follow(t)
                if (m == null) {
                    startFall(max(vy, 0f))
                } else if (m != t) {
                    val (lo, hi) = walkRange(m)
                    jumpToX = (jumpToX + shiftX(t, m)).coerceIn(lo, hi)
                    jumpTarget = m
                }
            }
            else -> {
                val p = platform ?: return
                val m = follow(p)
                if (m == null) {
                    startFall(0f)
                } else if (m != p) {
                    val dx = shiftX(p, m)
                    val (lo, hi) = walkRange(m)
                    feetX = (feetX + dx).coerceIn(lo, hi)
                    feetY = m.top.toFloat()
                    platform = m
                    // an edge she is heading for is the new platform's edge
                    val d = dest ?: return
                    when (then) {
                        Then.DROP -> dest = dropEdge(m, direction) ?: return goIdle()
                        Then.PEEK -> dest = if (direction < 0) lo else hi
                        Then.NONE -> dest = (d + dx).coerceIn(lo, hi)
                    }
                }
            }
        }
    }
}
