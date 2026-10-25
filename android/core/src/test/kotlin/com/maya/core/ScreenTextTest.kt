package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTextTest {
    private fun node(
        text: String,
        top: Int = 0,
        left: Int = 0,
        password: Boolean = false,
        editable: Boolean = false,
    ) = TextNode(text, top, left, password, editable)

    @Test fun extract_order_and_privacy() {
        val lines = extractLines(
            listOf(
                node("third", top = 300),
                node("second right", top = 100, left = 500),
                node("hunter2", top = 10, password = true),
                node("what I am typing", top = 50, editable = true),
                node("  first  ", top = 100, left = 20),
                node("   ", top = 120),
                node("", top = 130),
            ),
        )
        assertEquals(listOf("first", "second right", "third"), lines)
    }

    @Test fun extract_empty() {
        assertEquals(emptyList<String>(), extractLines(emptyList()))
    }

    @Test fun extract_caps_and_dupes() {
        // consecutive duplicates (after trimming) are dropped; non-consecutive ones stay
        assertEquals(
            listOf("a", "b", "a"),
            extractLines(listOf(node("a", 1), node(" a ", 2), node("b", 3), node("a", 4))),
        )

        // at most 40 lines
        val many = (1..60).map { node("line $it", top = it) }
        val capped = extractLines(many)
        assertEquals(40, capped.size)
        assertEquals("line 1", capped.first())
        assertEquals("line 40", capped.last())

        // each line at most 200 chars
        val long = extractLines(listOf(node("x".repeat(500))))
        assertEquals(listOf("x".repeat(200)), long)

        // a cut never splits a surrogate pair: char 199 is the high half of an emoji
        val emoji = "😀"
        val split = extractLines(listOf(node("y".repeat(199) + emoji + "tail")))
        assertEquals(listOf("y".repeat(199)), split)
        // ...but a pair that fits whole is kept
        val fits = extractLines(listOf(node("y".repeat(198) + emoji + "tail")))
        assertEquals(listOf("y".repeat(198) + emoji), fits)

        // at most 3000 chars in total (190 per line -> the 16th line is cut to fit)
        val wide = (10..49).map { node(('a' + (it % 26)).toString().repeat(188) + it, top = it) }
        val total = extractLines(wide)
        assertEquals(3000, total.sumOf { it.length })
        assertEquals(15, total.count { it.length == 190 })
        assertEquals(16, total.size)
    }
}
