package com.maya.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorTest {
    @Test fun starts_idle_in_bottom_right_corner() {
        val b = Behavior(1000, 100, Random(1), 100f)
        assertEquals("idle", b.animation); assertEquals(900f, b.x)
    }

    @Test fun disconnected_naps_and_ignores_play() {
        val b = Behavior(1000, 100, Random(1), 100f); b.setConnected(false)
        assertEquals("nap", b.animation); b.play("wave"); assertEquals("nap", b.animation)
        b.setConnected(true); assertEquals("idle", b.animation)
    }

    @Test fun play_returns_to_idle_after_2500ms() {
        val b = Behavior(1000, 100, Random(1), 100f)
        b.play("wave"); b.tick(2400); assertEquals("wave", b.animation); b.tick(200); assertEquals("idle", b.animation)
    }

    @Test fun walking_stays_in_bounds_over_a_long_run() {
        val b = Behavior(500, 100, Random(7), 300f); var walked = false
        repeat(10_000) { b.tick(50); assertTrue(b.x in 0f..400f); if (b.animation == "walk") walked = true }
        assertTrue(walked)
    }

    @Test fun drag_shows_surprised_and_drops_clamped() {
        val b = Behavior(500, 100, Random(1), 100f)
        b.dragStart(); b.tick(1000); assertEquals("surprised", b.animation)
        b.dragEnd(9999f); assertEquals(400f, b.x); assertEquals("idle", b.animation)
        b.dragStart(); b.dragEnd(-50f); assertEquals(0f, b.x)
    }

    @Test fun walk_moves_by_speed_times_dt_and_sets_facing() {
        val b = Behavior(1000, 100, Random(1), 100f)
        b.dragEnd(500f); b.play("walk"); assertEquals("walk", b.animation)
        val x0 = b.x; val left = b.facingLeft
        b.tick(1000)
        assertEquals(if (left) x0 - 100f else x0 + 100f, b.x, 0.001f)
    }

    @Test fun walk_turns_around_at_the_edge() {
        val b = Behavior(500, 100, Random(1), 1000f) // starts at right edge
        b.play("walk")
        repeat(20) { b.tick(100); if (b.animation != "walk") b.play("walk"); assertTrue(b.x in 0f..400f) }
        // after bouncing around at 1000 px/s on a 400 px track both directions must have been used
        var sawLeft = false; var sawRight = false
        repeat(40) { b.tick(100); if (b.animation != "walk") b.play("walk"); if (b.facingLeft) sawLeft = true else sawRight = true }
        assertTrue(sawLeft && sawRight)
    }

    @Test fun sit_and_nap_via_play_switch_immediately_and_end_in_idle() {
        val b = Behavior(1000, 100, Random(3), 100f)
        b.play("sit"); assertEquals("sit", b.animation)
        b.tick(15_001); assertEquals("idle", b.animation)
        b.play("nap"); assertEquals("nap", b.animation)
        b.tick(15_001); assertEquals("idle", b.animation)
    }

    @Test fun idle_play_keeps_idle() {
        val b = Behavior(1000, 100, Random(3), 100f)
        b.play("wave"); b.play("idle"); assertEquals("idle", b.animation)
    }

    @Test fun decisions_never_start_before_three_seconds() {
        repeat(50) { seed ->
            val b = Behavior(1000, 100, Random(seed), 100f)
            b.tick(2999); assertEquals("idle", b.animation)
        }
    }

    @Test fun decisions_happen_and_cover_walk_sit_idle() {
        val seen = HashSet<String>()
        repeat(50) { seed ->
            val b = Behavior(1000, 100, Random(seed), 100f)
            repeat(400) { b.tick(100); seen += b.animation }
        }
        assertTrue(seen.containsAll(listOf("idle", "walk", "sit")))
        assertFalse("nap" in seen || "surprised" in seen || "wave" in seen)
    }

    @Test fun ticks_do_not_move_or_decide_while_dragged() {
        val b = Behavior(500, 100, Random(1), 1000f)
        b.dragEnd(200f); b.dragStart()
        repeat(200) { b.tick(100) }
        assertEquals(200f, b.x); assertEquals("surprised", b.animation)
    }

    @Test fun reconnect_restarts_idle_decision_timer() {
        val b = Behavior(1000, 100, Random(1), 100f)
        b.setConnected(false); b.tick(60_000); b.setConnected(true)
        b.tick(2999); assertEquals("idle", b.animation)
    }

    @Test fun play_is_ignored_while_dragged() {
        val b = Behavior(1000, 100, Random(1), 100f)
        b.dragStart(); b.play("wave"); assertEquals("surprised", b.animation)
    }

    @Test fun pose_holds_until_cleared_and_stops_walking() {
        val b = Behavior(1000, 100, Random(7), 300f)
        b.play("walk"); val x0 = b.x
        b.setPose("talk")
        assertEquals("talk", b.animation)
        b.tick(60_000); assertEquals("talk", b.animation); assertEquals(x0, b.x)
        b.play("happy"); assertEquals("talk", b.animation) // a pose outranks one-shots
        b.setPose(null); assertEquals("idle", b.animation)
    }

    @Test fun nap_and_drag_outrank_pose() {
        val b = Behavior(1000, 100, Random(1), 100f)
        b.setPose("listen")
        b.dragStart(); assertEquals("surprised", b.animation)
        b.dragEnd(10f); assertEquals("listen", b.animation)
        b.setConnected(false); assertEquals("nap", b.animation)
        b.setConnected(true); assertEquals("listen", b.animation)
    }
}

