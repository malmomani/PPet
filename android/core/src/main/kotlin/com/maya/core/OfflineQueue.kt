package com.maya.core

import java.time.Instant

/**
 * Bounded buffer of events produced while the brain is unreachable. When full, the oldest event is
 * dropped. Not thread-safe.
 */
class OfflineQueue(private val capacity: Int = 200) {
    private val events = ArrayDeque<Event>()
    private var since: Instant? = null

    val size: Int get() = events.size

    fun add(e: Event) {
        if (since == null) since = e.ts
        events.addLast(e)
        while (events.size > capacity) events.removeFirst()
    }

    /** Returns everything queued as one summary and clears the queue; null when nothing is queued. */
    fun drainSummary(): OfflineSummary? {
        val start = since ?: return null
        if (events.isEmpty()) return null
        val summary = OfflineSummary(events.toList(), start)
        events.clear()
        since = null
        return summary
    }
}
