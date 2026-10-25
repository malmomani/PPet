package com.maya.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

fun stripMeta(e: Event): Event = when (e) {
    is AppOpened -> e.copy(id = "", ts = Instant.EPOCH)
    is AppClosed -> e.copy(id = "", ts = Instant.EPOCH)
    else -> e
}

class AppUsageTrackerTest {
    @Test fun open_switch_close_sequence() {
        val t = AppUsageTracker(isIgnored = { false }); val t0 = Instant.parse("2026-10-24T14:00:00Z")
        assertEquals(listOf<Event>(AppOpened("tt", "TikTok")).map(::stripMeta), t.observe("tt", "TikTok", t0).map(::stripMeta))
        assertEquals(emptyList<Event>(), t.observe("tt", "TikTok", t0.plusSeconds(5)))
        assertEquals(
            listOf<Event>(AppClosed("tt", "TikTok", 90), AppOpened("yt", "YouTube")).map(::stripMeta),
            t.observe("yt", "YouTube", t0.plusSeconds(90)).map(::stripMeta),
        )
        assertEquals(
            listOf<Event>(AppClosed("yt", "YouTube", 30)).map(::stripMeta),
            t.observe(null, null, t0.plusSeconds(120)).map(::stripMeta),
        )
        assertNull(t.current())
    }

    @Test fun ignored_package_closes_previous_without_opening() {
        val t = AppUsageTracker(isIgnored = { it == "bank" }); val t0 = Instant.parse("2026-10-24T14:00:00Z")
        t.observe("tt", "TikTok", t0)
        assertEquals(
            listOf<Event>(AppClosed("tt", "TikTok", 10)).map(::stripMeta),
            t.observe("bank", "Bank", t0.plusSeconds(10)).map(::stripMeta),
        )
        assertEquals(emptyList<Event>(), t.observe("bank", "Bank", t0.plusSeconds(20)))
    }

    @Test fun events_carry_now_as_ts_and_open_time_label() {
        val t = AppUsageTracker(isIgnored = { false }); val t0 = Instant.parse("2026-10-24T14:00:00Z")
        val opened = t.observe("a", "A-old", t0).single()
        assertEquals(t0, opened.ts)
        val out = t.observe("b", "B", t0.plusSeconds(7))
        assertEquals(listOf("a", "b"), out.map { (it as? AppClosed)?.packageName ?: (it as AppOpened).packageName })
        assertEquals("A-old", (out[0] as AppClosed).label)
        assertEquals(t0.plusSeconds(7), out[0].ts)
        assertEquals(t0.plusSeconds(7), out[1].ts)
    }

    @Test fun negative_duration_clamped_and_fractional_truncated() {
        val t = AppUsageTracker(isIgnored = { false }); val t0 = Instant.parse("2026-10-24T14:00:00Z")
        t.observe("a", "A", t0)
        assertEquals(0L, (t.observe(null, null, t0.minusSeconds(30)).single() as AppClosed).durationS)
        t.observe("a", "A", t0)
        assertEquals(2L, (t.observe(null, null, t0.plusMillis(2900)).single() as AppClosed).durationS)
    }

    @Test fun current_reflects_tracked_package_and_ignored_is_null() {
        val t = AppUsageTracker(isIgnored = { it == "bank" }); val t0 = Instant.parse("2026-10-24T14:00:00Z")
        t.observe("a", "A", t0); assertEquals("a", t.current())
        t.observe("bank", "Bank", t0.plusSeconds(1)); assertNull(t.current())
    }

    @Test fun own_package_is_transparent() {
        val t = AppUsageTracker(isIgnored = { false }, isTransparent = { it == "maya" })
        val t0 = Instant.parse("2026-10-24T14:00:00Z")
        t.observe("tt", "TikTok", t0)
        assertEquals(emptyList<Event>(), t.observe("maya", "Maya", t0.plusSeconds(10)))
        assertEquals("tt", t.current())
        assertEquals(
            listOf<Event>(AppClosed("tt", "TikTok", 60), AppOpened("yt", "YouTube")).map(::stripMeta),
            t.observe("yt", "YouTube", t0.plusSeconds(60)).map(::stripMeta),
        )
    }
}
