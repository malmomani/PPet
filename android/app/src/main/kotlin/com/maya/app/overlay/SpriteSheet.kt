package com.maya.app.overlay

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.maya.core.AnimSpec
import com.maya.core.SpriteConfig
import com.maya.core.parseSpriteConfig

/**
 * Maya's sprite sheet: `sprites/maya.png` (one row per animation, frames left to right) and the
 * `sprites/sprites.json` that describes it. Throws if either asset is missing or malformed.
 */
class SpriteSheet(assets: AssetManager) {
    val bitmap: Bitmap
    val config: SpriteConfig

    init {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        bitmap = assets.open(PNG).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("could not decode $PNG")
        config = parseSpriteConfig(assets.open(JSON).bufferedReader().use { it.readText() })
    }

    val frameW: Int get() = config.frameW
    val frameH: Int get() = config.frameH

    /** Spec for [name]; unknown names fall back to `idle`. */
    fun spec(name: String): AnimSpec = config.spec(name)

    /** Source rect of frame [index] (clamped to the animation's frames) of animation [name]. */
    fun frame(name: String, index: Int): Rect {
        val s = config.spec(name)
        val i = index.coerceIn(0, s.frames - 1)
        val left = i * config.frameW
        val top = s.row * config.frameH
        return Rect(left, top, left + config.frameW, top + config.frameH)
    }

    private companion object {
        const val PNG = "sprites/maya.png"
        const val JSON = "sprites/sprites.json"
    }
}
