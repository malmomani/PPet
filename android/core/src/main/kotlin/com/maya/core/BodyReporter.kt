package com.maya.core

/** Core's own view of her body for reporting; the app maps it to the `Body` protocol event. */
data class BodySnapshot(val doing: String, val where: String, val target: String?, val x: Double, val y: Double)

/**
 * Turns body state into `body` reports without flooding the brain: a snapshot is sent only when
 * (doing, where, target) differs from the last one sent and at least [throttleMs] has passed since
 * that send; otherwise the latest one stays pending until [poll] finds the window open.
 */
class BodyReporter(private val throttleMs: Long = 5_000) {
    private var lastSent: BodySnapshot? = null
    private var lastSentAtMs: Long = 0
    private var pending: BodySnapshot? = null

    fun update(s: BodySnapshot, nowMs: Long): BodySnapshot? {
        if (lastSent?.let { sameState(it, s) } == true) {
            pending = null
            return null
        }
        if (!windowOpen(nowMs)) {
            pending = s
            return null
        }
        return send(s, nowMs)
    }

    fun poll(nowMs: Long): BodySnapshot? {
        val p = pending ?: return null
        return if (windowOpen(nowMs)) send(p, nowMs) else null
    }

    /** Next update sends at once even if unchanged (used on connect). */
    fun reset() {
        lastSent = null
        pending = null
    }

    private fun windowOpen(nowMs: Long) = lastSent == null || nowMs - lastSentAtMs >= throttleMs

    private fun send(s: BodySnapshot, nowMs: Long): BodySnapshot {
        lastSent = s
        lastSentAtMs = nowMs
        pending = null
        return s
    }

    private fun sameState(a: BodySnapshot, b: BodySnapshot) =
        a.doing == b.doing && a.where == b.where && a.target == b.target
}
