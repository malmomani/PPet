package com.maya.core

/**
 * Decides when to take a screen "glance". An app change makes a glance due after [settleMs];
 * after each captured glance the next check is armed at now + [periodMs]. Pure timing logic:
 * the caller supplies the clock and does the capturing.
 */
class GlanceScheduler(private val settleMs: Long = 2_000, private val periodMs: Long = 180_000) {
    private var currentPkg: String? = null
    private var dueAtMs: Long? = null
    private val lastHash = HashMap<String, Int>()

    /** Foreground app changed. Same package as now: nothing. Null package: nothing is due. */
    fun onApp(pkg: String?, nowMs: Long) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        dueAtMs = if (pkg == null) null else nowMs + settleMs
    }

    /** True once when a glance is due and [allowed]; while not allowed it stays due. */
    fun poll(nowMs: Long, allowed: Boolean): Boolean {
        val due = dueAtMs ?: return false
        if (!allowed || nowMs < due) return false
        dueAtMs = null
        return true
    }

    /**
     * Call after a glance was actually captured. Re-arms the next check at now + period and
     * returns true iff [lines] differ from the last ones sent for [pkg] (and records them).
     */
    fun taken(pkg: String, lines: List<String>, nowMs: Long): Boolean {
        dueAtMs = nowMs + periodMs
        val hash = lines.hashCode()
        if (lastHash[pkg] == hash) return false
        lastHash[pkg] = hash
        return true
    }
}
