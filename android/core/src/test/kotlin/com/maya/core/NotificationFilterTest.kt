package com.maya.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationFilterTest {
    private val t0 = Instant.parse("2026-10-25T09:00:00Z")

    private fun info(
        pkg: String = "com.wa",
        app: String = "WhatsApp",
        category: String? = "msg",
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        foregroundService: Boolean = false,
        messagingStyle: Boolean = false,
        title: String? = "Sara",
        styleSender: String? = null,
        conversationTitle: String? = null,
        isGroup: Boolean = false,
        at: Instant = t0,
    ) = NotificationInfo(
        pkg, app, category, ongoing, groupSummary, foregroundService, messagingStyle,
        title, styleSender, conversationTitle, isGroup, at,
    )

    private fun filter(blocked: Set<String> = emptySet()) =
        NotificationFilter(isBlocked = { it in blocked }, ownPackage = "com.maya.app")

    @Test fun accepts_message_category() {
        val n = filter().accept(info())!!
        assertEquals("com.wa", n.packageName)
        assertEquals("WhatsApp", n.app)
        assertEquals("Sara", n.sender)
        assertNull(n.group)
        assertEquals("message", n.kind)
    }

    @Test fun accepts_messaging_style_without_category() {
        assertEquals("message", filter().accept(info(category = null, messagingStyle = true))!!.kind)
        assertEquals("message", filter().accept(info(category = "social", messagingStyle = true))!!.kind)
    }

    @Test fun email_and_missed_call_kinds() {
        assertEquals("email", filter().accept(info(pkg = "com.g", category = "email"))!!.kind)
        assertEquals("call", filter().accept(info(pkg = "com.d", category = "missed_call"))!!.kind)
        assertEquals("call", filter().accept(info(pkg = "com.e", category = "call"))!!.kind)
    }

    @Test fun ignores_ongoing_summary_fgs_own_blocked_and_other_categories() {
        assertNull(filter().accept(info(ongoing = true)))
        assertNull(filter().accept(info(groupSummary = true)))
        assertNull(filter().accept(info(foregroundService = true)))
        assertNull(filter().accept(info(pkg = "com.maya.app")))
        assertNull(filter(blocked = setOf("com.wa")).accept(info()))
        assertNull(filter().accept(info(category = "promo")))
        assertNull(filter().accept(info(category = "social")))
        assertNull(filter().accept(info(category = null)))
        assertNull(filter().accept(info(category = "msg", ongoing = true, messagingStyle = true)))
    }

    @Test fun sender_from_style_then_title_and_group_title() {
        assertEquals("Ann", filter().accept(info(styleSender = "Ann", title = "Family"))!!.sender)
        assertEquals("Sara", filter().accept(info(styleSender = null, title = "Sara"))!!.sender)
        val g = filter().accept(info(styleSender = " Ann ", conversationTitle = "Family", isGroup = true))!!
        assertEquals("Ann", g.sender)
        assertEquals("Family", g.group)
        // conversation title is ignored when it is not a group conversation
        assertNull(filter().accept(info(conversationTitle = "Sara", isGroup = false))!!.group)
        // group conversation without a title -> null group
        assertNull(filter().accept(info(conversationTitle = null, isGroup = true))!!.group)
        assertNull(filter().accept(info(conversationTitle = "  ", isGroup = true))!!.group)
    }

    @Test fun blank_sender_ignored_and_long_names_truncated() {
        assertNull(filter().accept(info(title = null)))
        assertNull(filter().accept(info(title = "   ")))
        val n = filter().accept(
            info(app = "A".repeat(150), title = "S".repeat(150), conversationTitle = "G".repeat(150), isGroup = true),
        )!!
        assertEquals(100, n.sender.length)
        assertEquals(100, n.group!!.length)
        assertEquals(100, n.app.length)
        val emoji = filter().accept(info(title = "S".repeat(99) + "\uD83D\uDE00"))!!
        assertEquals("S".repeat(99), emoji.sender)
    }

    @Test fun dedupe_window() {
        val f = filter()
        assertNotNull(f.accept(info(at = t0)))
        assertNull(f.accept(info(at = t0.plusSeconds(119))))
        assertNotNull(f.accept(info(title = "Bob", at = t0.plusSeconds(10))))
        assertNotNull(f.accept(info(conversationTitle = "Fam", isGroup = true, at = t0.plusSeconds(10))))
        assertNotNull(f.accept(info(pkg = "com.tg", at = t0.plusSeconds(10))))
        assertNotNull(f.accept(info(at = t0.plusSeconds(120))))
        // a dropped duplicate does not extend the window
        assertNull(f.accept(info(at = t0.plusSeconds(200))))
        assertNotNull(f.accept(info(at = t0.plusSeconds(241))))
    }

    @Test fun dedupe_state_is_pruned() {
        val f = filter()
        for (i in 0 until 500) assertNotNull(f.accept(info(title = "s$i", at = t0)))
        assertNotNull(f.accept(info(title = "late", at = t0.plusSeconds(1000))))
        assertNotNull(f.accept(info(title = "s0", at = t0.plusSeconds(1001))))
    }
}
