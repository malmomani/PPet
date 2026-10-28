package com.maya.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Rows used for animation names a sprite pack may not have yet (the roaming art comes later):
 * [SpriteConfig.spec] shows the fallback's row instead, and `idle` when that is missing too.
 */
val ANIMATION_FALLBACKS: Map<String, String> = mapOf(
    "jump" to "surprised",
    "fall" to "surprised",
    "perch" to "sit",
    "poke" to "wave",
    "tease" to "happy",
    "look" to "think",
    "ask" to "listen",
    "type" to "poke",
    "held" to "surprised",
)

/** One row of the sprite sheet: which row, how many frames, how fast, and whether it loops. */
data class AnimSpec(val row: Int, val frames: Int, val fps: Int, val loop: Boolean)

data class SpriteConfig(
    val frameW: Int,
    val frameH: Int,
    val animations: Map<String, AnimSpec>,
) {
    /** Spec for [name]: its own row, else its [ANIMATION_FALLBACKS] row, else `idle`. */
    fun spec(name: String): AnimSpec =
        animations[name] ?: ANIMATION_FALLBACKS[name]?.let { animations[it] } ?: animations.getValue("idle")
}

/**
 * Parses `{"frame_w":32,"frame_h":32,"animations":{"idle":{"row":0,"frames":4,"fps":4,"loop":true},...}}`.
 * `frameWidth`/`frameHeight` are accepted for `frame_w`/`frame_h`; other keys are ignored.
 * Throws [IllegalArgumentException] on malformed input, a missing `idle`, or a non-positive value.
 */
fun parseSpriteConfig(json: String): SpriteConfig {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid sprite config: ${e.message}", e)
    }
    val frameW = root.positiveInt(if ("frame_w" in root) "frame_w" else "frameWidth")
    val frameH = root.positiveInt(if ("frame_h" in root) "frame_h" else "frameHeight")
    val anims = try {
        root["animations"]?.jsonObject
    } catch (e: Exception) {
        null
    } ?: throw IllegalArgumentException("sprite config: missing animations")
    val specs = LinkedHashMap<String, AnimSpec>()
    for ((name, el) in anims) {
        val o = try {
            el.jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("sprite config: animation $name is not an object")
        }
        val loop = (o["loop"] as? JsonPrimitive)?.booleanOrNull
            ?: throw IllegalArgumentException("sprite config: $name.loop missing")
        val row = o.int("row")
        require(row >= 0) { "sprite config: $name.row must be >= 0" }
        specs[name] = AnimSpec(row, o.positiveInt("frames"), o.positiveInt("fps"), loop)
    }
    require("idle" in specs) { "sprite config: idle animation is required" }
    return SpriteConfig(frameW, frameH, specs)
}

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull
        ?: throw IllegalArgumentException("sprite config: $key missing or not an integer")

private fun JsonObject.positiveInt(key: String): Int =
    int(key).also { require(it > 0) { "sprite config: $key must be > 0" } }
