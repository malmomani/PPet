package com.maya.core

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledSpritesTest {
    private val dir = File("../app/src/main/assets/sprites")

    @Test fun bundled_config_covers_all_animations_and_matches_png() {
        val config = parseSpriteConfig(File(dir, "sprites.json").readText())
        ANIMATIONS.forEach { assertTrue("missing $it", it in config.animations) }
        val png = ImageIO.read(File(dir, "maya.png"))
        assertNotNull(png)
        assertEquals(config.frameH * 9, png.height)
        assertTrue(png.width >= config.frameW * config.animations.values.maxOf { it.frames })
        ANIMATIONS.forEachIndexed { i, name -> assertEquals("row of $name", i, config.spec(name).row) }
    }
}
