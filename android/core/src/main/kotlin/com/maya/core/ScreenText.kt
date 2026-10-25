package com.maya.core

/**
 * One piece of on-screen text as the accessibility service sees it (no Android types).
 * [password] and [editable] nodes are never read: what the user types stays on the phone.
 */
data class TextNode(
    val text: String,
    val top: Int,
    val left: Int,
    val password: Boolean,
    val editable: Boolean,
)

private const val MAX_LINES = 40
private const val MAX_LINE_LEN = 200
private const val MAX_TOTAL_LEN = 3000

/**
 * The visible text, top-to-bottom then left-to-right: trimmed, without password or editable nodes,
 * blanks or consecutive duplicates. At most 40 lines, 200 chars each, 3000 chars in all; a cut never
 * splits a surrogate pair.
 */
fun extractLines(nodes: List<TextNode>): List<String> {
    val out = ArrayList<String>()
    var total = 0
    val ordered = nodes.sortedWith(compareBy<TextNode>({ it.top }, { it.left }))
    for (n in ordered) {
        if (out.size >= MAX_LINES || total >= MAX_TOTAL_LEN) break
        if (n.password || n.editable) continue
        val line = capText(n.text.trim(), minOf(MAX_LINE_LEN, MAX_TOTAL_LEN - total)).trim()
        if (line.isEmpty() || line == out.lastOrNull()) continue
        out.add(line)
        total += line.length
    }
    return out
}

/** At most [max] chars without splitting an emoji (surrogate pair) in half. */
private fun capText(s: String, max: Int): String {
    val t = s.take(max)
    return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
}
