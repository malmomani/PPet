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

/** `stay`: she stands still this long before deciding anything again. */
const val STAY_MS = 10_000
/** A commanded `nap` ends after this (or on the next move or tap). */
const val NAP_MS = 60_000
/** A `sit_on` tease lasts this long on the target (hard cap = the max). */
const val TEASE_SIT_MIN_MS = 4000
const val TEASE_SIT_MAX_MS = 8000
const val POKE_MS = 2500
const val SHOO_SULK_MS = 1500
/** A tease she cannot reach within this long is given up (she plays `happy`). */
const val TEASE_REACH_MS = 6000
/** Any other move she has not finished within this long is given up (she roams on her own again). */
const val MOVE_MAX_MS = 15_000

/** Sustained poses the app holds while something is going on (her voice, listening, thinking). */
val BODY_POSES = listOf("talk", "listen", "think")

/** Animations that `play` shows for [ONE_SHOT_MS] and then return to idle. */
private val ONE_SHOTS = setOf("wave", "peek", "happy", "sulk", "surprised")

/** Something that happened to her body, for the brain: [kind] `fell` or `shooed`; [target] the tease tag involved. */
data class Moment(val kind: String, val target: String?)

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
 * the ground under her goes, and naps, sits, peeks and plays one-shot reactions. The brain steers
 * her with [command] (moves and teases on tagged buttons); [tapped] shoos her off a tease and
 * [drainMoments] reports what happened to her. The view drives it with [tick] and reads
 * [animation], [x], [y] and [facingLeft] (the sprite box), or [state].
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
    private enum class Mode { IDLE, WALK, SIT, NAP, ONE_SHOT, JUMP, FALL, TEASE }

    /** What a walk toward [dest] ends in. */
    private enum class Then { NONE, PEEK, DROP, GOAL }

    /** What she does on reaching a [Goal]. */
    private enum class Arrive { NONE, PEEK, TEASE }

    /** A brain move being carried out: get onto [surface] (null = the floor), at feet x [x] (null = anywhere). */
    private class Goal(var surface: Platform?, var x: Float?, val arrive: Arrive = Arrive.NONE) {
        var elapsedMs = 0L
        val tease: Boolean get() = arrive == Arrive.TEASE
    }

    /** Something asked for while she could not do it yet (in the air, or hopping off a target). */
    private sealed interface Deferred
    private data class DeferredCmd(val intent: String, val tag: String?) : Deferred
    private data class DeferredPlay(val name: String, val ms: Long) : Deferred

    private enum class Phase { APPROACH, SIT, POKE, SULK }

    /** A tease: [target] (followed as the layout changes) and the edge she stands on to reach it. */
    private class Tease(val kind: String, val tag: String, var target: Target, var surface: Platform) {
        var phase = Phase.APPROACH
        var onTargetMs = 0L
    }

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
    private var targets: List<Target> = emptyList()
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

    private var goal: Goal? = null
    private var tease: Tease? = null
    /** At most one command and one play, in the order they arrived; run on landing (a command also when a one-shot ends). */
    private val deferred = ArrayList<Deferred>()
    private val moments = ArrayList<Moment>()
    private var pendingFell: Moment? = null
    private var fellFromY = 0f

    /** Sprite box left. */
    val x: Float get() = feetX - half

    /** Sprite box top. */
    val y: Float get() = feetY - footY

    /** Starts facing left, toward the screen centre; follows her movement afterwards. */
    var facingLeft: Boolean = true
        private set

    private val airborne: Boolean get() = mode == Mode.JUMP || mode == Mode.FALL

    /** Where a jump comes down: its platform's top, or the floor. */
    private val jumpToY: Float get() = jumpTarget?.top?.toFloat() ?: floorY

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
                Mode.TEASE -> when (tease?.phase) {
                    Phase.POKE -> "poke"
                    Phase.SULK -> "sulk"
                    else -> "tease"
                }
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
                mode == Mode.TEASE -> "tease"
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
                mode == Mode.TEASE -> when (tease?.phase) {
                    Phase.POKE -> "poke"
                    Phase.SULK -> "sulk"
                    else -> null
                }
                mode == Mode.ONE_SHOT && oneShot != "peek" -> oneShot
                else -> null
            }
            val d = dest
            val onTarget = mode == Mode.TEASE && !dragging
            val (lookX, lookY) = when {
                mode == Mode.JUMP -> jumpToX to jumpToY
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
                where = when {
                    onTarget -> "target"
                    platform != null -> "platform"
                    else -> "floor"
                },
                target = if (onTarget) tease?.tag else null,
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
        if (airborne) {
            goal?.let { it.elapsedMs += dtMs }
            if (mode == Mode.JUMP) stepJump(dtMs) else stepFall(dtMs)
            return
        }
        if (mode == Mode.TEASE) { // runs whatever the pose: the cap is hard
            tease?.let { it.onTargetMs += dtMs }
            remainingMs -= dtMs
            if (remainingMs <= 0) hopOff()
            return
        }
        if (!connected || pose != null) return
        if (mode != Mode.ONE_SHOT && deferred.isNotEmpty()) return carryOn() // a move that waited for a gesture
        val g = goal
        if (g != null) {
            g.elapsedMs += dtMs
            if (g.elapsedMs >= if (g.tease) TEASE_REACH_MS else MOVE_MAX_MS) return giveUp()
            if (mode == Mode.IDLE) return plan()
        }
        if (mode == Mode.WALK && walk(dtMs)) return
        remainingMs -= dtMs
        if (remainingMs > 0) return
        when {
            mode == Mode.IDLE -> decide()
            mode == Mode.ONE_SHOT && deferred.isNotEmpty() -> carryOn()
            else -> resume()
        }
    }

    /**
     * Plays [name]. A one-shot asked for in the air plays on landing (the latest one wins); one
     * during a move pauses it (the move carries on afterwards). Ignored while she teases.
     */
    fun play(name: String) = playFor(name, ONE_SHOT_MS.toLong())

    private fun playFor(name: String, ms: Long) {
        if (!connected || dragging) return
        if (airborne) {
            if (name in ONE_SHOTS) defer(DeferredPlay(name, ms))
            return
        }
        if (mode == Mode.TEASE) return
        when {
            name in ONE_SHOTS -> startOneShot(name, ms)
            name == "walk" -> startWalk()
            name == "sit" -> startSit()
            name == "nap" -> startNap(rangeMs(SIT_MIN_MS, SIT_MAX_MS))
            name == "idle" -> goIdle()
            // unknown names are ignored
        }
    }

    /**
     * A `move` from the brain (spec §4.6). Ignored while dragged or disconnected and for unknown
     * intents; replaces the move she is carrying out. In the air it runs on landing, during a
     * one-shot gesture once the gesture ends (the latest one wins); on a tease target she hops off first. `sit_on`/`poke` with no such target in the
     * layout (or while restricted) play `happy` instead.
     */
    fun command(intent: String, tag: String?) {
        if (dragging || !connected || intent !in MOVE_INTENTS) return
        if (airborne || mode == Mode.ONE_SHOT) return defer(DeferredCmd(intent, tag))
        if (mode == Mode.TEASE) {
            hopOff()
            return defer(DeferredCmd(intent, tag))
        }
        endTease()
        goal = null
        if (airborne) return defer(DeferredCmd(intent, tag)) // she stood on the tease's own edge
        when (intent) {
            "stay" -> { goIdle(); remainingMs = STAY_MS.toLong() }
            "wander" -> decide()
            "come_closer" -> comeCloser()
            "go_left" -> goSide(-1)
            "go_right" -> goSide(1)
            "go_up" -> reachable().filter { it.top < feetY }.minByOrNull { it.top }?.let { jumpTo(it) }
            "go_down" -> startGoal(Goal(null, null))
            "sit" -> startSit()
            "nap" -> startNap(NAP_MS.toLong())
            "peek" -> peekAtScreenSide()
            else -> startTease(intent, tag)
        }
    }

    /**
     * A tap on her. True iff a tease was running: she sulks 1.5 s (then hops off the target) and a
     * `shooed` moment is recorded. Otherwise false; a nap ends.
     */
    fun tapped(): Boolean {
        if (dragging) return false
        val tz = tease
        if (tz == null) {
            if (mode == Mode.NAP) goIdle()
            return false
        }
        if (tz.phase == Phase.SULK) return true
        moments.add(Moment("shooed", tz.tag))
        if (mode == Mode.TEASE) {
            tz.phase = Phase.SULK
            // the 8 s cap holds: the sulk only uses what is left of it
            remainingMs = min(SHOO_SULK_MS.toLong(), TEASE_SIT_MAX_MS - tz.onTargetMs).coerceAtLeast(0)
        } else {
            endTease()
            playFor("sulk", SHOO_SULK_MS.toLong())
        }
        return true
    }

    /** The moments since the last call (oldest first). */
    fun drainMoments(): List<Moment> {
        val out = moments.toList()
        moments.clear()
        return out
    }

    /**
     * Holds [name] (e.g. "talk" while her voice plays) until cleared with null; she stands still
     * meanwhile (but still falls if the ground goes). Napping, being dragged and being in the air
     * still take precedence.
     */
    fun setPose(name: String?) {
        if (name == pose) return
        pose = name
        if (name == null && !airborne && mode != Mode.TEASE) resume()
    }

    /** Disconnected, she naps where she stands (off a tease target first); jumps and falls still finish. */
    fun setConnected(on: Boolean) {
        if (on == connected) return
        connected = on
        if (!on) {
            if (mode == Mode.TEASE) hopOff() else endTease()
            goal = null
            deferred.clear()
        }
        if (on && !airborne) goIdle()
    }

    fun dragStart() {
        dragging = true
        tease = null
        goal = null
        deferred.clear()
        pendingFell = null
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
        if (!airborne && platform == null && mode != Mode.TEASE) {
            // a rising floor carries her; one that drops away more than the follow tolerance, she falls to
            if (floorY - old <= FOLLOW_TOLERANCE_PX) this.feetY = floorY else startFall(0f)
        }
        revalidate(byLayout = false)
    }

    /**
     * Replaces the platforms; she follows hers if it moved ≤ 8 px, else (or if gone) she falls.
     * A [quiet] layout (the app's last one cut at Maya's own window) never makes a `fell` moment.
     */
    fun setLayout(layout: Layout, quiet: Boolean = false) {
        platforms = layout.platforms
        targets = layout.targets
        revalidate(byLayout = !quiet)
    }

    /** Restricted (keyboard, call, do-not-watch app): only the floor exists, she never jumps. */
    fun setRestricted(on: Boolean) {
        if (on == restricted) return
        restricted = on
        revalidate(byLayout = false)
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
        leap(p, landing(p, null))
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

    /** Carries on with the current move, if any; else idles. */
    private fun resume() = if (goal != null) plan() else goIdle()

    private fun startGoal(g: Goal) {
        goal = g
        plan()
    }

    /** The next step toward [goal]: walk along her surface, jump to it, go down to the floor, or give up. */
    private fun plan() {
        val g = goal ?: return goIdle()
        val s = g.surface
        if (s != null && s !in usable()) return giveUp()
        if (if (s == null) platform == null else platform == s) {
            val x = g.x?.let { val (lo, hi) = walkRange(s); it.coerceIn(lo, hi) }
            if (x == null || x == feetX) return arriveGoal()
            return walkTo(x, if (x < feetX) -1 else 1, Then.GOAL)
        }
        if (s != null && canReach(s)) return leap(s, landing(s, g.x))
        if (platform != null) return goDown()
        // on the floor and the platform is out of reach: get below it, else give up
        val (lo, hi) = walkRange(null)
        val under = (g.x ?: feetX).coerceIn(lo, hi)
        if (under != feetX) walkTo(under, if (under < feetX) -1 else 1, Then.GOAL) else giveUp()
    }

    private fun arriveGoal() {
        val g = goal
        goal = null
        when (g?.arrive) {
            Arrive.TEASE -> enterTease()
            Arrive.PEEK -> {
                facingLeft = feetX <= screenW / 2f // over the screen side
                startOneShot("peek")
            }
            else -> goIdle()
        }
    }

    /** `peek`: to the nearer screen side (along her platform if it reaches it, else on the floor), then peek. */
    private fun peekAtScreenSide() {
        val (lo, hi) = walkRange(null)
        val side = if (feetX <= screenW / 2f) lo else hi
        val (pLo, pHi) = walkRange(platform)
        val surface = if (platform != null && (side == pLo || side == pHi)) platform else null
        startGoal(Goal(surface, side, Arrive.PEEK))
    }

    /** The move cannot be finished; a tease plays `happy` instead. */
    private fun giveUp() {
        val teasing = tease != null
        endTease()
        goal = null
        if (!airborne) goIdle()
        if (teasing) play("happy")
    }

    /** Off her platform toward the floor: step off the nearer free edge, else hop down. */
    private fun goDown() {
        if (startDrop()) return
        val (lo, hi) = walkRange(null)
        leap(null, feetX.coerceIn(lo, hi))
    }

    /** The platform whose centre is nearest the screen centre, if she can get on it; else the floor centre. */
    private fun comeCloser() {
        val cx = screenW / 2f
        val cy = screenH / 2f
        val p = usable().minByOrNull { hypot((it.left + it.right) / 2f - cx, it.top - cy) }
        if (p != null && (p == platform || canReach(p))) {
            startGoal(Goal(p, (p.left + p.right) / 2f))
        } else {
            startGoal(Goal(null, cx))
        }
    }

    /** The farthest spot toward [dir] at about her height (her surface, or a reachable platform within ±0.1 screenH). */
    private fun goSide(dir: Int) {
        fun end(p: Platform?) = walkRange(p).let { if (dir < 0) it.first else it.second }
        var best = platform
        var bestX = end(best)
        for (p in reachable()) {
            if (abs(p.top - feetY) * 10f > screenH) continue
            val e = end(p)
            if (if (dir < 0) e < bestX else e > bestX) { best = p; bestX = e }
        }
        startGoal(Goal(best, bestX))
    }

    // ---- teases ----

    private fun startTease(kind: String, tag: String?) {
        if (tag == null || restricted) return play("happy")
        val t = targets
            .filter { it.tag == tag && it.bounds.top < floorY }
            .minByOrNull { hypot(it.bounds.left + it.bounds.width / 2f - feetX, it.bounds.top - feetY) }
            ?: return play("happy")
        val tz = Tease(kind, tag, t, surfaceFor(t))
        tease = tz
        startGoal(Goal(tz.surface, targetX(tz), Arrive.TEASE))
    }

    /** The platform the target's top edge belongs to, or one made from its top edge (too small to be a platform). */
    private fun surfaceFor(t: Target): Platform {
        val b = t.bounds
        return platforms
            .filter { it.top < floorY && it.tag == t.tag && it.left <= b.right && b.left <= it.right && abs(it.top - b.top) <= FOLLOW_TOLERANCE_PX }
            .minByOrNull { abs(it.top - b.top) }
            ?: Platform(max(b.left, 0), min(b.right, screenW), b.top, t.tag)
    }

    /** The target's centre x, on its surface. */
    private fun targetX(tz: Tease): Float {
        val (lo, hi) = walkRange(tz.surface)
        val b = tz.target.bounds
        return ((b.left + b.right) / 2f).coerceIn(lo, hi)
    }

    /** The same target in the current layout (same tag, overlapping, within 8 px in y), or null. */
    private fun followTarget(t: Target): Target? {
        if (restricted) return null
        val b = t.bounds
        return targets
            .filter {
                it.tag == t.tag && it.bounds.top < floorY && it.bounds.left <= b.right && b.left <= it.bounds.right &&
                    abs(it.bounds.top - b.top) <= FOLLOW_TOLERANCE_PX
            }
            .minWithOrNull(compareBy<Target>({ abs(it.bounds.top - b.top) }, { abs(it.bounds.left - b.left) + abs(it.bounds.right - b.right) }))
    }

    private fun enterTease() {
        val tz = tease ?: return goIdle()
        mode = Mode.TEASE
        dest = null
        then = Then.NONE
        platform = tz.surface
        if (tz.kind == "sit_on") {
            tz.phase = Phase.SIT
            remainingMs = rangeMs(TEASE_SIT_MIN_MS, TEASE_SIT_MAX_MS)
        } else {
            tz.phase = Phase.POKE
            remainingMs = POKE_MS.toLong()
        }
        placeOnTarget(tz)
    }

    /** Sitting: her sprite's centre on the target's centre. Poking (and sulking after it): feet on its top edge. */
    private fun placeOnTarget(tz: Tease) {
        val b = tz.target.bounds
        if (tz.phase == Phase.SIT || (tz.phase == Phase.SULK && tz.kind == "sit_on")) {
            val (lo, hi) = walkRange(null)
            feetX = ((b.left + b.right) / 2f).coerceIn(lo, hi)
            feetY = max((b.top + b.bottom) / 2f - spriteH / 2f + footY, footY.toFloat())
        } else {
            feetX = targetX(tz)
            feetY = tz.surface.top.toFloat()
        }
    }

    /** Off the target: to any other platform she can reach, else down to the floor. */
    private fun hopOff() {
        val from = tease?.surface ?: platform
        tease = null
        goal = null
        val others = reachable().filter { it != from }
        if (others.isNotEmpty()) {
            val p = others[rng.nextInt(others.size)]
            leap(p, landing(p, null))
        } else {
            val (lo, hi) = walkRange(null)
            val dir = if (feetX < screenW / 2f) 1 else -1
            leap(null, (feetX + dir * spriteW).coerceIn(lo, hi))
        }
    }

    /**
     * Drops a tease that is not on its target yet. Its made-up edge (for a small button) goes with
     * it: if she stood on it or was jumping to it, she falls.
     */
    private fun endTease() {
        if (tease == null) return
        tease = null
        goal = null
        val u = usable()
        if (mode == Mode.JUMP) {
            jumpTarget?.let { if (it !in u) startFall(max(vy, 0f)) }
        } else if (!airborne) {
            platform?.let { if (it !in u) startFall(0f) }
        }
    }

    private fun startNap(ms: Long) {
        mode = Mode.NAP
        dest = null
        then = Then.NONE
        remainingMs = ms
    }

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

    /**
     * The edge of [p] on side [dir] she can step off from, or null when the screen side is right
     * there or the step would only land on a neighbouring edge at about the same height (≤ 8 px down).
     */
    private fun dropEdge(p: Platform, dir: Int): Float? {
        val (screenLo, screenHi) = walkRange(null)
        val edge = if (dir < 0) p.left.toFloat() else p.right.toFloat()
        val step = edge + dir
        if (step < screenLo || step > screenHi) return null
        val neighbour = usable().any { it != p && step >= it.left && step <= it.right && it.top >= p.top && it.top - p.top <= FOLLOW_TOLERANCE_PX }
        return if (neighbour) null else edge
    }

    private fun arrive() {
        val after = then
        dest = null
        then = Then.NONE
        when (after) {
            Then.PEEK -> startOneShot("peek")
            Then.DROP -> { feetX += direction; startFall(0f) }
            else -> resume()
        }
    }

    private fun startSit() {
        mode = Mode.SIT
        dest = null
        then = Then.NONE
        remainingMs = rangeMs(SIT_MIN_MS, SIT_MAX_MS)
    }

    private fun startOneShot(name: String, ms: Long = ONE_SHOT_MS.toLong()) {
        mode = Mode.ONE_SHOT
        dest = null
        then = Then.NONE
        oneShot = name
        remainingMs = ms
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

    /** The feet x she lands at on [p] (null = floor): [x] (else where she is), kept off a platform's very ends. */
    private fun landing(p: Platform?, x: Float?): Float {
        val (lo, hi) = walkRange(p)
        val inset = if (p == null) 0f else min(half, (hi - lo) / 2f)
        return (x ?: feetX).coerceIn(lo + inset, hi - inset)
    }

    /** Jumps onto [p] (null = the floor) landing at feet x [land]; no reach check. */
    private fun leap(p: Platform?, land: Float) {
        if (land != feetX) facingLeft = land < feetX
        mode = Mode.JUMP
        dest = null
        then = Then.NONE
        jumpTarget = p
        jumpFromX = feetX
        jumpFromY = feetY
        jumpToX = land
        jumpElapsedMs = 0
        val dist = hypot(land - feetX, jumpToY - feetY)
        jumpDurMs = (JUMP_MIN_MS + JUMP_SPAN_MS * dist / screenDiag).toLong()
            .coerceIn(JUMP_MIN_MS.toLong(), JUMP_MAX_MS.toLong())
        platform = null
        vx = 0f
        vy = 0f
    }

    private fun stepJump(dtMs: Long) {
        jumpElapsedMs += dtMs
        if (jumpElapsedMs >= jumpDurMs) {
            feetX = jumpToX
            land(jumpTarget)
            return
        }
        // y(t) = y0 + b t + a t², t in 0..1, through both ends with its top at the apex
        val y0 = jumpFromY
        val y1 = jumpToY
        val dist = hypot(jumpToX - jumpFromX, y1 - y0)
        var apex = min(y0, y1) - JUMP_APEX * dist
        apex = max(apex, min(footY.toFloat(), min(y0, y1))) // the sprite's top never leaves the screen
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
            else -> { feetY = next; commitFell() }
        }
    }

    /**
     * Lands on [p] (null = floor), then starts a move or one-shot that arrived in the air, or
     * carries on with the current move.
     */
    private fun land(p: Platform?) {
        platform = p
        jumpTarget = null
        feetY = p?.top?.toFloat() ?: floorY
        vx = 0f
        vy = 0f
        // a "fall" that comes straight back down at the same height was no fall (same edge, new tag)
        if (pendingFell != null && feetY != fellFromY) commitFell() else pendingFell = null
        carryOn()
    }

    /** Back on her feet (landed, or a gesture ended): runs what was asked meanwhile, else carries on with the move. */
    private fun carryOn() {
        goIdle()
        val queued = deferred.toList()
        deferred.clear()
        if (queued.none { it is DeferredCmd } && goal != null) plan()
        for (d in queued) {
            when (d) {
                is DeferredCmd -> command(d.intent, d.tag)
                is DeferredPlay -> playFor(d.name, d.ms)
            }
        }
    }

    /** Keeps [d] for landing: it replaces an earlier one of its kind and goes last. */
    private fun defer(d: Deferred) {
        deferred.removeAll { it::class == d::class }
        deferred.add(d)
    }

    /** Her platform vanished or moved away: a `fell` moment, unless she lands again at once at the same height. */
    private fun markFell(tag: String?) {
        pendingFell = Moment("fell", tag)
        fellFromY = feetY
    }

    private fun commitFell() {
        pendingFell?.let { moments.add(it) }
        pendingFell = null
    }

    // ---- platforms ----

    /** Platforms she can stand on; plus, during a tease, the made-up edge of a button too small to be one. */
    private fun usable(): List<Platform> {
        if (restricted) return emptyList()
        val real = platforms.filter { it.top < floorY }
        val own = tease?.surface
        return if (own != null && own.top < floorY && own !in real) real + own else real
    }

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

    /**
     * After the platforms, floor or restriction changed: follow the tease target, the move's
     * platform and hers, or fall. A fall caused by a new layout ([byLayout]) is a `fell` moment.
     */
    private fun revalidate(byLayout: Boolean) {
        if (dragging) return
        revalidateTease(byLayout)
        if (mode == Mode.TEASE) return
        revalidateGoal()
        revalidateBody(byLayout)
    }

    private fun revalidateTease(byLayout: Boolean) {
        val tz = tease ?: return
        val t = followTarget(tz.target)
        if (t == null) {
            if (mode != Mode.TEASE) return giveUp() // gone before she got there
            tease = null
            goal = null
            if (byLayout) markFell(tz.tag)
            startFall(0f)
            return
        }
        tz.target = t
        tz.surface = surfaceFor(t)
        if (mode == Mode.TEASE) {
            platform = tz.surface
            placeOnTarget(tz)
        } else {
            goal?.let { it.surface = tz.surface; it.x = targetX(tz) }
        }
    }

    /** A move toward a platform follows it; if it is gone the move ends where she is. */
    private fun revalidateGoal() {
        val g = goal ?: return
        if (g.tease) return // follows the target instead
        val s = g.surface ?: return
        val m = follow(s)
        if (m == null) {
            goal = null
            return
        }
        g.x = g.x?.let { it + shiftX(s, m) }
        g.surface = m
    }

    private fun revalidateBody(byLayout: Boolean) {
        when (mode) {
            Mode.FALL -> {}
            Mode.JUMP -> {
                val t = jumpTarget ?: return // the floor
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
                    if (byLayout) markFell(null)
                    startFall(0f)
                } else if (m != p) {
                    val dx = shiftX(p, m)
                    val (lo, hi) = walkRange(m)
                    feetX = (feetX + dx).coerceIn(lo, hi)
                    feetY = m.top.toFloat()
                    platform = m
                    // an edge she is heading for is the new platform's edge; a spot on it moves with it
                    val d = dest ?: return
                    when (then) {
                        Then.DROP -> dest = dropEdge(m, direction) ?: return resume()
                        Then.PEEK -> dest = if (direction < 0) lo else hi
                        else -> dest = (d + dx).coerceIn(lo, hi)
                    }
                }
            }
        }
    }
}
