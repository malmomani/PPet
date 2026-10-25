package com.maya.core

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test fun encode_app_closed_matches_brain_schema() {
        val ts = Instant.parse("2026-10-24T14:00:00Z")
        val o = Json.parseToJsonElement(Protocol.encode(AppClosed("com.x", "X", 65, id = "abc", ts = ts))).jsonObject
        assertEquals("app_closed", o["type"]!!.jsonPrimitive.content)
        assertEquals("com.x", o["package"]!!.jsonPrimitive.content)
        assertEquals(65L, o["duration_s"]!!.jsonPrimitive.long)
        assertEquals("abc", o["id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-24T14:00:00Z", o["ts"]!!.jsonPrimitive.content)
    }

    @Test fun encode_hello_and_offline_summary() {
        val h = Json.parseToJsonElement(Protocol.encode(Hello("0.1.0", 1080, 2340))).jsonObject
        assertEquals(1080, h["screen_w"]!!.jsonPrimitive.int)
        assertEquals("0.1.0", h["client_version"]!!.jsonPrimitive.content)
        val s = Json.parseToJsonElement(
            Protocol.encode(OfflineSummary(listOf(Screen(false)), Instant.parse("2026-10-24T01:00:00Z")))
        ).jsonObject
        assertEquals("screen", s["events"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("2026-10-24T01:00:00Z", s["since"]!!.jsonPrimitive.content)
    }

    @Test fun every_event_type_string() {
        val types = listOf(
            Hello("1", 1, 1), UserMessage("hi"), UserTap(), UserDrag(true), UserShush(), AppOpened("p", "L"),
            AppClosed("p", "L", 0), Screen(true), Charging(false), OfflineSummary(emptyList(), Instant.EPOCH),
        ).map { Json.parseToJsonElement(Protocol.encode(it)).jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "hello", "user_message", "user_tap", "user_drag", "user_shush", "app_opened",
                "app_closed", "screen", "charging", "offline_summary",
            ),
            types,
        )
    }

    @Test fun decode_actions() {
        assertEquals(
            Say("yo", 6000, "1"),
            Protocol.decodeAction("""{"type":"say","text":"yo","bubble_ms":6000,"id":"1","ts":"2026-10-24T14:00:00Z"}"""),
        )
        assertEquals(Say("yo", 6000, ""), Protocol.decodeAction("""{"type":"say","text":"yo","bubble_ms":6000}"""))
        assertEquals(Animate("wave"), Protocol.decodeAction("""{"type":"animate","name":"wave"}"""))
        assertEquals(
            State("happy", "friend"),
            Protocol.decodeAction("""{"type":"state","mood":"happy","closeness_tier":"friend"}"""),
        )
        assertEquals(
            ErrorMsg("auth", "bad token"),
            Protocol.decodeAction("""{"type":"error","code":"auth","message":"bad token"}"""),
        )
        assertEquals(Typing(true), Protocol.decodeAction("""{"type":"typing","on":true}"""))
    }

    @Test fun decode_unknown_and_malformed_returns_null() {
        assertNull(Protocol.decodeAction("""{"type":"teleport"}"""))
        assertNull(Protocol.decodeAction("{nope"))
        assertNull(Protocol.decodeAction("""{"type":"say"}"""))
        assertNull(Protocol.decodeAction("[1,2]"))
    }

    @Test fun unknown_animation_maps_to_idle() {
        assertEquals(Animate("idle"), Protocol.decodeAction("""{"type":"animate","name":"moonwalk"}"""))
    }

    @Test fun animations_list_order() {
        assertEquals(
            listOf("idle", "walk", "sit", "nap", "wave", "peek", "happy", "sulk", "surprised"),
            ANIMATIONS,
        )
    }

    @Test fun encode_remaining_fields_and_default_id_ts() {
        val e = UserDrag(true)
        val o = Json.parseToJsonElement(Protocol.encode(e)).jsonObject
        assertEquals(true, o["thrown"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(36, o["id"]!!.jsonPrimitive.content.length)
        assertEquals(e.ts, Instant.parse(o["ts"]!!.jsonPrimitive.content))
        val m = Json.parseToJsonElement(Protocol.encode(UserMessage("hi"))).jsonObject
        assertEquals("hi", m["text"]!!.jsonPrimitive.content)
        val a = Json.parseToJsonElement(Protocol.encode(AppOpened("p", "L"))).jsonObject
        assertEquals("L", a["label"]!!.jsonPrimitive.content)
        val c = Json.parseToJsonElement(Protocol.encode(Charging(true))).jsonObject
        assertEquals("true", c["on"]!!.jsonPrimitive.content)
    }

    @Test fun encode_user_message_voice() {
        val v = Json.parseToJsonElement(Protocol.encode(UserMessage("hi", voice = true))).jsonObject
        assertEquals(true, v["voice"]!!.jsonPrimitive.boolean)
        val t = Json.parseToJsonElement(Protocol.encode(UserMessage("hi"))).jsonObject
        assertEquals(false, t["voice"]!!.jsonPrimitive.boolean)
    }

    @Test fun encode_hello_voice_mode() {
        val h = Json.parseToJsonElement(Protocol.encode(Hello("0.1.0", 1080, 2340, voiceMode = "replies"))).jsonObject
        assertEquals("replies", h["voice_mode"]!!.jsonPrimitive.content)
        val d = Json.parseToJsonElement(Protocol.encode(Hello("0.1.0", 1080, 2340))).jsonObject
        assertEquals("off", d["voice_mode"]!!.jsonPrimitive.content)
    }

    @Test fun decode_audio() {
        assertEquals(
            Audio("abc", "audio/wav", "UklGRg=="),
            Protocol.decodeAction("""{"type":"audio","ref_id":"abc","mime":"audio/wav","data_b64":"UklGRg==","id":"1","ts":"2026-10-24T14:00:00Z"}"""),
        )
    }

    @Test fun decode_audio_missing_data_is_null() {
        assertNull(Protocol.decodeAction("""{"type":"audio","ref_id":"abc","mime":"audio/wav"}"""))
        assertNull(Protocol.decodeAction("""{"type":"audio","ref_id":"abc","data_b64":"UklGRg=="}"""))
        assertNull(Protocol.decodeAction("""{"type":"audio","mime":"audio/wav","data_b64":"UklGRg=="}"""))
    }
}
