package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyReporterTest {
    private fun snap(doing: String = "idle", where: String = "floor", target: String? = null, x: Double = 0.5, y: Double = 1.0) =
        BodySnapshot(doing, where, target, x, y)

    @Test fun body_reporter_change_detection() {
        val r = BodyReporter()
        val a = snap()
        assertEquals(a, r.update(a, 0))
        // x/y alone never trigger a send, even long after the window
        assertNull(r.update(snap(x = 0.9, y = 0.2), 10_000))
        assertNull(r.poll(20_000))
        // a change of any of doing / where / target does
        assertEquals(snap("walk"), r.update(snap("walk"), 20_000))
        assertEquals(snap("walk", "platform"), r.update(snap("walk", "platform"), 30_000))
        assertEquals(snap("walk", "platform", "send"), r.update(snap("walk", "platform", "send"), 40_000))
    }

    @Test fun body_reporter_throttles() {
        val r = BodyReporter()
        val first = snap("walk")
        val second = snap("sit")
        val third = snap("nap")
        assertEquals(first, r.update(first, 0))
        assertNull(r.update(second, 400))
        assertNull(r.update(third, 900))
        assertNull(r.poll(4_999))
        assertEquals(third, r.poll(5_000))
        assertNull(r.poll(5_001))
    }

    @Test fun pending_dropped_when_back_to_last_sent() {
        val r = BodyReporter()
        val a = snap("walk")
        assertEquals(a, r.update(a, 0))
        assertNull(r.update(snap("sit"), 100))
        assertNull(r.update(a, 200))
        assertNull(r.poll(10_000))
    }

    @Test fun reset_resends() {
        val r = BodyReporter()
        val a = snap()
        assertEquals(a, r.update(a, 0))
        assertNull(r.update(a, 100))
        r.reset()
        assertEquals(a, r.update(a, 200))
    }
}
