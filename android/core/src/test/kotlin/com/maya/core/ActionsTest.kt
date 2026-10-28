package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun press(target: String) = Act("press", target = target, id = "p")

private fun node(
    l: Int, t: Int, r: Int, b: Int,
    text: String? = null, description: String? = null, viewId: String? = null,
    clickable: Boolean = true, editable: Boolean = false,
) = UiNode(Box(l, t, r, b), clickable, editable, false, viewId, description, text)

private fun btn(text: String) = node(0, 0, 100, 50, text = text)

class ActionsTest {
    // ---- risk ----

    @Test fun risky_press_needs_confirmation() {
        for (tag in listOf("send", "share", "comment", "follow")) assertTrue(tag, isRisky(press(tag), null))
        for (tag in listOf("like", "play", "search")) assertFalse(tag, isRisky(press(tag), null))
        // whole English words in the target, any case
        for (w in listOf("Send", "post", "PUBLISH", "buy now", "Pay", "order", "checkout", "purchase", "delete", "remove",
            "call", "dial", "share", "subscribe", "follow", "confirm", "submit", "install", "uninstall", "reply")) {
            assertTrue(w, isRisky(press(w), null))
        }
        // Arabic, also with diacritics
        for (w in listOf("إرسال", "ارسال", "نشر", "شراء", "دفع", "حذف", "اتصال", "مشاركة", "متابعة", "اشتراك", "تأكيد", "إِرْسَال")) {
            assertTrue(w, isRisky(press(w), null))
        }
        assertTrue(isRisky(press("زر إرسال"), null))
        assertTrue(isRisky(press("Send message"), null))
        // the definite article "ال" is dropped as well
        assertTrue(isRisky(press("الإرسال"), null))
        assertTrue(isRisky(press("زر الإرسال"), null))
        assertTrue(isRisky(press("الشراء"), null))
        assertTrue(isRisky(press("blue"), btn("الدفع")))
        assertTrue(isRisky(press("الْحَذْف"), null))
        assertFalse(isRisky(press("الزر"), null)) // "the button"
        assertFalse(isRisky(press("ال"), null))
        // not whole words
        assertFalse(isRisky(press("Callback"), null))
        assertFalse(isRisky(press("Ordering info"), null))
        assertFalse(isRisky(press("Recall"), null))
        assertFalse(isRisky(press("Settings"), null))
        assertFalse(isRisky(press("Next"), null))
        // the matched node's label counts too
        assertFalse(isRisky(press("blue button"), btn("OK")))
        assertTrue(isRisky(press("blue button"), btn("Pay $5")))
        assertTrue(isRisky(press("big button"), btn("ارسال")))
        assertFalse(isRisky(press("like"), btn("Like")))
        assertTrue(isRisky(press("like"), btn("Like and Subscribe")))
        assertFalse(isRisky(press("blue button"), null))
    }

    @Test fun risk_looks_at_the_whole_button() {
        val p = press("the blue one")
        // text says one thing, description another: both count
        assertTrue(isRisky(p, node(0, 0, 100, 50, text = "Continue", description = "Confirm payment")))
        assertTrue(isRisky(p, node(0, 0, 100, 50, text = "Pay", description = "Continue")))
        assertFalse(isRisky(p, node(0, 0, 100, 50, text = "Continue", description = "Next step")))
        // the view id tail
        assertTrue(isRisky(p, node(0, 0, 100, 50, text = "OK", viewId = "com.shop:id/buy_button")))
        assertFalse(isRisky(p, node(0, 0, 100, 50, text = "OK", viewId = "com.shop:id/buyer_ok")))
        assertFalse(isRisky(p, node(0, 0, 100, 50, text = "OK", viewId = "com.delete.me:id/ok_button"))) // only the tail
        // a risky tag of the node
        assertTrue(isRisky(p, node(0, 0, 100, 50, viewId = "com.x:id/send")))
        assertTrue(isRisky(p, node(0, 0, 100, 50, description = "Share")))
        // what the user typed in an editable field is not read
        assertFalse(isRisky(p, node(0, 0, 100, 50, text = "send", editable = true)))
        assertFalse(isRisky(p, node(0, 0, 100, 50, text = "send me money", description = "Message", editable = true)))
        assertTrue(isRisky(p, node(0, 0, 100, 50, text = "x", description = "Send", editable = true)))
        // no node, no words: harmless; a non-press is never made risky by a node
        assertFalse(isRisky(p, null))
        assertFalse(isRisky(Act("scroll", dir = "up", id = "s"), node(0, 0, 100, 50, text = "Pay")))
        assertTrue(isRisky(Act("type", text = "hi", id = "t"), null))
    }

