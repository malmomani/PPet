package com.maya.core

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen 1000×2000, sprite 100×100, feet at y + 90, gravity 3000 px/s². */
private const val W = 1000
private const val H = 2000
private const val SPRITE = 100
private const val FOOT = 90
private const val GRAVITY = 3000f

private fun mk(seed: Int = 1, speed: Float = 100f, w: Int = W, h: Int = H) =
    Behavior(w, h, SPRITE, SPRITE, FOOT, Random(seed), speed, GRAVITY)

/** Drops her with her feet exactly at (feetX, feetY); she stands there at once if it is a surface. */
private fun Behavior.putFeet(feetX: Float, feetY: Float) = dragEnd(feetX - SPRITE / 2f, feetY - FOOT)

/** Ticks in 10 ms steps until she is on something (fails after [maxMs]). */
private fun Behavior.settle(maxMs: Int = 10_000) {
    var t = 0
    while (!state.grounded) {
        tick(10); t += 10
        assertTrue("never landed", t <= maxMs)
    }
}

class BehaviorTest {
    // ---- start / floor ----

    @Test fun starts_on_floor_right_side() {
        val b = mk()
        assertEquals("idle", b.animation)
        assertEquals(900f, b.x); assertEquals((H - FOOT).toFloat(), b.y)
        assertTrue(b.facingLeft)
        val s = b.state
        assertEquals(950f, s.feetX); assertEquals(H.toFloat(), s.feetY)
        assertEquals("floor", s.where); assertTrue(s.grounded); assertEquals("idle", s.doing)
    }

    // ---- walking ----

    @Test fun walk_stays_on_platform() {
        val b = mk(seed = 7, speed = 400f)
        val p = Platform(200, 600, 1000)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(400f, 1000f)
        assertEquals("platform", b.state.where)
        var sawLeft = false; var sawRight = false
        repeat(2_000) {
            if (b.animation != "walk") b.play("walk")
            b.tick(20)
            val s = b.state
            assertTrue("feet ${s.feetX}", s.feetX in 200f..600f)
            assertEquals(1000f, s.feetY); assertTrue(s.grounded)
            if (s.vx < 0) sawLeft = true; if (s.vx > 0) sawRight = true
        }
        assertTrue(sawLeft && sawRight)
    }

    @Test fun walking_on_the_floor_stays_on_screen_over_a_long_run() {
        val b = mk(seed = 7, speed = 300f, w = 500)
        var walked = false
        repeat(10_000) {
            b.tick(50); assertTrue(b.x in 0f..400f)
            if (b.animation == "walk") walked = true
        }
        assertTrue(walked)
    }

    @Test fun walk_moves_by_speed_times_dt_and_sets_facing() {
        val b = mk()
        b.putFeet(550f, H.toFloat()); b.play("walk"); assertEquals("walk", b.animation)
        val x0 = b.x; val left = b.facingLeft
        b.tick(1000)
        assertEquals(if (left) x0 - 100f else x0 + 100f, b.x, 0.001f)
        assertEquals(if (left) -100f else 100f, b.state.vx, 0.001f)
    }

    // ---- jumping ----

