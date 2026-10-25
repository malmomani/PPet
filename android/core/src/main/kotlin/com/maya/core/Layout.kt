package com.maya.core

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

/** A visible accessibility node, reduced to what layout needs (no Android types). */
data class UiNode(
    val bounds: Box,
    val clickable: Boolean,
    val editable: Boolean,
    val image: Boolean,
    val viewId: String?,
    val description: String?,
    val text: String?,
)

/** A tease target: a button she can sit on or poke. */
data class Target(val tag: String, val bounds: Box)

/** A top edge she can stand on, spanning [left]..[right] at y = [top]; [tag] when it is a tease target. */
data class Platform(val left: Int, val right: Int, val top: Int, val tag: String? = null)

data class Layout(val platforms: List<Platform>, val targets: List<Target>) {
    companion object {
        val EMPTY = Layout(emptyList(), emptyList())
    }
}

/** Tease tags, in priority order (first match wins). */
val TEASE_TAGS: List<String> = listOf("send", "like", "play", "search", "share", "comment", "follow")

private val TAG_WORDS: Map<String, Set<String>> = mapOf(
    "send" to setOf("send", "إرسال", "ارسال"),
    "like" to setOf("like", "heart", "favorite", "favourite", "love", "أعجبني", "إعجاب"),
    "play" to setOf("play"),
    "search" to setOf("search", "بحث"),
    "share" to setOf("share", "مشاركة"),
    "comment" to setOf("comment", "reply", "تعليق"),
    "follow" to setOf("follow", "subscribe", "متابعة"),
)

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/** Lower-cased words: runs of letters/digits (Unicode-aware, so Arabic works). */
private fun words(s: String?): List<String> =
    if (s == null) emptyList() else s.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

/**
 * The tease tag a node carries, or null. Whole words of the view id (after the last `/`), the
 * description and the text; "unlike" and "unfollow" are different words and never match. The first
 * tag in [TEASE_TAGS] order wins. An editable node's text is what the user typed: never read.
 */
fun tagOf(n: UiNode): String? {
    val found = HashSet<String>()
    found.addAll(words(n.viewId?.substringAfterLast('/')))
    found.addAll(words(n.description))
    if (!n.editable) found.addAll(words(n.text))
    if (found.isEmpty()) return null
    return TEASE_TAGS.firstOrNull { tag -> TAG_WORDS.getValue(tag).any { it in found } }
}

private const val MAX_PLATFORMS = 30
private const val MERGE_TOLERANCE_PX = 4

/**
 * Turns the visible nodes into the platforms she can stand on and the tease targets.
 *
 * A node is a platform if it is clickable, editable, or an image of at least [minImagePx] in both
 * directions; it is at least 0.6 sprite widths wide (on screen), there is room for her above it
 * (`top - spriteH >= statusBarH`) and it does not start at or below [keyboardTop]. Edges within 4 px
 * in y that overlap or touch in x merge (a target's tag survives); the 30 widest are kept, widest
 * first. Targets are the clickable nodes that carry a tease tag. The floor is not included.
 */
fun buildLayout(
    nodes: List<UiNode>,
    screenW: Int,
    spriteW: Int,
    spriteH: Int,
    statusBarH: Int,
    keyboardTop: Int?,
    minImagePx: Int,
): Layout {
    val targets = ArrayList<Target>()
    val edges = ArrayList<Platform>()
    for (n in nodes) {
        val b = n.bounds
        val tag = if (n.clickable) tagOf(n) else null
        if (tag != null && b.width > 0 && b.height > 0) targets.add(Target(tag, b))

        val big = n.clickable || n.editable || (n.image && b.width >= minImagePx && b.height >= minImagePx)
        if (!big) continue
        val left = maxOf(b.left, 0)
        val right = minOf(b.right, screenW)
        if ((right - left) * 5 < spriteW * 3) continue
        if (b.top - spriteH < statusBarH) continue
        if (keyboardTop != null && b.top >= keyboardTop) continue
        edges.add(Platform(left, right, b.top, tag))
    }
    val platforms = merge(edges)
        .sortedWith(compareBy<Platform>({ -(it.right - it.left) }, { it.top }, { it.left }))
        .take(MAX_PLATFORMS)
    targets.sortWith(compareBy<Target>({ it.bounds.top }, { it.bounds.left }))
    return Layout(platforms, targets)
}

private fun merge(edges: List<Platform>): List<Platform> {
    val out = edges.sortedWith(compareBy<Platform>({ it.top }, { it.left })).toMutableList()
    var changed = true
    while (changed) {
        changed = false
        loop@ for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                if (Math.abs(a.top - b.top) <= MERGE_TOLERANCE_PX && a.left <= b.right && b.left <= a.right) {
                    out[i] = Platform(minOf(a.left, b.left), maxOf(a.right, b.right), minOf(a.top, b.top), a.tag ?: b.tag)
                    out.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
    }
    return out
}
