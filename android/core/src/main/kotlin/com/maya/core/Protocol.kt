package com.maya.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Animation names the sprite sheet supports (same order as the brain). */
val ANIMATIONS: List<String> =
    listOf("idle", "walk", "sit", "nap", "wave", "peek", "happy", "sulk", "surprised")

fun newId(): String = UUID.randomUUID().toString()

// ---- Events (phone -> brain) ----

sealed interface Event {
    val id: String
    val ts: Instant
}

data class Hello(
    val clientVersion: String,
    val screenW: Int,
    val screenH: Int,
    val voiceMode: String = "off",
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class UserMessage(
    val text: String,
    val voice: Boolean = false,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class UserTap(
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class UserDrag(
    val thrown: Boolean,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class UserShush(
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class AppOpened(
    val packageName: String,
    val label: String,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class AppClosed(
    val packageName: String,
    val label: String,
    val durationS: Long,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class Screen(
    val on: Boolean,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class Charging(
    val on: Boolean,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

data class OfflineSummary(
    val events: List<Event>,
    val since: Instant,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

// ---- Actions (brain -> phone) ----

sealed interface Action

data class Say(val text: String, val bubbleMs: Long) : Action

data class Animate(val name: String) : Action

data class State(val mood: String, val closenessTier: String) : Action

data class Typing(val on: Boolean) : Action

data class Audio(val refId: String, val mime: String, val dataB64: String) : Action

data class ErrorMsg(val code: String, val message: String) : Action

object Protocol {
    private fun fmt(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t)

    fun encode(e: Event): String = encodeObject(e).toString()

    fun encodeObject(e: Event): JsonObject = buildJsonObject {
        put("type", typeOf(e))
        put("id", e.id)
        put("ts", fmt(e.ts))
        when (e) {
            is Hello -> {
                put("client_version", e.clientVersion)
                put("screen_w", e.screenW)
                put("screen_h", e.screenH)
                put("voice_mode", e.voiceMode)
            }
            is UserMessage -> {
                put("text", e.text)
                put("voice", e.voice)
            }
            is UserTap -> Unit
            is UserDrag -> put("thrown", e.thrown)
            is UserShush -> Unit
            is AppOpened -> {
                put("package", e.packageName)
                put("label", e.label)
            }
            is AppClosed -> {
                put("package", e.packageName)
                put("label", e.label)
                put("duration_s", e.durationS)
            }
            is Screen -> put("on", e.on)
            is Charging -> put("on", e.on)
            is OfflineSummary -> {
                put("events", JsonArray(e.events.map { encodeObject(it) }))
                put("since", fmt(e.since))
            }
        }
    }

    private fun typeOf(e: Event): String = when (e) {
        is Hello -> "hello"
        is UserMessage -> "user_message"
        is UserTap -> "user_tap"
        is UserDrag -> "user_drag"
        is UserShush -> "user_shush"
        is AppOpened -> "app_opened"
        is AppClosed -> "app_closed"
        is Screen -> "screen"
        is Charging -> "charging"
        is OfflineSummary -> "offline_summary"
    }

    /** Returns null for unknown types, malformed JSON, or missing/invalid required fields. */
    fun decodeAction(text: String): Action? = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        if (o == null) null else decodeObject(o)
    } catch (_: Exception) {
        null
    }

    private fun decodeObject(o: JsonObject): Action? {
        return when (o.str("type")) {
            "say" -> Say(o.str("text") ?: return null, o.long("bubble_ms") ?: return null)
            "animate" -> {
                val name = o.str("name") ?: return null
                Animate(if (name in ANIMATIONS) name else "idle")
            }
            "state" -> State(o.str("mood") ?: return null, o.str("closeness_tier") ?: return null)
            "typing" -> Typing(o.bool("on") ?: return null)
            "audio" -> Audio(
                o.str("ref_id") ?: return null,
                o.str("mime") ?: return null,
                o.str("data_b64") ?: return null,
            )
            "error" -> ErrorMsg(o.str("code") ?: return null, o.str("message") ?: return null)
            else -> null
        }
    }

    private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonObject.str(key: String): String? {
        val p = this[key].prim() ?: return null
        return if (p.isString) p.contentOrNull else null
    }

    private fun JsonObject.long(key: String): Long? = this[key].prim()?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = this[key].prim()?.takeIf { !it.isString }?.booleanOrNull
}