    @Test fun jump_lands_on_target_platform() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p))
        assertEquals("jump", b.animation); assertEquals("jump", b.state.doing); assertFalse(b.state.grounded)
        assertFalse(b.facingLeft) // she faces where she is going
        // landing point: her x clamped into the platform, 50 px in from its edge
        val dist = hypot(350f - 150f, 1400f - 2000f)
        val dur = (400 + 500 * dist / hypot(W.toFloat(), H.toFloat())).toInt().coerceIn(400, 900)
        var minFeet = Float.MAX_VALUE
        var t = 0
        while (t < dur - 10) {
            b.tick(1); t += 1; minFeet = minOf(minFeet, b.state.feetY)
            assertTrue("sprite above the screen", b.y >= 0f)
        }
        assertEquals("jump", b.state.doing)
        assertEquals(1400f - 0.25f * dist, minFeet, 2f) // apex 0.25 × distance above the higher end
        b.tick(10)
        val s = b.state
        assertEquals(1400f, s.feetY); assertEquals(350f, s.feetX, 0.001f)
        assertTrue(s.grounded); assertEquals("platform", s.where); assertEquals("idle", b.animation)
    }

    @Test fun jump_duration_scales_with_distance() {
        val b = mk()
        val near = Platform(880, 1000, 1990)
        b.setLayout(Layout(listOf(near), emptyList()))
        assertTrue(b.jumpTo(near)) // a hop of ~36 px: 400 + 500 × 36 / 2236 ≈ 408 ms
        b.tick(399); assertEquals("jump", b.state.doing)
        b.tick(20); assertTrue(b.state.grounded); assertEquals(1990f, b.state.feetY)

        val far = mk()
        val top = Platform(0, 100, 1100)
        far.setLayout(Layout(listOf(top), emptyList()))
        far.putFeet(650f, H.toFloat()) // lands at x 75: Δ (575, 900) → 400 + 500 × 1068 / 2236 ≈ 639 ms
        assertTrue(far.jumpTo(top))
        far.tick(630); assertEquals("jump", far.state.doing)
        far.tick(20); assertTrue(far.state.grounded)
    }

    @Test fun jump_apex_keeps_the_sprite_on_screen() {
        val b = mk()
        val top = Platform(600, 900, 150)
        b.setLayout(Layout(listOf(Platform(0, 300, 1000), top), emptyList()))
        b.putFeet(150f, 1000f) // rise 850: an unclamped apex would put the sprite ~190 px above the screen
        assertTrue(b.jumpTo(top))
        var minY = Float.MAX_VALUE
        while (!b.state.grounded) {
            b.tick(5)
            assertTrue("sprite above the screen: ${b.y}", b.y >= 0f)
            minY = minOf(minY, b.y)
        }
        assertEquals(0f, minY, 2f) // the arc tops out with the sprite at the screen top
        assertEquals(150f, b.state.feetY)
    }

    @Test fun drag_mid_jump() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p)); b.tick(100)
        b.dragStart()
        assertEquals("dragged", b.state.doing); assertEquals("held", b.animation)
        val x0 = b.x; val y0 = b.y
        b.tick(500); assertEquals(x0, b.x); assertEquals(y0, b.y)
        b.dragEnd(x0, y0)
        assertEquals("fall", b.state.doing)
        b.settle(); assertEquals("floor", b.state.where); assertEquals("idle", b.animation)
    }

    @Test fun restricted_mid_jump_falls() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p)); b.tick(100)
        b.setRestricted(true)
        assertEquals("fall", b.state.doing)
        b.settle(); assertEquals("floor", b.state.where); assertEquals(H.toFloat(), b.state.feetY)
    }

    @Test fun layout_during_a_fall_catches_her() {
        val b = mk()
        b.putFeet(500f, 100f)
        b.tick(100); assertEquals("fall", b.state.doing)
        b.setLayout(Layout(listOf(Platform(0, 1000, 1000)), emptyList()))
        b.settle()
        assertEquals(1000f, b.state.feetY); assertEquals("platform", b.state.where)
    }

    @Test fun disconnected_mid_jump_finishes_then_naps() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p)); b.tick(100)
        b.setConnected(false)
        assertEquals("jump", b.state.doing); assertEquals("jump", b.animation)
        b.tick(1000)
        assertTrue(b.state.grounded); assertEquals(1400f, b.state.feetY)
        assertEquals("nap", b.animation); assertEquals("nap", b.state.doing)
    }

    @Test fun jump_reach_limits() {
        val b = mk()
        b.putFeet(100f, H.toFloat()) // feet x 100, y 2000
        val edgeX = Platform(700, 800, 1800) // Δx exactly 0.6 × 1000
        val tooFar = Platform(701, 800, 1700) // Δx 601
        val edgeUp = Platform(0, 300, 1100) // rise exactly 0.45 × 2000
        val tooHigh = Platform(0, 300, 1099) // rise 901
        b.setLayout(Layout(listOf(edgeX, tooFar, edgeUp, tooHigh), emptyList()))
        assertEquals(setOf(edgeX, edgeUp), b.reachable().toSet())
        assertFalse(b.jumpTo(tooHigh)); assertTrue(b.state.grounded)
        // dropping down is always allowed, however far
        val high = Platform(0, 200, 300)
        val low = Platform(0, 200, 1900)
        b.setLayout(Layout(listOf(high, low), emptyList()))
        b.putFeet(100f, 300f)
        assertEquals(listOf(low), b.reachable())
    }

    @Test fun jump_decision_with_nothing_reachable_walks() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(0, 300, 100)), emptyList())) // far too high
        b.act("jump")
        assertEquals("walk", b.animation)
    }

    // ---- falling ----

    @Test fun fall_to_platform_below_or_floor() {
        val a = Platform(100, 500, 800)
        val c = Platform(0, 1000, 1500)
        val layout = Layout(listOf(c, a), emptyList())

        val b1 = mk(); b1.setLayout(layout)
        b1.putFeet(300f, 200f); assertEquals("fall", b1.animation); assertFalse(b1.state.grounded)
        b1.settle(); assertEquals(800f, b1.state.feetY); assertEquals("platform", b1.state.where)

        val b2 = mk(); b2.setLayout(layout)
        b2.putFeet(700f, 200f); b2.settle(); assertEquals(1500f, b2.state.feetY)

        val b3 = mk(); b3.setLayout(Layout(listOf(a), emptyList()))
        b3.putFeet(700f, 200f); b3.settle()
        assertEquals(H.toFloat(), b3.state.feetY); assertEquals("floor", b3.state.where)

        // a platform above her feet is not caught on the way down
        val b4 = mk(); b4.setLayout(layout)
        b4.putFeet(300f, 900f); b4.settle(); assertEquals(1500f, b4.state.feetY)
    }

    @Test fun fall_follows_gravity() {
        val b = mk()
        b.putFeet(500f, 100f)
        b.tick(100) // vy = 300 px/s, moved 30 px (semi-implicit Euler)
        assertEquals(300f, b.state.vy, 0.01f)
        assertEquals(130f, b.state.feetY, 0.01f)
        assertEquals("fall", b.state.doing)
    }

    @Test fun platform_moved_small_follows_large_falls() {
        val p = Platform(200, 600, 1000)
        val b = mk()
        b.setLayout(Layout(listOf(p), emptyList())); b.putFeet(400f, 1000f)

        b.setLayout(Layout(listOf(Platform(200, 600, 1008)), emptyList())) // 8 px: follows
        assertTrue(b.state.grounded); assertEquals(1008f, b.state.feetY); assertEquals("platform", b.state.where)

        b.setLayout(Layout(listOf(Platform(150, 550, 1000)), emptyList())) // slid sideways: moves with it
        assertTrue(b.state.grounded); assertEquals(1000f, b.state.feetY); assertEquals(350f, b.state.feetX)

        b.setLayout(Layout(listOf(Platform(150, 550, 991)), emptyList())) // 9 px up: she falls off
        assertEquals("fall", b.state.doing)
        b.settle(); assertEquals(H.toFloat(), b.state.feetY)

        val b2 = mk()
        b2.setLayout(Layout(listOf(p), emptyList())); b2.putFeet(400f, 1000f)
        b2.setLayout(Layout.EMPTY) // gone
        assertEquals("fall", b2.state.doing)
        b2.settle(); assertEquals("floor", b2.state.where)
    }

    @Test fun a_different_tag_is_a_different_platform() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(200, 600, 1000, "like")), emptyList())); b.putFeet(400f, 1000f)
        b.setLayout(Layout(listOf(Platform(200, 600, 1000, "send")), emptyList()))
        assertEquals("fall", b.state.doing)
        b.tick(10) // same place, so she drops straight back onto it
        assertTrue(b.state.grounded); assertEquals("platform", b.state.where)
    }

    @Test fun platform_removed_mid_jump_falls() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p))
        b.tick(200)
        b.setLayout(Layout.EMPTY)
        assertEquals("fall", b.state.doing); assertEquals("fall", b.animation)
        b.settle(); assertEquals(H.toFloat(), b.state.feetY); assertEquals("floor", b.state.where)
    }

    @Test fun target_moved_small_mid_jump_lands_on_moved_platform() {
        val b = mk()
        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        assertTrue(b.jumpTo(p))
        b.tick(100)
        b.setLayout(Layout(listOf(Platform(300, 700, 1395)), emptyList()))
        b.tick(1000)
        assertTrue(b.state.grounded); assertEquals(1395f, b.state.feetY)
    }

    // ---- floor / restricted ----

    @Test fun keyboard_floor_and_restricted() {
        val b = mk()
        b.setFloor(1200) // keyboard opened: she rides the floor up
        assertTrue(b.state.grounded); assertEquals(1200f, b.state.feetY); assertEquals(1110f, b.y)
        b.setFloor(H) // keyboard closed: the floor drops away, she falls to it
        assertEquals("fall", b.state.doing)
        b.settle(); assertEquals(H.toFloat(), b.state.feetY)

        val p = Platform(200, 600, 1200)
        b.setLayout(Layout(listOf(p), emptyList())); b.putFeet(400f, 1200f)
        assertEquals("platform", b.state.where)
        b.setRestricted(true)
        assertEquals("fall", b.state.doing)
        b.settle(); assertEquals("floor", b.state.where)
        assertTrue(b.reachable().isEmpty())
        assertFalse(b.jumpTo(p))
        b.act("jump"); assertEquals("walk", b.animation)
        b.setRestricted(false)
        assertEquals(listOf(p), b.reachable())
    }

    @Test fun platforms_below_the_floor_are_ignored() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(0, 1000, 1500)), emptyList()))
        b.putFeet(500f, 1500f); assertEquals("platform", b.state.where)
        b.setFloor(1300) // keyboard covers her platform
        b.settle()
        assertEquals(1300f, b.state.feetY); assertEquals("floor", b.state.where)
    }

    // ---- drag ----

    @Test fun drag_end_falls() {
        val b = mk()
        b.dragStart()
        assertEquals("held", b.animation); assertEquals("dragged", b.state.doing); assertFalse(b.state.grounded)
        b.tick(1000); assertEquals("dragged", b.state.doing)
        b.dragEnd(9999f, 500f)
        assertEquals(900f, b.x); assertEquals("fall", b.animation)
        b.settle(); assertEquals(H.toFloat(), b.state.feetY); assertEquals("idle", b.animation)
        b.dragStart(); b.dragEnd(-50f, -500f)
        assertEquals(0f, b.x); assertEquals(0f, b.y)
        b.dragStart(); b.dragEnd(10f, 99_999f) // below the floor: put back on it
        assertTrue(b.state.grounded); assertEquals(H.toFloat(), b.state.feetY)
    }

    @Test fun ticks_do_not_move_or_decide_while_dragged() {
        val b = mk(speed = 1000f)
        b.putFeet(250f, H.toFloat()); b.dragStart()
        repeat(200) { b.tick(100) }
        assertEquals(200f, b.x); assertEquals("held", b.animation)
    }

    @Test fun play_is_ignored_while_dragged() {
        val b = mk()
        b.dragStart(); b.play("wave"); assertEquals("held", b.animation)
    }

    // ---- decisions / temper ----

    @Test fun temper_changes_jump_rate() {
        fun rate(mood: String, tier: String): Double {
            val b = mk(seed = 42)
            b.setTemper(mood, tier)
            return (1..1000).count { b.pickAction() == "jump" } / 1000.0
        }
        assertEquals(0.10, rate("curious", "stranger"), 0.05)
        assertEquals(0.10, rate("sleepy", "close"), 0.05)
        assertEquals(0.10, rate("sulky", "friend"), 0.05)
        assertEquals(0.10, rate("excited", "stranger"), 0.05) // strangers stay mostly on the floor
        assertEquals(0.10, rate("happy", "stranger"), 0.05)
        assertEquals(0.25, rate("curious", "friend"), 0.05)
        assertEquals(0.40, rate("bored", "close"), 0.05)
        assertEquals(0.35, rate("happy", "friend"), 0.05)
        assertEquals(0.50, rate("excited", "close"), 0.05)
        assertEquals(0.10, mk(seed = 42).let { b -> (1..1000).count { b.pickAction() == "jump" } / 1000.0 }, 0.05)
    }

    @Test fun decisions_cover_every_choice() {
        val b = mk(seed = 3)
        b.setTemper("happy", "friend")
        val seen = (1..1000).map { b.pickAction() }.toSet()
        assertEquals(setOf("walk", "jump", "sit", "peek", "drop", "fidget", "idle"), seen)
    }

    @Test fun fidgets_fit_her_mood() {
        fun fidgets(mood: String, typing: Boolean = false): Set<String> {
            val b = mk(seed = 5)
            b.setTemper(mood, "friend")
            b.setUserTyping(typing)
            return (1..200).map { b.pickFidget() }.toSet()
        }
        assertEquals(setOf("nap", "look"), fidgets("sleepy"))
        assertEquals(setOf("sulk", "look"), fidgets("sulky"))
        assertEquals(setOf("happy", "wave", "look"), fidgets("excited"))
        assertEquals(setOf("look", "happy", "wave"), fidgets("curious"))
        assertTrue("type" in fidgets("curious", typing = true))
        assertFalse("type" in fidgets("curious"))
    }

    @Test fun a_fidget_plays_and_ends_in_idle() {
        val b = mk(seed = 5)
        b.setTemper("sulky", "friend")
        b.act("fidget")
        assertTrue(b.animation in setOf("sulk", "look"))
        b.tick(ONE_SHOT_MS.toLong()); assertEquals("idle", b.animation)
    }

    @Test fun react_plays_facing_the_middle_only_while_she_is_free() {
        val b = mk()
        assertTrue(b.react("look")); assertEquals("look", b.animation); assertTrue(b.facingLeft) // she stands on the right
        b.tick(2000); assertEquals("idle", b.animation)
        assertFalse(b.react("fly")) // not a gesture
        b.command("go_left", null) // a move under way: no reaction
        assertFalse(b.react("surprised")); assertEquals("walk", b.animation)
        b.command("stay", null)
        b.setPose("talk"); assertFalse(b.react("wave")); b.setPose(null)
        b.setConnected(false); assertFalse(b.react("wave")); b.setConnected(true)
        assertTrue(b.react("wave"))
        assertFalse(b.react("look")) // a gesture already plays
    }

    @Test fun decisions_never_start_before_three_seconds() {
        repeat(50) { seed ->
            val b = mk(seed = seed)
            b.tick(2999); assertEquals("idle", b.animation)
        }
    }

    @Test fun decisions_happen_and_roam_between_platforms() {
        val seen = HashSet<String>()
        val wheres = HashSet<String>()
        val layout = Layout(listOf(Platform(100, 500, 1500), Platform(500, 900, 1300), Platform(0, 400, 1100)), emptyList())
        repeat(30) { seed ->
            val b = mk(seed = seed, speed = 200f)
            b.setLayout(layout)
            b.setTemper("excited", "close")
            repeat(1_500) {
                b.tick(50)
                seen += b.state.doing; wheres += b.state.where
                assertTrue(b.x in 0f..900f)
                assertTrue(b.state.feetY <= H.toFloat())
            }
        }
        assertTrue(seen.toString(), seen.containsAll(listOf("idle", "walk", "sit", "perch", "jump", "fall", "peek")))
        assertTrue(wheres.containsAll(listOf("floor", "platform")))
        assertFalse("nap" in seen || "dragged" in seen || "target" in wheres)
    }

    @Test fun drop_steps_off_the_nearest_edge() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(500f, 1000f)
        b.act("drop")
        assertEquals("walk", b.animation); assertFalse(b.facingLeft)
        b.tick(1100) // walks 100 px to the right edge and steps off
        assertEquals("fall", b.state.doing)
        b.settle()
        assertEquals(H.toFloat(), b.state.feetY); assertEquals("floor", b.state.where)
        assertTrue(b.state.feetX > 600f)
    }

    @Test fun drop_edge_follows_a_shrinking_platform() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(500f, 1000f)
        b.act("drop")
        b.setLayout(Layout(listOf(Platform(200, 560, 1004)), emptyList())) // re-laid out a little narrower
        b.tick(700) // walks 60 px to the new edge and steps off
        assertEquals("fall", b.state.doing); assertTrue(b.state.feetX > 560f)
        b.settle(); assertEquals("floor", b.state.where)

        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(500f, 1000f)
        b.act("drop")
        b.setLayout(Layout(listOf(Platform(200, 640, 1000)), emptyList())) // wider: the edge moved out
        b.tick(1500) // walks 140 px to the new edge and steps off
        assertEquals("fall", b.state.doing); assertTrue(b.state.feetX > 640f)
        b.settle(); assertEquals("floor", b.state.where)

        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(500f, 1000f)
        b.act("drop")
        b.setLayout(Layout(listOf(Platform(200, 1000, 1000)), emptyList())) // now runs off the screen side
        assertEquals("idle", b.state.doing) // no edge to step off: she gives up
        b.tick(2000); assertEquals("platform", b.state.where)
    }

    @Test fun drop_on_the_floor_walks() {
        val b = mk()
        b.act("drop"); assertEquals("walk", b.animation)
    }

    @Test fun peek_walks_to_the_edge_then_peeks_over_it() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(300f, 1000f)
        b.act("peek")
        assertEquals("walk", b.animation); assertTrue(b.facingLeft)
        assertEquals(200f, b.state.lookX); assertEquals(1000f, b.state.lookY)
        b.tick(1000)
        assertEquals("peek", b.animation); assertEquals("peek", b.state.doing)
        assertEquals(200f, b.state.feetX); assertTrue(b.facingLeft); assertTrue(b.state.grounded)
        b.tick(ONE_SHOT_MS.toLong()); assertEquals("idle", b.animation)
    }

    @Test fun sitting_on_a_platform_is_a_perch() {
        val b = mk()
        b.play("sit"); assertEquals("sit", b.animation); assertEquals("sit", b.state.doing)
        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(400f, 1000f)
        b.play("sit"); assertEquals("perch", b.animation); assertEquals("perch", b.state.doing)
    }

    // ---- resize ----

    @Test fun resize_keeps_on_screen() {
        val old = mk()
        old.putFeet(950f, 1500f); old.settle()
        // rotated: a narrower, shorter screen; the app re-creates her and drops her where she was
        val b = mk(w = 600, h = 900)
        b.dragEnd(old.x, old.y)
        assertEquals(500f, b.x)
        assertTrue(b.y <= (900 - FOOT).toFloat())
        b.settle()
        assertEquals(900f, b.state.feetY); assertTrue(b.x in 0f..500f)
    }

    // ---- body state ----

    @Test fun body_state_fields() {
        val b = mk()
        var s = b.state
        assertEquals(0f, s.vx); assertEquals(0f, s.vy)
        assertTrue(s.facingLeft); assertNull(s.target); assertNull(s.gesture)
        assertEquals(950f - SPRITE, s.lookX) // straight ahead: one sprite width in front of her feet
        assertEquals(H.toFloat(), s.lookY)

        b.play("wave"); s = b.state
        assertEquals("wave", s.gesture); assertEquals("idle", s.doing); assertEquals("wave", b.animation)

        b.setPose("talk"); s = b.state
        assertEquals("talk", s.gesture)
        assertEquals(W / 2f, s.lookX); assertEquals(H / 2f, s.lookY) // talking: she looks at the screen centre
        b.setPose(null)

        val p = Platform(300, 700, 1400)
        b.setLayout(Layout(listOf(p), emptyList()))
        b.putFeet(150f, H.toFloat())
        b.jumpTo(p); b.tick(100); s = b.state
        assertEquals("jump", s.doing); assertFalse(s.grounded)
        assertEquals(350f, s.lookX); assertEquals(1400f, s.lookY) // looking where she will land
        assertTrue(s.vx > 0); assertTrue(s.vy < 0) // rising toward the right
        b.tick(1000); s = b.state
        assertEquals("platform", s.where); assertTrue(s.grounded); assertEquals(0f, s.vy)

        b.setConnected(false); s = b.state
        assertEquals("nap", s.doing)
    }

    // ---- kept: one-shots, poses, connection ----

    @Test fun disconnected_naps_and_ignores_play() {
        val b = mk(); b.setConnected(false)
        assertEquals("nap", b.animation); b.play("wave"); assertEquals("nap", b.animation)
        b.setConnected(true); assertEquals("idle", b.animation)
    }

    @Test fun disconnected_naps_in_place_but_still_falls() {
        val b = mk(speed = 500f)
        val p = Platform(200, 600, 1000)
        b.setLayout(Layout(listOf(p), emptyList())); b.putFeet(400f, 1000f)
        b.setConnected(false)
        repeat(100) { b.tick(100) }
        assertEquals(400f, b.state.feetX); assertEquals("nap", b.animation); assertEquals("nap", b.state.doing)
        b.setLayout(Layout.EMPTY)
        assertEquals("fall", b.state.doing); assertEquals("fall", b.animation)
        b.settle()
        assertEquals("floor", b.state.where); assertEquals("nap", b.animation)
    }

    @Test fun play_returns_to_idle_after_2500ms() {
        val b = mk()
        b.play("sulk"); b.tick(2400); assertEquals("sulk", b.animation); b.tick(200); assertEquals("idle", b.animation)
    }

    @Test fun gestures_last_whole_plays() {
        val b = mk()
        b.play("wave"); b.tick(1400); assertEquals("wave", b.animation); b.tick(200); assertEquals("idle", b.animation)
        b.play("happy"); b.tick(900); assertEquals("happy", b.animation); b.tick(200); assertEquals("idle", b.animation)
    }

    @Test fun play_is_ignored_in_the_air() {
        val b = mk()
        b.putFeet(500f, 100f)
        b.play("wave"); assertEquals("fall", b.animation)
    }

    @Test fun sit_and_nap_via_play_switch_immediately_and_end_in_idle() {
        val b = mk(seed = 3)
        b.play("sit"); assertEquals("sit", b.animation)
        b.tick(15_001); assertEquals("idle", b.animation)
        b.play("nap"); assertEquals("nap", b.animation)
        b.tick(15_001); assertEquals("idle", b.animation)
    }

    @Test fun idle_play_keeps_idle() {
        val b = mk(seed = 3)
        b.play("wave"); b.play("idle"); assertEquals("idle", b.animation)
    }

    @Test fun reconnect_restarts_idle_decision_timer() {
        val b = mk()
        b.setConnected(false); b.tick(60_000); b.setConnected(true)
        b.tick(2999); assertEquals("idle", b.animation)
    }

    @Test fun pose_holds_until_cleared_and_stops_walking() {
        val b = mk(seed = 7, speed = 300f)
        b.play("walk"); val x0 = b.x
        b.setPose("talk")
        assertEquals("talk", b.animation)
        b.tick(60_000); assertEquals("talk", b.animation); assertEquals(x0, b.x)
        b.play("happy"); assertEquals("talk", b.animation) // a pose outranks one-shots
        b.setPose(null); assertEquals("idle", b.animation)
    }

    @Test fun nap_and_drag_outrank_pose() {
        val b = mk()
        b.setPose("listen")
        b.dragStart(); assertEquals("held", b.animation)
        b.dragEnd(10f, (H - FOOT).toFloat()); assertEquals("listen", b.animation)
        b.setConnected(false); assertEquals("nap", b.animation)
        b.setConnected(true); assertEquals("listen", b.animation)
    }

    @Test fun pose_still_falls_when_the_platform_goes() {
        val b = mk()
        b.setLayout(Layout(listOf(Platform(200, 600, 1000)), emptyList())); b.putFeet(400f, 1000f)
        b.setPose("talk")
        b.setLayout(Layout.EMPTY)
        assertEquals("fall", b.animation)
        b.settle(); assertEquals("talk", b.animation); assertEquals(H.toFloat(), b.state.feetY)
    }
}
