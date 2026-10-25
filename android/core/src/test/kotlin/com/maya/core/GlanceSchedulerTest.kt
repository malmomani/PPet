package com.maya.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceSchedulerTest {
    private val lines = listOf("a", "b")

    @Test fun settle_then_due_once() {
        val g = GlanceScheduler()
        assertFalse(g.poll(0, true))
        g.onApp("com.x", 1_000)
        assertFalse(g.poll(2_999, true))
        assertTrue(g.poll(3_000, true))
        assertFalse(g.poll(3_001, true))
    }

    @Test fun app_change_rearms() {
        val g = GlanceScheduler()
        g.onApp("com.x", 0)
        g.onApp("com.y", 1_500)
        assertFalse(g.poll(2_000, true))
        assertTrue(g.poll(3_500, true))
        // same package does not re-arm
        g.onApp("com.y", 4_000)
        assertFalse(g.poll(10_000, true))
        // null package clears what is due
        g.onApp("com.z", 10_000)
        g.onApp(null, 10_500)
        assertFalse(g.poll(20_000, true))
        // coming back to the same package after null arms again
        g.onApp("com.z", 20_000)
        assertTrue(g.poll(22_000, true))
    }

    @Test fun period_and_hash_dedupe() {
        val g = GlanceScheduler()
        g.onApp("com.x", 0)
        assertTrue(g.poll(2_000, true))
        assertTrue(g.taken("com.x", lines, 2_000))
        assertFalse(g.poll(181_999, true))
        assertTrue(g.poll(182_000, true))
        // unchanged text: not sent, but the next check is still armed
        assertFalse(g.taken("com.x", lines, 182_000))
        assertFalse(g.poll(361_999, true))
        assertTrue(g.poll(362_000, true))
        // changed text is sent
        assertTrue(g.taken("com.x", listOf("c"), 362_000))
        // dedupe is per package
        assertTrue(g.taken("com.y", listOf("c"), 362_000))
    }

    @Test fun first_glance_after_an_app_change_is_always_sent() {
        val g = GlanceScheduler()
        g.onApp("com.x", 0)
        assertTrue(g.poll(2_000, true)); assertTrue(g.taken("com.x", lines, 2_000))
        // to another app and back: X's unchanged text goes out again (the brain dropped it)
        g.onApp("com.y", 10_000)
        assertTrue(g.poll(12_000, true)); assertTrue(g.taken("com.y", listOf("y"), 12_000))
        g.onApp("com.x", 20_000)
        assertTrue(g.poll(22_000, true)); assertTrue(g.taken("com.x", lines, 22_000))
        // the periodic same-app re-check still dedupes
        assertTrue(g.poll(202_000, true)); assertFalse(g.taken("com.x", lines, 202_000))
        // an app change whose glance never got taken (blocked app, failed capture) still counts
        g.onApp("com.z", 210_000)
        g.onApp("com.x", 211_000)
        assertTrue(g.poll(213_000, true)); assertTrue(g.taken("com.x", lines, 213_000))
        // off (null) and back to the same app counts as a change too
        g.onApp(null, 220_000)
        g.onApp("com.x", 230_000)
        assertTrue(g.poll(232_000, true)); assertTrue(g.taken("com.x", lines, 232_000))
        assertTrue(g.poll(412_000, true)); assertFalse(g.taken("com.x", lines, 412_000))
    }

    @Test fun not_allowed_keeps_due() {
        val g = GlanceScheduler()
        g.onApp("com.x", 0)
        assertFalse(g.poll(2_000, false))
        assertFalse(g.poll(500_000, false))
        assertTrue(g.poll(500_001, true))
        assertFalse(g.poll(500_002, true))
    }

    @Test fun failed_capture_retries_after_period() {
        val g = GlanceScheduler()
        g.onApp("com.x", 0)
        assertTrue(g.poll(2_000, true))
        // capture failed: taken() is never called
        assertFalse(g.poll(181_999, true))
        assertTrue(g.poll(182_000, true))
    }
}
