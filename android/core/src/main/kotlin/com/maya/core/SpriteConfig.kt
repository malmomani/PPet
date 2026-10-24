package com.maya.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One row of the sprite sheet: which row, how many frames, how fast, and whether it loops. */
data class AnimSpec(val row: Int, val frames: Int, val fps: Int, val loop: Boolean)

data class SpriteConfig(
    val frameW: Int,
    val frameH: Int,
    val animations: Map<String, AnimSpec>,
) {
    /** Spec for [name]; unknown names fall back to `idle`. */
    fun spec(name: String): AnimSpec = animations[name] ?: animations.getValue("idle")
}

/**
 * Parses `{"frame_w":32,"frame_h":32,"animations":{"idle":{"row":0,"frames":4,"fps":4,"loop":true},...}}`.
 * Throws [IllegalArgumentException] on malformed input, a missing `idle`, or a non-positive value.
 */
fun parseSpriteConfig(json: String): SpriteConfig {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid sprite config: ${e.message}", e)
    }
    val frameW = root.positiveInt("frame_w")
    val frameH = root.positiveInt("frame_h")
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
