package com.maya.core

import java.time.Duration
import java.time.Instant

/**
 * Turns foreground-app observations into [AppOpened]/[AppClosed] events. A null package means the
 * screen is off or the foreground app is unknown; ignored packages are treated the same as null.
 * Transparent packages (e.g. our own overlay) are skipped entirely: no events, and the current app
 * is left unchanged. Not thread-safe (call from a single thread).
 */
class AppUsageTracker(
    private val isIgnored: (String) -> Boolean,
    private val isTransparent: (String) -> Boolean = { false },
) {
    private var pkg: String? = null
    private var label: String = ""
    private var since: Instant = Instant.EPOCH

    fun current(): String? = pkg

    fun observe(pkg: String?, label: String?, now: Instant): List<Event> {
        if (pkg != null && isTransparent(pkg)) return emptyList()
        val next = pkg?.takeUnless(isIgnored)
        val prev = this.pkg
        if (next == prev) return emptyList()
        val out = ArrayList<Event>(2)
        if (prev != null) {
            val seconds = maxOf(0L, Duration.between(since, now).seconds)
            out += AppClosed(prev, this.label, seconds, ts = now)
        }
        if (next != null) {
            val shown = label ?: next
            out += AppOpened(next, shown, ts = now)
            this.label = shown
            this.since = now
        } else {
            this.label = ""
        }
        this.pkg = next
        return out
    }
}
