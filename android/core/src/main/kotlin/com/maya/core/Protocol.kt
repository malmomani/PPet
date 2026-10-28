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

/** Someone contacted the user: who and where only, never the text. [kind] is message|email|call. */
data class Notification(
    val packageName: String,
    val app: String,
    val sender: String,
    val group: String? = null,
    val kind: String = "message",
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

/** The visible text of the app the user is in, plus which tease targets it shows. Never queued offline. */
data class Glance(
    val packageName: String,
    val app: String,
    val lines: List<String>,
    val targets: List<String>,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

/**
 * What her body is doing: [doing] idle|walk|sit|perch|jump|fall|nap|peek|tease|dragged, [where]
 * floor|platform|target; [x]/[y] are her feet as fractions of the screen. Never queued offline.
 */
data class Body(
    val doing: String,
    val where: String,
    val target: String?,
    val x: Double,
    val y: Double,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

/** Something happened to her body: [kind] is `fell` or `shooed`. Never queued offline. */
data class BodyMoment(
    val kind: String,
    val target: String? = null,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

/**
 * A screenshot of the app the user is in, as base64 JPEG ([imageB64]); [reason] is `auto` or `asked`.
 * RAM only, never queued offline.
 */
data class Sight(
    val packageName: String,
    val app: String,
    val reason: String,
    val imageB64: String,
    override val id: String = newId(),
    override val ts: Instant = Instant.now(),
) : Event

/** An asked look that could not happen: [reason] is `blocked` or `error`. Never queued offline. */
data class SightFailed(
    val reason: String,
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

/** [id] is the brain message id (matched by a later [Audio.refId]); "" when absent. */
data class Say(val text: String, val bubbleMs: Long, val id: String = "") : Action

data class Animate(val name: String) : Action

data class State(val mood: String, val closenessTier: String) : Action

data class Typing(val on: Boolean) : Action

data class Audio(val refId: String, val mime: String, val dataB64: String) : Action

data class ErrorMsg(val code: String, val message: String) : Action

/** [intent] is one of [MOVE_INTENTS]; [target] is a tease tag, always set for [TEASE_INTENTS]. */
data class Move(val intent: String, val target: String? = null) : Action

/** The brain asks her to look at the screen now; [id] is the brain message id, "" when absent. */
data class Look(val id: String = "") : Action

/** Moves the brain can ask for (same as the brain). [TEASE_INTENTS] need a target. */
val MOVE_INTENTS: List<String> = listOf(
    "stay", "wander", "come_closer", "go_left", "go_right", "go_up", "go_down",
    "sit", "nap", "peek", "sit_on", "poke",
)
val TEASE_INTENTS: List<String> = listOf("sit_on", "poke")

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
            is Notification -> {
                put("package", e.packageName)
                put("app", e.app)
                put("sender", e.sender)
                put("group", e.group)
                put("kind", e.kind)
            }
            is Glance -> {
                put("package", e.packageName)
                put("app", e.app)
                put("lines", JsonArray(e.lines.map { JsonPrimitive(it) }))
                put("targets", JsonArray(e.targets.map { JsonPrimitive(it) }))
            }
            is Body -> {
                put("doing", e.doing)
                put("where", e.where)
                put("target", e.target)
                put("x", unit(e.x))
                put("y", unit(e.y))
            }
            is BodyMoment -> {
                put("kind", e.kind)
                put("target", e.target)
            }
            is Sight -> {
                put("package", e.packageName)
                put("app", e.app)
                put("reason", e.reason)
                put("image_b64", e.imageB64)
            }
            is SightFailed -> put("reason", e.reason)
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
        is Notification -> "notification"
        is Glance -> "glance"
        is Body -> "body"
        is BodyMoment -> "body_moment"
        is Sight -> "sight"
        is SightFailed -> "sight_failed"
        is OfflineSummary -> "offline_summary"
    }

    /** A fraction of the screen: clamped to 0..1 and rounded to 2 decimals. */
    private fun unit(v: Double): Double = Math.round(v.coerceIn(0.0, 1.0) * 100) / 100.0

    /** Returns null for unknown types, malformed JSON, or missing/invalid required fields. */
    fun decodeAction(text: String): Action? = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        if (o == null) null else decodeObject(o)
    } catch (_: Exception) {
        null
    }

    private fun decodeObject(o: JsonObject): Action? {
        return when (o.str("type")) {
            "say" -> Say(
                o.str("text") ?: return null,
                o.long("bubble_ms") ?: return null,
                o.str("id") ?: "",
            )
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
            "move" -> {
                val intent = o.str("intent")?.takeIf { it in MOVE_INTENTS } ?: return null
                val target = o.str("target")?.takeIf { it in TEASE_TAGS }
                if (intent in TEASE_INTENTS && target == null) return null
                Move(intent, target)
            }
            "look" -> Look(o.str("id") ?: "")
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
