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

/**
 * What the screen shows. [images] are the boxes of the visible image nodes at least `minImagePx`
 * in both directions, clipped to the screen, largest first, at most 40 (for the picture-heavy rule).
 */
data class Layout(
    val platforms: List<Platform>,
    val targets: List<Target>,
    val images: List<Box> = emptyList(),
) {
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
private val MARKS = Regex("\\p{M}+")

/**
 * Lower-cased words: runs of letters/digits (Unicode-aware, so Arabic works). Combining marks
 * (Arabic diacritics) are stripped first, so "إِرْسَال" is the word "إرسال".
 */
private fun words(s: String?): List<String> =
    if (s == null) emptyList() else s.lowercase().replace(MARKS, "").split(NON_WORD).filter { it.isNotEmpty() }

private const val MAX_LABEL_WORDS = 4
private const val MAX_LABEL_CHARS = 24

/** Words of a button label; a long text (a card, a paragraph) is not a label and gives none. */
private fun labelWords(s: String?): List<String> {
    val t = s?.trim() ?: return emptyList()
    if (t.length > MAX_LABEL_CHARS) return emptyList()
    val w = words(t)
    return if (w.size > MAX_LABEL_WORDS) emptyList() else w
}

/**
 * The tease tag a node carries, or null. Whole words of the view id (after the last `/`), the
 * description and the text (the last two only when they are short labels: at most 4 words and
 * 24 chars); "unlike" and "unfollow" are different words and never match. The first
 * tag in [TEASE_TAGS] order wins. An editable node's text is what the user typed: never read.
 */
fun tagOf(n: UiNode): String? {
    val found = HashSet<String>()
    found.addAll(words(n.viewId?.substringAfterLast('/')))
    found.addAll(labelWords(n.description))
    if (!n.editable) found.addAll(labelWords(n.text))
    if (found.isEmpty()) return null
    return TEASE_TAGS.firstOrNull { tag -> TAG_WORDS.getValue(tag).any { it in found } }
}

private const val MAX_PLATFORMS = 30
private const val MAX_IMAGES = 40
private const val MERGE_TOLERANCE_PX = 4

/**
 * Turns the visible nodes into the platforms she can stand on and the tease targets.
 *
 * A node is a platform if it is clickable, editable, or an image of at least [minImagePx] in both
 * directions; it is at least 0.6 sprite widths wide (on screen), there is room for her above it
 * (`top - spriteH >= statusBarH`) and it does not start at or below [keyboardTop]. Edges within 4 px
 * in y that overlap or touch in x merge (a target's tag survives); tagged ones always survive the cap of 30 and
 * come first, then the widest others, widest first. Targets are the clickable nodes that carry a tease tag. The floor is not included.
 * Image boxes are clipped to the screen (`screenW` wide, [screenH] high when given) before the
 * size check; the layout keeps the 40 largest.
 */
fun buildLayout(
    nodes: List<UiNode>,
    screenW: Int,
    spriteW: Int,
    spriteH: Int,
    statusBarH: Int,
    keyboardTop: Int?,
    minImagePx: Int,
    screenH: Int = Int.MAX_VALUE,
): Layout {
    val targets = ArrayList<Target>()
    val images = ArrayList<Box>()
    val edges = ArrayList<Platform>()
    for (n in nodes) {
        val b = n.bounds
        // a target is a button: a node spanning more than half the screen is a card or a row, not one
        val tag = if (n.clickable && b.width * 2 <= screenW) tagOf(n) else null
        if (tag != null && b.width > 0 && b.height > 0) targets.add(Target(tag, b))

        if (n.image) {
            val clipped = Box(maxOf(b.left, 0), maxOf(b.top, 0), minOf(b.right, screenW), minOf(b.bottom, screenH))
            if (clipped.width >= minImagePx && clipped.height >= minImagePx) images.add(clipped)
        }

        val big = n.clickable || n.editable || (n.image && b.width >= minImagePx && b.height >= minImagePx)
        if (!big) continue
        val left = maxOf(b.left, 0)
        val right = minOf(b.right, screenW)
        if ((right - left) * 5 < spriteW * 3) continue
        if (b.top - spriteH < statusBarH) continue
        if (keyboardTop != null && b.top >= keyboardTop) continue
        edges.add(Platform(left, right, b.top, tag))
    }
    // tagged platforms are never evicted by the cap; the rest fill up by width
    val (tagged, plain) = merge(edges)
        .sortedWith(compareBy<Platform>({ -(it.right - it.left) }, { it.top }, { it.left }))
        .partition { it.tag != null }
    val platforms = (tagged.take(MAX_PLATFORMS) + plain).take(MAX_PLATFORMS)
    targets.sortWith(compareBy<Target>({ it.bounds.top }, { it.bounds.left }))
    val largest = images.sortedByDescending { it.width.toLong() * it.height }.take(MAX_IMAGES)
    return Layout(platforms, targets, largest)
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
