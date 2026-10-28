package com.maya.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen 1000×2000, sprite 100×100, feet at y + 90, gravity 3000 px/s² (as in BehaviorTest). */
private const val W = 1000
private const val H = 2000
private const val SPRITE = 100
private const val FOOT = 90

private fun mk(seed: Int = 1, speed: Float = 100f) = Behavior(W, H, SPRITE, SPRITE, FOOT, Random(seed), speed, 3000f)

private fun Behavior.putFeet(feetX: Float, feetY: Float) = dragEnd(feetX - SPRITE / 2f, feetY - FOOT)

private fun Behavior.settle(maxMs: Int = 10_000) {
    var t = 0
    while (!state.grounded) {
        tick(10); t += 10
        assertTrue("never landed", t <= maxMs)
    }
}

private fun Behavior.runFor(ms: Int) = repeat(ms / 10) { tick(10) }

private fun layout(vararg p: Platform, targets: List<Target> = emptyList()) = Layout(p.toList(), targets)

private val SEND = Target("send", Box(400, 1500, 600, 1560))
private val SEND_LAYOUT = layout(Platform(400, 600, 1500, "send"), targets = listOf(SEND))

/** On the floor at feet x 300, sitting on SEND after one jump. */
private fun sittingOnSend(seed: Int = 1): Behavior {
    val b = mk(seed, speed = 200f)
    b.setLayout(SEND_LAYOUT)
    b.putFeet(300f, H.toFloat())
    b.command("sit_on", "send")
    b.settle()
    assertEquals("tease", b.state.doing)
    return b
}

class BehaviorCommandTest {
    // ---- plain intents ----

    @Test fun stay() {
        var decidedAfter = 0
        for (seed in 0 until 30) {
            val b = mk(seed)
            b.play("walk"); assertEquals("walk", b.animation)
            b.command("stay", null)
            assertEquals("idle", b.animation)
            val x0 = b.x
            repeat(999) { // 9 990 ms: longer than any ordinary decision wait (8 s), and she never moves
                b.tick(10)
                assertEquals("idle", b.animation); assertEquals(x0, b.x)
            }
            b.tick(10) // 10 s: she decides again
            if (b.animation != "idle") decidedAfter++
        }
        assertTrue("never decided after the stay", decidedAfter > 0)
    }

    @Test fun wander() {
        val b = mk()
        b.command("nap", null); assertEquals("nap", b.animation)
        b.command("wander", null); assertNotEquals("nap", b.animation)
        // a decision is taken at once, not after the idle wait
        assertTrue((0 until 20).any { seed ->
            val c = mk(seed)
            c.command("stay", null); c.command("wander", null)
            c.animation == "walk"
        })
    }

