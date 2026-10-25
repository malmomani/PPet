package com.maya.app.overlay

import android.content.Context
import android.util.Log
import com.maya.core.parseSpriteConfig
import kotlin.math.roundToInt

/**
 * Maya's on-screen size in pixels: one sprite frame drawn at the integer nearest-neighbour
 * [scale] that makes it about [SPRITE_DP] tall (64 px art -> x3 at density 3). This is the same
 * rule OverlayBody uses for its window, so code that never draws her (the accessibility service
 * building platforms) agrees with the body on how much room she needs.
 */
class SpriteMetrics(val scale: Int, val width: Int, val height: Int) {
    companion object {
        /** About how tall she is drawn (OverlayBody's SPRITE_DP). */
        const val SPRITE_DP = 64f

        private const val TAG = "MayaSprite"

        /** The integer scale for frames [frameH] px tall at [density]; at least 1. */
        fun scaleFor(density: Float, frameH: Int): Int = (SPRITE_DP * density / frameH).roundToInt().coerceAtLeast(1)

        /**
         * Reads only `sprites.json` (not the bitmap). If it is missing or malformed she is assumed
         * to be a [SPRITE_DP] square.
         */
        fun forContext(ctx: Context): SpriteMetrics {
            val density = ctx.resources.displayMetrics.density
            return try {
                val json = ctx.assets.open(SpriteSheet.JSON).bufferedReader().use { it.readText() }
                val config = parseSpriteConfig(json)
                val scale = scaleFor(density, config.frameH)
                SpriteMetrics(scale, config.frameW * scale, config.frameH * scale)
            } catch (e: Exception) {
                Log.w(TAG, "no sprite config: ${e.javaClass.simpleName}")
                val px = (SPRITE_DP * density).roundToInt().coerceAtLeast(1)
                SpriteMetrics(1, px, px)
            }
        }
    }
}
