package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpriteConfigTest {
    @Test fun parse_sprite_config_and_fallback() {
        val c = parseSpriteConfig("""{"frame_w":32,"frame_h":32,"animations":{"idle":{"row":0,"frames":4,"fps":4,"loop":true},"wave":{"row":5,"frames":6,"fps":8,"loop":false}}}""")
        assertEquals(32, c.frameW); assertEquals(32, c.frameH)
        assertEquals(AnimSpec(5, 6, 8, false), c.spec("wave")); assertEquals(AnimSpec(0, 4, 4, true), c.spec("moonwalk"))
        assertThrows(IllegalArgumentException::class.java) { parseSpriteConfig("""{"frame_w":32,"frame_h":32,"animations":{}}""") }
    }

    @Test fun non_positive_values_are_rejected() {
        val bad = """{"frame_w":32,"frame_h":32,"animations":{"idle":{"row":0,"frames":0,"fps":4,"loop":true}}}"""
        assertThrows(IllegalArgumentException::class.java) { parseSpriteConfig(bad) }
        val badFrame = """{"frame_w":0,"frame_h":32,"animations":{"idle":{"row":0,"frames":4,"fps":4,"loop":true}}}"""
        assertThrows(IllegalArgumentException::class.java) { parseSpriteConfig(badFrame) }
    }

    @Test fun malformed_json_is_an_illegal_argument() {
        assertThrows(IllegalArgumentException::class.java) { parseSpriteConfig("not json") }
    }

    @Test fun accepts_camel_case_keys_and_ignores_extras() {
        val c = parseSpriteConfig("""{"name":"Maya","frameWidth":64,"frameHeight":64,"columns":8,
            "anchor":{"x":32,"y":60},"animations":{"idle":{"row":0,"frames":4,"fps":4,"loop":true,
            "onComplete":"repeat","cells":[{"x":0,"y":0,"w":64,"h":64}]}}}""")
        assertEquals(64, c.frameW); assertEquals(64, c.frameH); assertEquals(AnimSpec(0, 4, 4, true), c.spec("idle"))
    }
}