    @Test fun arabic_prefixes_and_tatweel() {
        for (w in listOf("للدفع", "بالإرسال", "والإرسال", "وبالإرسال", "وللدفع", "الشراء", "للحذف", "بالدفع", "إرســال", "الإرســال")) {
            assertTrue(w, isRisky(press(w), null))
        }
        // not risky: the same letters in other words
        for (w in listOf("للقراءة", "بالتأكيدات", "والزر", "لل", "وال", "بال", "ولد")) assertFalse(w, isRisky(press(w), null))
        assertTrue(isRisky(press("x"), btn("بالإرسال")))
    }

    @Test fun type_is_always_risky_and_system_kinds_are_not() {
        assertTrue(isRisky(Act("type", text = "hello", id = "t"), null))
        assertTrue(isRisky(Act("type", text = "hello", into = "note", id = "t"), null))
        assertFalse(isRisky(Act("open_app", app = "Call of Duty", id = "o"), null))
        assertFalse(isRisky(Act("media", key = "next", id = "m"), null))
        assertFalse(isRisky(Act("timer", seconds = 60, label = "delete", id = "t"), null))
        assertFalse(isRisky(Act("alarm", hour = 7, minute = 0, id = "a"), null))
        assertFalse(isRisky(Act("volume", dir = "up", id = "v"), null))
        assertFalse(isRisky(Act("web_search", query = "buy shoes", id = "w"), null))
        assertFalse(isRisky(Act("scroll", dir = "down", id = "s"), null))
    }

    // ---- blocked ----

    @Test fun never_list_blocks() {
        val own = "com.maya.app"
        fun why(act: Act, pkg: String?, dnw: Set<String> = emptySet(), locked: Boolean = false, allow: Boolean = true) =
            blockedReason(act, pkg, own, { it in dnw }, locked, allow)

        val scroll = Act("scroll", dir = "down", id = "s")
        assertNull(why(scroll, "com.some.app"))
        assertEquals("not here", why(scroll, "com.android.systemui"))
        assertEquals("not here", why(scroll, "com.android.settings"))
        assertEquals("not here", why(scroll, own))
        assertEquals("not here", why(scroll, "com.bank.x", dnw = setOf("com.bank.x")))
        assertEquals("locked", why(scroll, "com.some.app", locked = true))
        assertEquals("off", why(scroll, "com.some.app", allow = false))
        // the switch wins over everything; locked over a place
        assertEquals("off", why(scroll, own, locked = true, allow = false))
        assertEquals("locked", why(scroll, own, locked = true))

        // press / type / scroll need a pkg
        assertEquals("not here", why(scroll, null))
        assertEquals("not here", why(scroll, ""))
        assertEquals("not here", why(press("send"), "  "))
        assertNull(why(Act("media", key = "next", id = "m"), " "))
        assertEquals("not here", why(press("send"), null))
        assertEquals("not here", why(Act("type", text = "x", id = "t"), null))
        assertNull(why(press("send"), "com.some.app")) // risky is not blocked: it is confirmed

        // system kinds work with no pkg, but not when off or locked
        val system = listOf(
            Act("open_app", app = "YouTube", id = "1"), Act("media", key = "next", id = "2"),
            Act("timer", seconds = 60, id = "3"), Act("alarm", hour = 7, minute = 0, id = "4"),
            Act("volume", dir = "up", id = "5"), Act("web_search", query = "x", id = "6"),
        )
        for (a in system) {
            assertNull(a.kind, why(a, null))
            assertNull(a.kind, why(a, "com.some.app"))
            assertEquals(a.kind, "off", why(a, null, allow = false))
            assertEquals(a.kind, "locked", why(a, null, locked = true))
            // the never list is for what happens inside an app: these work from anywhere (e.g. Maya's own chat)
            assertNull(a.kind, why(a, "com.android.settings"))
            assertNull(a.kind, why(a, "com.android.systemui"))
            assertNull(a.kind, why(a, own))
            assertNull(a.kind, why(a, "com.bank.x", dnw = setOf("com.bank.x")))
        }
        assertEquals(setOf("com.android.systemui", "com.android.settings"), NEVER_PACKAGES)
    }

    // ---- matchApp ----

