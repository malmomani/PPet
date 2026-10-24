package com.maya.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineQueueTest {
    @Test fun queue_drops_oldest_and_summarizes() {
        val q = OfflineQueue(capacity = 3)
        val first = Instant.parse("2026-10-24T01:00:00Z")
        q.add(Screen(false, ts = first))
        (1..4).forEach { q.add(AppClosed("p$it", "L$it", it.toLong(), ts = first.plusSeconds(it * 60L))) }
        assertEquals(3, q.size)
        val s = q.drainSummary()!!
        assertEquals(first, s.since)
        assertEquals(listOf("p2", "p3", "p4"), s.events.map { (it as AppClosed).packageName })
        assertNull(q.drainSummary())
    }
}
