package com.maya.core

enum class Gesture { TAP, DRAG, FLING }

const val TAP_MAX_DURATION_MS = 300L
const val HOLD_TO_TALK_MS = 400L
const val FLING_MIN_SPEED_PX_PER_S = 2500f

/**
 * Classifies a finished touch on the character. A tap is a short touch that barely moved; a fast
 * release is a fling (throw); everything else (incl. a long press without moving) is a drag.
 */
fun classifyGesture(
    distancePx: Float,
    durationMs: Long,
    releaseSpeedPxPerS: Float,
    touchSlopPx: Float,
): Gesture = when {
    distancePx < touchSlopPx && durationMs < TAP_MAX_DURATION_MS -> Gesture.TAP
    releaseSpeedPxPerS > FLING_MIN_SPEED_PX_PER_S -> Gesture.FLING
    else -> Gesture.DRAG
}

/** True while a press has stayed put (under touch slop) long enough to start listening. */
fun isHoldToTalk(distancePx: Float, heldMs: Long, touchSlopPx: Float): Boolean =
    distancePx < touchSlopPx && heldMs >= HOLD_TO_TALK_MS
