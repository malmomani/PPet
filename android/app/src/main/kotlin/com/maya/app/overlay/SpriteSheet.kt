package com.maya.app.overlay

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.maya.core.AnimSpec
import com.maya.core.SpriteConfig
import com.maya.core.parseSpriteConfig
import org.json.JSONException
import org.json.JSONObject

/**
 * Maya's sprite sheet: `sprites/maya.png` (one row per animation, frames left to right) and the
 * `sprites/sprites.json` that describes it. Throws if either asset is missing or malformed.
 *
 * Animations the pack has no row for yet (the roaming `jump`, `fall`, `perch`, `poke`, `tease`)
 * are drawn with core's fallback rows ([SpriteConfig.spec]); a row the json names but the png is
 * too short for counts as missing too.
 */
class SpriteSheet(assets: AssetManager) {
    val bitmap: Bitmap
    val config: SpriteConfig

    /** The feet row within a frame (`anchor.y` in the json, 60 of 64), or null if absent or invalid. */
    val anchorY: Int?

    init {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        bitmap = assets.open(PNG).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("could not decode $PNG")
        val json = assets.open(JSON).bufferedReader().use { it.readText() }
        val parsed = parseSpriteConfig(json)
        val rows = bitmap.height / parsed.frameH
        val drawable = parsed.animations.filterValues { it.row < rows }
        // Without a drawable idle there is nothing sensible to fall back to: keep the json as is.
        config = if ("idle" in drawable) parsed.copy(animations = drawable) else parsed
        anchorY = anchorY(json, parsed.frameH)
    }

    val frameW: Int get() = config.frameW
    val frameH: Int get() = config.frameH

    /** Spec for [name]: its own row, else its fallback's row (e.g. `jump` -> `surprised`), else `idle`. */
    fun spec(name: String): AnimSpec = config.spec(name)

    /** Source rect of frame [index] (clamped to the animation's frames) of animation [name] (or its fallback). */
    fun frame(name: String, index: Int): Rect {
        val s = config.spec(name)
        val i = index.coerceIn(0, s.frames - 1)
        val left = i * config.frameW
        val top = s.row * config.frameH
        return Rect(left, top, left + config.frameW, top + config.frameH)
    }

    internal companion object {
        const val PNG = "sprites/maya.png"
        const val JSON = "sprites/sprites.json"

        /** `anchor.y` from the sprite json if it is an integer inside the frame (1..[frameH]). */
        fun anchorY(json: String, frameH: Int): Int? = try {
            val anchor = JSONObject(json).optJSONObject("anchor")
            val y = if (anchor != null && anchor.has("y")) anchor.getInt("y") else null
            y?.takeIf { it in 1..frameH }
        } catch (e: JSONException) {
            null
        }
    }
}