    @Test fun match_app_cases() {
        val apps = listOf(
            "YouTube Music" to "com.google.android.apps.youtube.music",
            "YouTube" to "com.google.android.youtube",
            "Maps" to "com.google.android.apps.maps",
            "Chrome" to "com.android.chrome",
            "Photos" to "com.google.android.apps.photos",
            "واتساب" to "com.whatsapp",
        )
        assertEquals("com.google.android.youtube", matchApp("youtube", apps)) // exact beats prefix
        assertEquals("com.google.android.youtube", matchApp("YouTube", apps))
        assertEquals("com.google.android.youtube", matchApp("you tube", apps)) // normalised
        assertEquals("com.google.android.youtube", matchApp("youtub", apps)) // prefix, shortest label wins
        assertEquals("com.google.android.youtube", matchApp("yutube", apps)) // typo, similarity
        assertEquals("com.google.android.apps.youtube.music", matchApp("youtube music", apps))
        assertEquals("com.google.android.apps.youtube.music", matchApp("YouTube-Music!", apps))
        assertEquals("com.google.android.apps.maps", matchApp("map", apps))
        assertEquals("com.android.chrome", matchApp("chrom", apps))
        assertEquals("com.whatsapp", matchApp("واتساب", apps))
        assertNull(matchApp("yt music", apps)) // no false match
        // a prefix needs 3+ characters; shorter queries only match exactly
        assertNull(matchApp("m", apps))
        assertNull(matchApp("ma", apps))
        assertNull(matchApp("yo", apps))
        assertEquals("com.google.android.apps.maps", matchApp("map", apps))
        assertEquals("p", matchApp("p", listOf("P" to "p", "Photos" to "photos")))
        assertEquals("ab", matchApp("ab", listOf("Abc" to "abc", "AB" to "ab")))
        assertNull(matchApp("spotify", apps))
        assertNull(matchApp("", apps))
        assertNull(matchApp("!!!", apps))
        assertNull(matchApp("youtube", emptyList()))
        // best similarity wins; below 0.8 never
        val sim = listOf("Calendar" to "cal", "Calender" to "cal2", "Calculator" to "calc")
        assertEquals("cal", matchApp("calendar", sim)) // exact
        assertEquals("cal2", matchApp("calenderr", listOf("Calendar" to "cal", "Calender" to "cal2"))) // 1 edit vs 2
        assertNull(matchApp("camera", listOf("Calendar" to "cal"))) // too far
    }

    // ---- matchLabel ----

    @Test fun match_label_prefers_smallest_clickable() {
        val big = node(0, 0, 1000, 400, text = "Send")
        val small = node(800, 300, 900, 380, text = "Send")
        val notClickable = node(10, 10, 30, 30, text = "Send", clickable = false)
        assertEquals(small, matchLabel("send", listOf(big, notClickable, small)))
        assertEquals(small, matchLabel("Send", listOf(small, big)))

        // tag target: via tagOf (description, view id), not the plain label
        val heart = node(500, 500, 580, 580, description = "Like", viewId = "com.x:id/like_button")
        val liked = node(100, 500, 180, 580, text = "Unlike")
        assertEquals(heart, matchLabel("like", listOf(liked, heart)))
        assertNull(matchLabel("like", listOf(liked)))

        // free label: whole words, case-insensitive; text first, then description
        val next = node(0, 0, 200, 100, text = "Next episode")
        val sub = node(0, 200, 200, 300, description = "Subscribe to channel")
        assertEquals(next, matchLabel("next", listOf(next, sub)))
        assertEquals(next, matchLabel("NEXT EPISODE", listOf(sub, next)))
        assertEquals(sub, matchLabel("subscribe", listOf(next, sub)))
        assertEquals(sub, matchLabel("to channel", listOf(next, sub)))
        assertNull(matchLabel("nex", listOf(next, sub))) // not a whole word
        assertNull(matchLabel("episode next", listOf(next))) // words stay in order
        assertNull(matchLabel("settings", listOf(next, sub)))
        assertNull(matchLabel("next", emptyList()))
        assertNull(matchLabel("   ", listOf(next)))
        // Arabic with marks
        val ar = node(0, 0, 100, 100, text = "إِرْسَال")
        assertEquals(ar, matchLabel("ارسال", listOf(ar)) ?: matchLabel("إرسال", listOf(ar)))
        // what the user typed is not a label
        val typed = node(0, 0, 100, 40, text = "send me money", editable = true)
        assertNull(matchLabel("money", listOf(typed)))
    }
}
