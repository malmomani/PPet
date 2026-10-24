package com.maya.core

/** Reconnect delay: starts at [initialMs], doubles on each call, capped at [maxMs]. No jitter. */
class Backoff(private val initialMs: Long = 1000, private val maxMs: Long = 60_000) {
    private var nextMs: Long = initialMs

    fun nextDelayMs(): Long {
        val current = nextMs
        nextMs = if (current >= maxMs / 2) maxMs else current * 2
        return minOf(current, maxMs)
    }

    fun reset() {
        nextMs = initialMs
    }
}