    @Test fun come_closer() {
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(0, 300, 1500), Platform(400, 700, 1200))) // (550, 1200) is nearest the centre
        b.putFeet(150f, H.toFloat())
        b.command("come_closer", null)
        assertEquals("jump", b.state.doing)
        b.settle()
        assertEquals(550f, b.state.feetX); assertEquals(1200f, b.state.feetY); assertEquals("idle", b.state.doing)

        val c = mk(speed = 200f) // no platforms: the floor centre
        c.command("come_closer", null)
        assertEquals("walk", c.animation); assertTrue(c.facingLeft)
        c.runFor(3000)
        assertEquals(500f, c.state.feetX); assertEquals("idle", c.state.doing)

        val d = mk(speed = 200f) // the nearest is out of reach: the floor centre
        d.setLayout(layout(Platform(400, 700, 1000)))
        d.command("come_closer", null)
        d.runFor(3000)
        assertEquals(500f, d.state.feetX); assertEquals("floor", d.state.where)
    }

    @Test fun go_left() {
        val b = mk(speed = 200f)
        val here = Platform(400, 700, 1200)
        val level = Platform(100, 350, 1250) // within 0.1 × screenH of her height
        val low = Platform(0, 300, 1500) // reaches farther left, but 300 px lower
        b.setLayout(layout(here, level, low))
        b.putFeet(500f, 1200f)
        b.command("go_left", null)
        assertEquals("jump", b.state.doing); assertTrue(b.facingLeft)
        b.runFor(1500) // jump, then walk to the far end
        assertEquals(100f, b.state.feetX); assertEquals(1250f, b.state.feetY); assertEquals("idle", b.state.doing)
    }

    @Test fun go_right() {
        val b = mk(speed = 200f)
        b.putFeet(500f, H.toFloat())
        b.command("go_right", null)
        assertEquals("walk", b.animation); assertFalse(b.facingLeft)
        b.runFor(3000)
        assertEquals(950f, b.state.feetX); assertEquals("idle", b.state.doing)

        val c = mk(speed = 200f) // nothing at her height: the end of her own platform
        c.setLayout(layout(Platform(400, 700, 1200), Platform(800, 1000, 1700)))
        c.putFeet(500f, 1200f)
        c.command("go_right", null)
        c.runFor(1500)
        assertEquals(700f, c.state.feetX); assertEquals(1200f, c.state.feetY)
    }

    @Test fun go_up() {
        val b = mk()
        b.setLayout(layout(Platform(100, 500, 1500), Platform(500, 900, 1300), Platform(0, 1000, 1000)))
        b.putFeet(450f, H.toFloat())
        b.command("go_up", null)
        assertEquals("jump", b.state.doing)
        b.settle()
        assertEquals(1300f, b.state.feetY) // the highest one she can reach (1000 is too high)

        val c = mk()
        c.command("go_up", null) // nothing to jump to: no-op
        assertTrue(c.state.grounded); assertEquals("idle", c.state.doing); assertEquals(H.toFloat(), c.state.feetY)

        val d = mk() // on the highest platform, only lower ones in reach: never a jump down
        d.setLayout(layout(Platform(100, 500, 1000), Platform(400, 800, 1300), Platform(0, 300, 1600)))
        d.putFeet(300f, 1000f)
        d.command("go_up", null)
        assertTrue(d.state.grounded); assertEquals("idle", d.state.doing); assertEquals(1000f, d.state.feetY)
    }

    @Test fun go_down() {
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(200, 600, 1000), Platform(0, 1000, 1500)))
        b.putFeet(500f, 1000f)
        b.command("go_down", null)
        assertEquals("walk", b.animation) // to the nearer edge
        b.runFor(5000) // drops onto the wide one, then hops down from it (no edge to step off)
        assertEquals("floor", b.state.where); assertEquals(H.toFloat(), b.state.feetY)
        assertTrue(b.drainMoments().isEmpty())
    }

    @Test fun go_down_and_come_closer_finish_over_touching_edges() {
        // two edges at the same height, 1 px apart: stepping off one only lands on the other
        val twins = layout(Platform(200, 600, 1000), Platform(601, 900, 1000))
        val b = mk(speed = 200f)
        b.setLayout(twins); b.putFeet(550f, 1000f)
        b.command("go_down", null)
        b.runFor(8000)
        assertEquals("floor", b.state.where); assertEquals(H.toFloat(), b.state.feetY)

        val c = mk(speed = 200f) // the nearest-centre one is out of reach from here: the floor centre
        c.setLayout(layout(Platform(200, 600, 1700), Platform(601, 900, 1700), Platform(400, 600, 700))) // rise 1 000
        c.putFeet(550f, 1700f)
        c.command("come_closer", null)
        c.runFor(8000)
        assertEquals("floor", c.state.where); assertEquals(500f, c.state.feetX)
    }

    @Test fun a_move_not_finished_in_15s_is_given_up() {
        val b = mk(speed = 20f) // 450 px to the floor centre at 20 px/s: 22.5 s
        b.command("come_closer", null)
        b.runFor(14_990); assertEquals("walk", b.animation)
        b.runFor(20); assertEquals("idle", b.animation) // back to her own roaming
        val x = b.x
        b.runFor(2000); assertEquals(x, b.x)
    }

    @Test fun sit() {
        val b = mk()
        b.command("sit", null); assertEquals("sit", b.animation); assertEquals("sit", b.state.doing)
        b.setLayout(layout(Platform(200, 600, 1000))); b.putFeet(400f, 1000f)
        b.command("sit", null); assertEquals("perch", b.animation); assertEquals("perch", b.state.doing)
    }

    @Test fun nap_ends_after_60s() {
        val b = mk()
        b.command("nap", null); assertEquals("nap", b.animation); assertEquals("nap", b.state.doing)
        b.tick(59_999); assertEquals("nap", b.animation)
        b.tick(1); assertEquals("idle", b.animation)
        b.command("nap", null)
        b.command("sit", null); assertEquals("sit", b.animation) // the next move ends it
    }

    @Test fun peek() {
        // the nearer screen side is left; her platform does not reach it: down, then along the floor
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(200, 600, 1000))); b.putFeet(300f, 1000f)
        b.command("peek", null)
        assertEquals("walk", b.animation); assertTrue(b.facingLeft)
        var t = 0
        while (b.animation != "peek") {
            b.tick(10); t += 10
            assertTrue("never peeked", t <= 5000)
        }
        assertEquals(50f, b.state.feetX); assertEquals("floor", b.state.where)
        assertTrue(b.facingLeft); assertEquals("peek", b.state.doing)

        // a platform running to the screen side: she peeks from it
        val c = mk(speed = 200f)
        c.setLayout(layout(Platform(0, 400, 1000))); c.putFeet(300f, 1000f)
        c.command("peek", null)
        c.runFor(1500)
        assertEquals("peek", c.animation); assertEquals(50f, c.state.feetX); assertEquals(1000f, c.state.feetY)

        // on the right half: the right side
        val d = mk(speed = 200f)
        d.putFeet(700f, H.toFloat())
        d.command("peek", null)
        d.runFor(1500)
        assertEquals("peek", d.animation); assertEquals(950f, d.state.feetX); assertFalse(d.facingLeft)
    }

    @Test fun unknown_intent_is_ignored() {
        val b = mk()
        b.play("walk")
        b.command("moonwalk", null); assertEquals("walk", b.animation)
    }

    @Test fun command_replaces_command() {
        val b = mk(speed = 200f)
        b.command("come_closer", null) // heading left to the floor centre
        b.tick(1000); assertEquals(750f, b.state.feetX); assertTrue(b.facingLeft)
        b.command("go_right", null)
        assertFalse(b.facingLeft)
        b.runFor(2000)
        assertEquals(950f, b.state.feetX)
    }

    @Test fun command_ignored_while_dragging() {
        val b = mk()
        b.dragStart()
        b.command("nap", null); assertEquals("surprised", b.animation)
        b.dragEnd(400f, (H - FOOT).toFloat())
        assertEquals("idle", b.animation)
    }

    @Test fun things_asked_in_the_air_run_in_arrival_order() {
        val b = mk()
        b.putFeet(500f, 1000f)
        b.play("wave"); b.command("nap", null) // the later command waits for the earlier gesture
        b.settle(); assertEquals("wave", b.animation)
        b.tick(ONE_SHOT_MS.toLong()); b.tick(10); assertEquals("nap", b.animation)
        b.tick(ONE_SHOT_MS.toLong() + 10); assertEquals("nap", b.animation)

        val c = mk()
        c.putFeet(500f, 1000f)
        c.command("nap", null); c.play("happy") // here the play is the later one
        c.settle(); assertEquals("happy", c.animation)
    }

    @Test fun a_move_waits_for_the_gesture_of_the_same_thought() {
        for (seed in 0 until 10) {
            val b = mk(seed)
            b.play("happy"); b.command("stay", null)
            val x0 = b.x
            repeat(ONE_SHOT_MS / 10 - 1) { // the whole gesture shows
                b.tick(10)
                assertEquals("happy", b.animation); assertEquals(x0, b.x)
            }
            b.tick(10); b.tick(10)
            assertEquals("idle", b.animation)
            repeat(980) { // then the stay: 9.8 s still, longer than any ordinary decision wait (8 s)
                b.tick(10)
                assertEquals("idle", b.animation); assertEquals(x0, b.x)
            }
        }

        val c = mk(speed = 200f) // on the floor at the right side
        c.play("wave"); c.command("go_right", null); c.command("go_left", null) // the latest wins
        c.tick(ONE_SHOT_MS.toLong() - 10); assertEquals("wave", c.animation); assertEquals(950f, c.state.feetX)
        c.tick(10); c.tick(100)
        assertEquals("walk", c.animation); assertTrue(c.facingLeft); assertTrue(c.state.feetX < 950f)

        val d = mk() // a gesture replaced by another animation: the move runs at once
        d.play("happy"); d.command("nap", null); d.play("idle")
        d.tick(10); assertEquals("nap", d.animation)
    }

    @Test fun command_and_play_in_the_air_run_on_landing() {
        val b = mk()
        b.putFeet(500f, 1000f) // falling
        b.command("sit", null); b.command("nap", null) // the latest wins
        assertEquals("fall", b.animation)
        b.settle(); assertEquals("nap", b.animation)

        val c = mk()
        c.putFeet(500f, 1000f)
        c.play("wave"); c.play("happy")
        assertEquals("fall", c.animation)
        c.settle(); assertEquals("happy", c.animation)
    }

    // ---- teases ----

    @Test fun sit_on_covers_target_4_to_8s_cap() {
        val durations = (0 until 20).map { seed ->
            val b = sittingOnSend(seed)
            val s = b.state
            assertEquals("target", s.where); assertEquals("send", s.target); assertEquals("tease", b.animation)
            assertTrue(s.grounded)
            // her sprite's centre is the button's centre
            assertEquals(500f, b.x + SPRITE / 2f); assertEquals(1530f, b.y + SPRITE / 2f)
            var t = 0
            while (b.state.doing == "tease") {
                b.tick(10); t += 10
                assertTrue("over the cap", t <= 8000)
            }
            assertEquals("jump", b.state.doing) // she hops off
            b.settle(); assertNull(b.state.target); assertNotEquals("target", b.state.where)
            t
        }
        assertTrue(durations.toString(), durations.all { it in 4000..8000 })
        assertTrue(durations.toSet().size > 1)
    }

    @Test fun sit_on_goes_via_the_floor_when_out_of_reach() {
        val b = mk(speed = 200f)
        val send = Target("send", Box(800, 1500, 900, 1560))
        b.setLayout(layout(Platform(0, 200, 1000), Platform(800, 900, 1500, "send"), targets = listOf(send)))
        b.putFeet(100f, 1000f) // 700 px from the button: too far for one jump
        b.command("sit_on", "send")
        assertEquals("walk", b.animation)
        var t = 0
        while (b.state.doing != "tease") {
            b.tick(10); t += 10
            assertTrue("never got there", t <= 6000)
        }
        assertEquals("target", b.state.where)
        assertEquals(850f, b.x + SPRITE / 2f); assertEquals(1530f, b.y + SPRITE / 2f)
    }

    @Test fun sit_on_gives_up_after_6s_and_plays_happy() {
        val b = mk(speed = 100f) // feet at 950 on the floor
        val send = Target("send", Box(100, 900, 200, 960)) // 1 100 px above the floor: never reachable
        b.setLayout(layout(Platform(100, 200, 900, "send"), targets = listOf(send)))
        b.command("sit_on", "send")
        assertEquals("walk", b.animation) // heading below it
        b.runFor(5990); assertEquals("walk", b.animation)
        b.runFor(20); assertEquals("happy", b.animation)
    }

    @Test fun poke_then_hop() {
        val like = Target("like", Box(400, 1500, 600, 1560))
        val other = Platform(700, 1000, 1400)
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(400, 600, 1500, "like"), other, targets = listOf(like)))
        b.putFeet(300f, H.toFloat())
        b.command("poke", "like")
        b.settle()
        val s = b.state
        assertEquals("tease", s.doing); assertEquals("target", s.where); assertEquals("like", s.target)
        assertEquals("poke", b.animation); assertEquals("poke", s.gesture)
        assertEquals(500f, s.feetX); assertEquals(1500f, s.feetY) // on the button's top edge
        b.runFor(2490); assertEquals("poke", b.animation)
        b.runFor(20); assertEquals("jump", b.state.doing)
        b.settle()
        assertEquals(1400f, b.state.feetY); assertEquals("platform", b.state.where); assertNull(b.state.target)
    }

    @Test fun poke_a_button_too_small_to_be_a_platform() {
        val like = Target("like", Box(480, 1500, 520, 1540)) // no platform for it in the layout
        val b = mk(speed = 200f)
        b.setLayout(layout(targets = listOf(like)))
        b.putFeet(300f, H.toFloat())
        b.command("poke", "like")
        b.settle()
        assertEquals("tease", b.state.doing); assertEquals(1500f, b.state.feetY); assertEquals(500f, b.state.feetX)
        b.runFor(2510)
        b.settle(); assertEquals("floor", b.state.where)
        b.setLayout(layout(targets = listOf(like))) // its edge is gone with the tease: nothing to fall from
        assertTrue(b.drainMoments().isEmpty())
    }

    @Test fun tease_missing_or_restricted_plays_happy() {
        val b = mk()
        b.command("sit_on", "send"); assertEquals("happy", b.animation) // no targets at all

        val c = mk()
        c.setLayout(SEND_LAYOUT)
        c.command("sit_on", "like"); assertEquals("happy", c.animation) // none with that tag

        val d = mk()
        d.setLayout(SEND_LAYOUT)
        d.command("poke", null); assertEquals("happy", d.animation)

        val e = mk()
        e.setLayout(SEND_LAYOUT); e.setRestricted(true)
        e.command("poke", "send"); assertEquals("happy", e.animation)
    }

    @Test fun nearest_target_wins() {
        val near = Target("send", Box(100, 1500, 200, 1560))
        val far = Target("send", Box(800, 1500, 900, 1560))
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(100, 200, 1500, "send"), Platform(800, 900, 1500, "send"), targets = listOf(far, near)))
        b.putFeet(300f, H.toFloat())
        b.command("poke", "send")
        b.settle()
        assertEquals("tease", b.state.doing); assertEquals(150f, b.state.feetX)
    }

    @Test fun tap_during_tease_sulks_and_shoos() {
        val b = sittingOnSend()
        assertTrue(b.drainMoments().isEmpty())
        assertTrue(b.tapped())
        assertEquals("sulk", b.animation); assertEquals("tease", b.state.doing); assertEquals("target", b.state.where)
        assertEquals(listOf(Moment("shooed", "send")), b.drainMoments())
        assertTrue(b.tapped()); assertTrue(b.drainMoments().isEmpty()) // already sulking: one moment
        b.runFor(1490); assertEquals("sulk", b.animation)
        b.runFor(20); assertEquals("jump", b.state.doing)
        b.settle(); assertNotEquals("target", b.state.where); assertNull(b.state.target)
    }

    @Test fun tap_late_in_a_tease_keeps_the_8s_cap() {
        var found = false
        for (seed in 0 until 300) {
            val b = sittingOnSend(seed)
            b.runFor(7900)
            if (b.animation != "tease") continue // this one already ended
            found = true
            assertTrue(b.tapped())
            assertEquals("sulk", b.animation)
            b.runFor(100) // 8.0 s on the target: off, the sulk cut short
            assertEquals("jump", b.state.doing)
            break
        }
        assertTrue("no seed sat past 7.9 s", found)
    }

    @Test fun tap_while_heading_to_a_tease_shoos_too() {
        val b = mk(speed = 100f)
        val send = Target("send", Box(100, 900, 200, 960))
        b.setLayout(layout(Platform(100, 200, 900, "send"), targets = listOf(send)))
        b.command("sit_on", "send"); b.tick(500)
        assertTrue(b.tapped())
        assertEquals("sulk", b.animation)
        assertEquals(listOf(Moment("shooed", "send")), b.drainMoments())
        b.runFor(1510); assertEquals("idle", b.animation)
        val x = b.x
        b.runFor(2000); assertEquals(x, b.x) // the tease is over: no more walking toward it
    }

    @Test fun tap_without_tease_false() {
        val b = mk()
        assertFalse(b.tapped()); assertTrue(b.drainMoments().isEmpty())
        b.command("nap", null)
        assertFalse(b.tapped()); assertEquals("idle", b.animation) // a tap wakes her
    }

    @Test fun tease_target_vanishes_ends_tease() {
        val b = sittingOnSend()
        // a small move: she stays on it
        b.setLayout(layout(Platform(400, 600, 1505, "send"), targets = listOf(Target("send", Box(400, 1505, 600, 1565)))))
        assertEquals("tease", b.state.doing); assertEquals(1535f, b.y + SPRITE / 2f)
        b.setLayout(Layout.EMPTY)
        assertEquals("fall", b.state.doing); assertNull(b.state.target)
        b.settle()
        assertEquals("floor", b.state.where)
        assertEquals(listOf(Moment("fell", "send")), b.drainMoments())

        val c = sittingOnSend()
        c.setRestricted(true) // the keyboard opened: the tease ends at once, but she did not "fall off"
        assertEquals("fall", c.state.doing)
        c.settle(); assertTrue(c.drainMoments().isEmpty())
    }

    @Test fun fell_moment_only_on_vanish() {
        val p = Platform(200, 600, 1000)

        val gone = mk()
        gone.setLayout(layout(p)); gone.putFeet(400f, 1000f)
        gone.setLayout(Layout.EMPTY); gone.settle()
        assertEquals(listOf(Moment("fell", null)), gone.drainMoments())
        assertTrue(gone.drainMoments().isEmpty())

        val moved = mk()
        moved.setLayout(layout(p)); moved.putFeet(400f, 1000f)
        moved.setLayout(layout(Platform(200, 600, 1020))); moved.settle()
        assertEquals(1020f, moved.state.feetY)
        assertEquals(listOf(Moment("fell", null)), moved.drainMoments())

        val retagged = mk() // identical geometry, new tag: a 0 px "fall"
        retagged.setLayout(layout(Platform(200, 600, 1000, "like"))); retagged.putFeet(400f, 1000f)
        retagged.setLayout(layout(Platform(200, 600, 1000, "send"))); retagged.tick(10)
        assertTrue(retagged.state.grounded); assertTrue(retagged.drainMoments().isEmpty())

        val down = mk(speed = 200f)
        down.setLayout(layout(p)); down.putFeet(400f, 1000f)
        down.command("go_down", null); down.runFor(5000)
        assertEquals("floor", down.state.where); assertTrue(down.drainMoments().isEmpty())

        val dragged = mk()
        dragged.setLayout(layout(p)); dragged.putFeet(400f, 1000f)
        dragged.dragStart(); dragged.dragEnd(700f, 100f); dragged.settle()
        assertTrue(dragged.drainMoments().isEmpty())

        val trimmed = mk() // the app's layout cut at Maya's own window: she falls, but it is no `fell`
        trimmed.setLayout(layout(p)); trimmed.putFeet(400f, 1000f)
        trimmed.setLayout(Layout.EMPTY, quiet = true); trimmed.settle()
        assertEquals("floor", trimmed.state.where); assertTrue(trimmed.drainMoments().isEmpty())

        val restricted = mk()
        restricted.setLayout(layout(p)); restricted.putFeet(400f, 1000f)
        restricted.setRestricted(true); restricted.settle()
        assertEquals("floor", restricted.state.where); assertTrue(restricted.drainMoments().isEmpty())
    }

    @Test fun random_moves_keep_her_on_screen() {
        val intents = MOVE_INTENTS + "bogus"
        val layouts = listOf(
            SEND_LAYOUT,
            layout(Platform(0, 300, 1100), Platform(500, 900, 1300, "like"), Platform(100, 1000, 1700),
                targets = listOf(Target("like", Box(500, 1300, 900, 1360)), Target("send", Box(20, 400, 60, 440)))),
            Layout.EMPTY,
        )
        repeat(40) { seed ->
            val rng = Random(seed)
            val b = mk(seed, speed = 300f)
            b.setTemper("excited", "close")
            repeat(3000) {
                when (rng.nextInt(60)) {
                    0 -> b.command(intents[rng.nextInt(intents.size)], listOf("send", "like", null)[rng.nextInt(3)])
                    1 -> b.setLayout(layouts[rng.nextInt(layouts.size)])
                    2 -> b.tapped()
                    3 -> b.setRestricted(rng.nextInt(4) == 0)
                    4 -> b.play(listOf("wave", "happy", "walk")[rng.nextInt(3)])
                }
                b.tick(30)
                assertTrue("x ${b.x}", b.x in 0f..900f)
                assertTrue("y ${b.y}", b.y >= 0f && b.state.feetY <= H.toFloat())
                b.drainMoments().forEach { assertTrue(it.kind == "fell" || it.kind == "shooed") }
            }
        }
    }

    // ---- press ----

    @Test fun press_command_reports_done_and_lost() {
        val btn = Box(400, 1500, 600, 1560) // any labelled box: not a tease target
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(400, 600, 1500)))
        b.putFeet(300f, H.toFloat())
        assertFalse(b.pressDone()); assertFalse(b.pressTargetLost())
        b.commandPress(btn)
        assertFalse(b.pressDone())
        b.settle()
        // she is on it and poking; the first ask says so, then it is consumed
        assertEquals("poke", b.animation); assertEquals("tease", b.state.doing)
        assertEquals(500f, b.state.feetX); assertEquals(1500f, b.state.feetY)
        assertNull(b.state.target) // not a tease tag: the brain is told nothing
        assertTrue(b.pressDone()); assertFalse(b.pressDone())
        assertFalse(b.pressTargetLost())
        b.runFor(2510)
        b.settle()
        assertFalse(b.pressDone()); assertFalse(b.pressTargetLost())
        assertTrue(b.drainMoments().isEmpty())
    }

    @Test fun press_a_small_box_with_no_platform() {
        val b = mk(speed = 200f)
        b.putFeet(300f, H.toFloat())
        b.commandPress(Box(480, 1500, 520, 1540))
        b.settle()
        assertEquals("poke", b.animation); assertEquals(1500f, b.state.feetY); assertEquals(500f, b.state.feetX)
        assertTrue(b.pressDone())
    }

    @Test fun press_lost_after_6s_and_when_restricted() {
        // cannot get under it in time: 6 s like a tease
        val far = Box(100, 900, 200, 960)
        val b = mk(speed = 100f)
        b.setLayout(layout(Platform(100, 200, 900)))
        b.commandPress(far)
        assertEquals("walk", b.animation)
        b.runFor(5990); assertFalse(b.pressTargetLost()); assertFalse(b.pressDone())
        b.runFor(20)
        assertTrue(b.pressTargetLost()); assertFalse(b.pressTargetLost()) // once
        assertFalse(b.pressDone())

        val r = mk()
        r.setRestricted(true)
        r.commandPress(Box(400, 1500, 600, 1560))
        assertTrue(r.pressTargetLost()); assertFalse(r.pressDone())
        assertFalse(r.pressTargetLost())

        // a box below the floor is not reachable either
        val c = mk()
        c.commandPress(Box(400, H + 10, 600, H + 60))
        assertTrue(c.pressTargetLost())
    }

    @Test fun press_waits_for_a_landing_and_a_new_command_clears_it() {
        val btn = Box(400, 1500, 600, 1560)
        val b = mk(speed = 200f)
        b.setLayout(layout(Platform(400, 600, 1500), Platform(100, 300, 1000)))
        b.putFeet(200f, 600f) // let go in mid-air
        assertEquals("fall", b.state.doing)
        b.commandPress(btn) // in the air: runs on landing
        var done = false
        repeat(1500) { if (!done) { b.tick(10); done = b.pressDone() } }
        assertTrue("never poked", done)
        assertEquals("poke", b.animation); assertEquals(1500f, b.state.feetY)

        val c = mk(speed = 200f)
        c.setLayout(layout(Platform(400, 600, 1500)))
        c.putFeet(300f, H.toFloat())
        c.commandPress(btn)
        c.command("stay", null) // replaced: no longer pressing
        c.runFor(8000)
        assertFalse(c.pressDone()); assertFalse(c.pressTargetLost())
    }
}
