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

    @Test fun fallback_rows() {
        assertEquals(
            mapOf(
                "jump" to "surprised", "fall" to "surprised", "perch" to "sit", "poke" to "wave", "tease" to "happy",
                "look" to "think", "ask" to "listen", "type" to "poke", "held" to "surprised",
            ),
            ANIMATION_FALLBACKS,
        )
        val anim = { row: Int -> """{"row":$row,"frames":4,"fps":4,"loop":true}""" }
        val c = parseSpriteConfig("""{"frame_w":64,"frame_h":64,"animations":{"idle":${anim(0)},"sit":${anim(2)},""" +
            """"wave":${anim(4)},"surprised":${anim(8)},"jump":${anim(9)}}}""")
        assertEquals(9, c.spec("jump").row) // its own row wins
        assertEquals(8, c.spec("fall").row)
        assertEquals(2, c.spec("perch").row)
        assertEquals(4, c.spec("poke").row)
        assertEquals(0, c.spec("tease").row) // fallback (happy) missing too: idle
        assertEquals(0, c.spec("moonwalk").row)
        assertEquals(8, c.spec("held").row) // held -> surprised
        assertEquals(0, c.spec("look").row) // think missing: idle
        assertEquals(0, c.spec("type").row) // one level only: poke's own fallback (wave) is not followed
        val withPoses = parseSpriteConfig("""{"frame_w":64,"frame_h":64,"animations":{"idle":${anim(0)},""" +
            """"listen":${anim(10)},"think":${anim(11)},"poke":${anim(12)}}}""")
        assertEquals(11, withPoses.spec("look").row)
        assertEquals(10, withPoses.spec("ask").row)
        assertEquals(12, withPoses.spec("type").row)
    }
}
