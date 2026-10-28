package com.maya.core

/** Share of the screen (percent) that images must cover for it to count as picture-heavy. */
private const val HEAVY_AREA_PERCENT = 40
/** Share of the screen (percent) one image must cover, together with few text lines. */
private const val BIG_IMAGE_PERCENT = 25
private const val FEW_TEXT_LINES = 8

/** [b] clipped to the screen, as an area in px (0 when it is empty or off screen). */
private fun visibleArea(b: Box, screenW: Int, screenH: Int): Long {
    val w = minOf(b.right, screenW) - maxOf(b.left, 0)
    val h = minOf(b.bottom, screenH) - maxOf(b.top, 0)
    return if (w <= 0 || h <= 0) 0L else w.toLong() * h
}

/**
 * Is the screen mostly pictures? True when the image boxes cover at least 40 % of the screen
 * (the clipped areas are summed, overlaps are not removed, the total is capped at the screen), or
 * when one image covers at least 25 % of it and there are at most 8 lines of text.
 */
fun isPictureHeavy(images: List<Box>, screenW: Int, screenH: Int, textLines: Int): Boolean {
    if (screenW <= 0 || screenH <= 0 || images.isEmpty()) return false
    val screen = screenW.toLong() * screenH
    val areas = images.map { visibleArea(it, screenW, screenH) }
    val total = minOf(areas.sum(), screen)
    if (total * 100 >= screen * HEAVY_AREA_PERCENT) return true
    val biggest = areas.max()
    return biggest * 100 >= screen * BIG_IMAGE_PERCENT && textLines <= FEW_TEXT_LINES
}

/**
 * Limits how often she may look at the screen. An automatic look needs a picture-heavy screen and
 * permission, and is at most once per [autoGapMs] overall and once per [perAppGapMs] per app; an
 * asked look ignores those and is at most once per [askedGapMs]. Pure timing logic: the caller
 * supplies the clock.
 */
class LookScheduler(
    private val autoGapMs: Long = 300_000,
    private val perAppGapMs: Long = 600_000,
    private val askedGapMs: Long = 20_000,
) {
    private var lastAutoMs: Long? = null
    private val lastAutoByApp = HashMap<String, Long>()
    private var lastAskedMs: Long? = null

    /** True (and recorded, globally and for [pkg]) when an automatic look is due; false records nothing. */
    fun autoDue(pkg: String, pictureHeavy: Boolean, allowed: Boolean, nowMs: Long): Boolean {
        if (!allowed || !pictureHeavy) return false
        lastAutoMs?.let { if (nowMs - it < autoGapMs) return false }
        lastAutoByApp[pkg]?.let { if (nowMs - it < perAppGapMs) return false }
        lastAutoMs = nowMs
        lastAutoByApp[pkg] = nowMs
        return true
    }

    /** True (and recorded) when an asked look may go ahead; false records nothing. */
    fun askedAllowed(nowMs: Long): Boolean {
        lastAskedMs?.let { if (nowMs - it < askedGapMs) return false }
        lastAskedMs = nowMs
        return true
    }
}
