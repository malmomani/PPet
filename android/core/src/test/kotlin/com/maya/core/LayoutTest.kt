package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun node(
        box: Box,
        clickable: Boolean = false,
        editable: Boolean = false,
        image: Boolean = false,
        viewId: String? = null,
        description: String? = null,
        text: String? = null,
    ) = UiNode(box, clickable, editable, image, viewId, description, text)

    private fun build(nodes: List<UiNode>, keyboardTop: Int? = null) =
        buildLayout(nodes, SCREEN_W, SPRITE_W, SPRITE_H, STATUS_H, keyboardTop, MIN_IMG)

    @Test fun box_geometry() {
        val b = Box(10, 20, 110, 70)
        assertEquals(100, b.width)
        assertEquals(50, b.height)
        assertEquals(60, b.centerX)
    }

    @Test fun tease_tags_order() {
        assertEquals(listOf("send", "like", "play", "search", "share", "comment", "follow"), TEASE_TAGS)
    }

    @Test fun tag_words_en_ar() {
        val b = Box(0, 0, 10, 10)
        assertEquals("send", tagOf(node(b, clickable = true, viewId = "com.whatsapp:id/send")))
        assertEquals("like", tagOf(node(b, clickable = true, description = "Like")))
        assertNull(tagOf(node(b, clickable = true, description = "Unlike")))
        assertEquals("send", tagOf(node(b, clickable = true, text = "إرسال")))
        assertEquals("send", tagOf(node(b, clickable = true, text = "ارسال")))
    }

    @Test fun tag_words_whole_words_only() {
        val b = Box(0, 0, 10, 10)
        assertNull(tagOf(node(b, text = "Unfollow")))
        assertNull(tagOf(node(b, text = "Playlist")))
        assertNull(tagOf(node(b, text = "likelihood")))
        assertNull(tagOf(node(b, viewId = "com.x:id/unlike_button")))
        assertNull(tagOf(node(b, viewId = "com.x:id/sender_name")))
        // view ids: only the part after the last '/', split on '_'
        assertEquals("send", tagOf(node(b, viewId = "com.x:id/btn_send")))
        assertNull(tagOf(node(b, viewId = "com.send.app:id/avatar")))
        // punctuation splits words
        assertEquals("share", tagOf(node(b, text = "Share!")))
        assertEquals("comment", tagOf(node(b, description = "Add a comment...")))
        assertEquals("follow", tagOf(node(b, text = "FOLLOW")))
        assertEquals("search", tagOf(node(b, text = "بحث")))
        assertEquals("like", tagOf(node(b, text = "أعجبني")))
        assertEquals("follow", tagOf(node(b, text = "متابعة")))
    }

    @Test fun tag_word_lists() {
        val b = Box(0, 0, 10, 10)
        val cases = mapOf(
            "send" to listOf("send", "إرسال", "ارسال"),
            "like" to listOf("like", "heart", "favorite", "favourite", "love", "أعجبني", "إعجاب"),
            "play" to listOf("play"),
            "search" to listOf("search", "بحث"),
            "share" to listOf("share", "مشاركة"),
            "comment" to listOf("comment", "reply", "تعليق"),
            "follow" to listOf("follow", "subscribe", "متابعة"),
        )
        for ((tag, words) in cases) for (w in words) assertEquals("$tag/$w", tag, tagOf(node(b, text = w)))
    }

    @Test fun tag_first_in_tease_order_wins() {
        val b = Box(0, 0, 10, 10)
        assertEquals("send", tagOf(node(b, text = "Follow", description = "send")))
        assertEquals("like", tagOf(node(b, text = "Share and like")))
        assertEquals("play", tagOf(node(b, viewId = "x:id/play", text = "search")))
    }

    @Test fun tag_ignores_editable_text() {
        val b = Box(0, 0, 10, 10)
        assertNull(tagOf(node(b, editable = true, text = "please send money")))
        assertEquals("search", tagOf(node(b, editable = true, viewId = "x:id/search_box")))
    }

    @Test fun targets_from_clickables_only() {
        val btn = Box(900, 1000, 1000, 1100)
        val layout = build(
            listOf(
                node(btn, clickable = true, description = "Send"),
                node(Box(0, 300, 200, 360), clickable = false, text = "Send me a message"),
                node(Box(0, 500, 90, 590), clickable = true, text = "Like"),
                node(Box(0, 700, 90, 790), clickable = true, text = "Open"),
            ),
        )
        assertEquals(
            listOf(Target("like", Box(0, 500, 90, 590)), Target("send", btn)),
            layout.targets.sortedBy { it.bounds.top },
        )
        assertEquals(2, layout.targets.size)
    }

    @Test fun platforms_filters() {
        val ok = Box(100, 600, 500, 700)
        val layout = build(
            listOf(
                node(ok, clickable = true),
                // too narrow (needs >= 0.6 * spriteW = 30)
                node(Box(0, 800, 29, 900), clickable = true),
                // no room above: top - spriteH < statusBarH  (150 - 100 < 80)
                node(Box(100, 150, 500, 250), clickable = true),
                // below the keyboard
                node(Box(100, 1700, 500, 1800), clickable = true),
                // plain, inert, non-image nodes never make a platform
                node(Box(100, 900, 500, 1000)),
                // images below minImagePx
                node(Box(0, 1100, 47, 1147), image = true),
            ),
            keyboardTop = 1600,
        )
        assertEquals(listOf(Platform(100, 500, 600)), layout.platforms)
    }

    @Test fun platforms_room_above_boundary_and_keyboard_boundary() {
        // top - spriteH == statusBarH is allowed
        val edge = Box(0, STATUS_H + SPRITE_H, 100, STATUS_H + SPRITE_H + 50)
        assertEquals(1, build(listOf(node(edge, clickable = true))).platforms.size)
        val tooHigh = Box(0, STATUS_H + SPRITE_H - 1, 100, STATUS_H + SPRITE_H + 49)
        assertEquals(0, build(listOf(node(tooHigh, clickable = true))).platforms.size)
        // top at keyboardTop is excluded, just above it is kept
        val kb = Box(0, 1500, 100, 1550)
        assertEquals(0, build(listOf(node(kb, clickable = true)), keyboardTop = 1500).platforms.size)
        assertEquals(1, build(listOf(node(kb, clickable = true)), keyboardTop = 1501).platforms.size)
        assertEquals(1, build(listOf(node(kb, clickable = true)), keyboardTop = null).platforms.size)
    }

    @Test fun platforms_kinds() {
        val layout = build(
            listOf(
                node(Box(0, 400, 200, 460), editable = true),
                node(Box(0, 600, 200, 700), image = true),
                node(Box(0, 800, 200, 900), image = true, clickable = false),
                node(Box(0, 1000, 40, 1040), image = true), // 40 < MIN_IMG (48)
            ),
        )
        assertEquals(listOf(400, 600, 800), layout.platforms.map { it.top }.sorted())
    }

    @Test fun platforms_clamped_to_screen() {
        val layout = build(listOf(node(Box(-300, 500, 29, 600), clickable = true)))
        // only 29 px of it is on screen: narrower than 30
        assertTrue(layout.platforms.isEmpty())
        val l2 = build(listOf(node(Box(-300, 500, 400, 600), clickable = true)))
        assertEquals(listOf(Platform(0, 400, 500)), l2.platforms)
        val l3 = build(listOf(node(Box(900, 500, 2000, 600), clickable = true)))
        assertEquals(listOf(Platform(900, SCREEN_W, 500)), l3.platforms)
    }

    @Test fun platforms_merge_and_cap() {
        // same top +-4 and overlapping x -> one spanning platform
        val merged = build(
            listOf(
                node(Box(100, 600, 300, 700), clickable = true),
                node(Box(250, 603, 500, 700), clickable = true),
            ),
        )
        assertEquals(listOf(Platform(100, 500, 600)), merged.platforms)

        // adjacent (touching) edges merge too
        val adjacent = build(
            listOf(
                node(Box(100, 600, 300, 700), clickable = true),
                node(Box(300, 604, 500, 700), clickable = true),
            ),
        )
        assertEquals(listOf(Platform(100, 500, 600)), adjacent.platforms)

        // a gap, or a different y (> 4 px), keeps them apart
        val gap = build(
            listOf(
                node(Box(100, 600, 300, 700), clickable = true),
                node(Box(301, 600, 500, 700), clickable = true),
                node(Box(100, 605, 300, 700), clickable = true),
            ),
        )
        assertEquals(3, gap.platforms.size)

        // merging keeps a target's tag, whichever side it is on
        val tagged = build(
            listOf(
                node(Box(100, 600, 300, 700), clickable = true),
                node(Box(250, 600, 500, 700), clickable = true, description = "Send"),
            ),
        )
        assertEquals(listOf(Platform(100, 500, 600, "send")), tagged.platforms)
        val tagged2 = build(
            listOf(
                node(Box(100, 600, 300, 700), clickable = true, text = "Like"),
                node(Box(250, 600, 500, 700), clickable = true),
            ),
        )
        assertEquals(listOf(Platform(100, 500, 600, "like")), tagged2.platforms)

        // a single target node makes a tagged platform; untagged ones have no tag
        val single = build(listOf(node(Box(100, 600, 300, 700), clickable = true, text = "Share")))
        assertEquals("share", single.platforms.single().tag)

        // at most 30 platforms, the widest kept, widest first
        val many = (0 until 40).map { i ->
            node(Box(0, 300 + i * 20, 100 + i * 10, 310 + i * 20), clickable = true)
        }
        val capped = build(many).platforms
        assertEquals(30, capped.size)
        assertEquals((10 until 40).map { 100 + it * 10 }.reversed(), capped.map { it.right })
    }

    @Test fun long_card_text_not_tagged() {
        val card = node(Box(0, 500, 500, 700), clickable = true, text = "Video: best play of the day")
        assertNull(tagOf(card))
        assertTrue(build(listOf(card)).targets.isEmpty())
        // more than 4 words, even if short in chars
        assertNull(tagOf(node(Box(0, 0, 10, 10), text = "a b c d play")))
        assertEquals("play", tagOf(node(Box(0, 0, 10, 10), text = "a b c play")))
        // more than 24 chars in a description
        assertNull(tagOf(node(Box(0, 0, 10, 10), description = "Play the whole playlist now")))
        assertEquals("play", tagOf(node(Box(0, 0, 10, 10), description = "  Play  ")))
        // view-id matching is unchanged
        assertEquals("play", tagOf(node(Box(0, 0, 10, 10), viewId = "x:id/a_b_c_d_e_play", text = "Video: best of the day")))
    }

    @Test fun wide_node_not_target() {
        val wide = node(Box(0, 500, 600, 600), clickable = true, text = "Send") // 600 > 0.5 * 1080
        val ok = node(Box(0, 700, 540, 800), clickable = true, text = "Send") // exactly half
        val layout = build(listOf(wide, ok))
        assertEquals(listOf(Target("send", Box(0, 700, 540, 800))), layout.targets)
        // the wide one is still a plain platform, without a tag
        assertEquals(listOf(null, "send"), layout.platforms.sortedBy { it.top }.map { it.tag })
    }

    @Test fun tagged_platforms_survive_cap() {
        val many = (0 until 40).map { i ->
            node(Box(0, 300 + i * 20, 200 + i * 10, 310 + i * 20), clickable = true)
        }
        val narrowSend = node(Box(0, 1500, 30, 1560), clickable = true, text = "Send")
        val capped = build(many + narrowSend).platforms
        assertEquals(30, capped.size)
        assertEquals("send", capped.first().tag)
        assertEquals(1, capped.count { it.tag != null })
        // the rest are the 29 widest
        assertEquals((11 until 40).map { 200 + it * 10 }.reversed(), capped.drop(1).map { it.right })
    }

    @Test fun arabic_diacritics_match() {
        val b = Box(0, 0, 10, 10)
        assertEquals("send", tagOf(node(b, text = "إِرْسَال")))
        assertEquals("like", tagOf(node(b, description = "أَعْجَبَنِي")))
        assertEquals("search", tagOf(node(b, text = "بَحْث")))
        assertEquals("follow", tagOf(node(b, text = "مُتَابَعَة")))
    }

    @Test fun empty_layout() {
        assertEquals(Layout(emptyList(), emptyList()), Layout.EMPTY)
        assertEquals(Layout.EMPTY, build(emptyList()))
    }

    private companion object {
        const val SCREEN_W = 1080
        const val SPRITE_W = 50
        const val SPRITE_H = 120
        const val STATUS_H = 80
        const val MIN_IMG = 48
    }
}
