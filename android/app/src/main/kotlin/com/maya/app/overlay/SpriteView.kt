package com.maya.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Draws the current sprite frame scaled to the view size with nearest-neighbour sampling (crisp
 * pixels), mirrored horizontally when [facingLeft] (the art faces right). [advance] steps frames
 * at the animation's fps; a non-looping animation holds its last frame, except the gestures in
 * [REPEATED] (a wave, a happy hop), which play again for as long as she keeps them up.
 */
class SpriteView(context: Context, private val sheet: SpriteSheet) : View(context) {
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val dst = Rect()
    private var animation = "idle"
    private var frameIndex = 0
    private var frameElapsedMs = 0L

    var facingLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Switches animation; the frame index restarts only when the name actually changes. */
    fun setAnimation(name: String) {
        if (name == animation) return
        animation = name
        frameIndex = 0
        frameElapsedMs = 0L
        invalidate()
    }

    fun advance(dtMs: Long) {
        val spec = sheet.spec(animation)
        val frameMs = (1000L / spec.fps).coerceAtLeast(1L)
        frameElapsedMs += dtMs
        var changed = false
        while (frameElapsedMs >= frameMs) {
            frameElapsedMs -= frameMs
            if (frameIndex < spec.frames - 1) {
                frameIndex++
                changed = true
            } else if (spec.loop || animation in REPEATED) {
                if (frameIndex != 0) {
                    frameIndex = 0
                    changed = true
                }
            } else {
                frameElapsedMs = 0L // hold the last frame
                break
            }
        }
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        dst.set(0, 0, w, h)
        val saved = canvas.save()
        if (facingLeft) canvas.scale(-1f, 1f, w / 2f, h / 2f)
        canvas.drawBitmap(sheet.bitmap, sheet.frame(animation, frameIndex), dst, paint)
        canvas.restoreToCount(saved)
    }

    private companion object {
        /** Gestures Behavior holds for a whole number of plays: they repeat instead of freezing. */
        val REPEATED = setOf("wave", "happy")
    }
}
