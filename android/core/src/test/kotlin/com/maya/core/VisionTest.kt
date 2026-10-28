package com.maya.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTest {
    private val w = 1000
    private val h = 2000 // screen area 2_000_000

    @Test fun picture_heavy_rule() {
        // 40 % of the screen in images: heavy whatever the text
        assertTrue(isPictureHeavy(listOf(Box(0, 0, 1000, 800)), w, h, 50))
        assertTrue(isPictureHeavy(listOf(Box(0, 0, 1000, 400), Box(0, 500, 1000, 900)), w, h, 50))
        // just under 40 %, many lines: not heavy
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 1000, 399), Box(0, 500, 1000, 900)), w, h, 50))
        // one image of exactly 25 % with at most 8 lines: heavy
        assertTrue(isPictureHeavy(listOf(Box(0, 0, 1000, 500)), w, h, 8))
        assertTrue(isPictureHeavy(listOf(Box(0, 0, 1000, 500)), w, h, 0))
        // the same with 9 lines: text-heavy
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 1000, 500)), w, h, 9))
        // one image just under 25 %
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 1000, 499)), w, h, 0))
        // two 20 % images (40 % in total) is heavy by area; two 12 % ones are neither
        assertTrue(isPictureHeavy(listOf(Box(0, 0, 1000, 400), Box(0, 500, 1000, 900)), w, h, 3))
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 1000, 240), Box(0, 500, 1000, 740)), w, h, 3))
        // text-heavy screen with a thumbnail, and an empty screen
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 100, 100)), w, h, 30))
        assertFalse(isPictureHeavy(emptyList(), w, h, 0))
    }

    @Test fun picture_heavy_clips_to_screen() {
        // mostly off screen: only 100x100 is visible
        assertFalse(isPictureHeavy(listOf(Box(900, 1900, 5000, 6000)), w, h, 0))
        // clipped on every side still counts what is visible
        assertTrue(isPictureHeavy(listOf(Box(-500, -500, 1500, 2500)), w, h, 40))
        // degenerate screen or boxes
        assertFalse(isPictureHeavy(listOf(Box(0, 0, 10, 10)), 0, 0, 0))
        assertFalse(isPictureHeavy(listOf(Box(10, 10, 0, 0)), w, h, 0))
    }

    @Test fun look_scheduler_limits() {
        val s = LookScheduler()
        // not allowed, or not picture-heavy: false and nothing recorded
        assertFalse(s.autoDue("a", true, false, 0))
        assertFalse(s.autoDue("a", false, true, 0))
        assertTrue(s.autoDue("a", true, true, 0))
        // global gap 300 s (any app)
        assertFalse(s.autoDue("b", true, true, 299_999))
        assertTrue(s.autoDue("b", true, true, 300_000))
        // per-app gap 600 s: "a" last looked at 0, "b" at 300 s
        assertFalse(s.autoDue("a", true, true, 599_999))
        assertTrue(s.autoDue("a", true, true, 600_000))
        // a refused check records nothing: "b" is free 600 s after its own look at 300 s,
        // and 300 s after the last look (600 s) of any app
        assertFalse(s.autoDue("b", true, true, 899_999))
        assertTrue(s.autoDue("b", true, true, 900_000))
    }

    @Test fun look_scheduler_refusals_do_not_record() {
        val s = LookScheduler()
        assertFalse(s.autoDue("a", true, false, 1_000))
        assertFalse(s.autoDue("a", false, true, 2_000))
        // had either refusal been recorded, this would be blocked
        assertTrue(s.autoDue("a", true, true, 3_000))
    }

    @Test fun asked_look_gap_20s() {
        val s = LookScheduler()
        assertTrue(s.askedAllowed(0))
        assertFalse(s.askedAllowed(19_999))
        assertTrue(s.askedAllowed(20_000))
        assertFalse(s.askedAllowed(39_999))
        assertTrue(s.askedAllowed(40_000))
    }

    @Test fun asked_and_auto_gaps_are_independent() {
        val s = LookScheduler()
        assertTrue(s.askedAllowed(0))
        assertTrue(s.autoDue("a", true, true, 1_000))
        assertTrue(s.askedAllowed(21_000))
        assertFalse(s.autoDue("a", true, true, 22_000))
    }
}
