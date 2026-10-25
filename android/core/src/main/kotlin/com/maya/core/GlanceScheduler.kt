package com.maya.core

/**
 * Decides when to take a screen "glance". An app change makes a glance due after [settleMs];
 * after each captured glance the next check is armed at now + [periodMs]. The first glance after
 * an app change is always sent; only the periodic same-app re-checks are deduplicated. Pure
 * timing logic: the caller supplies the clock and does the capturing.
 */
class GlanceScheduler(private val settleMs: Long = 2_000, private val periodMs: Long = 180_000) {
    private var currentPkg: String? = null
    private var dueAtMs: Long? = null
    private val lastHash = HashMap<String, Int>()
    /** The app changed since the last glance was taken: the next one is sent whatever its text. */
    private var appChanged = false
    /** The package of the last glance sent. */
    private var lastSentPkg: String? = null

    /** Foreground app changed. Same package as now: nothing. Null package: nothing is due. */
    fun onApp(pkg: String?, nowMs: Long) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        appChanged = true
        dueAtMs = if (pkg == null) null else nowMs + settleMs
    }

    /**
     * True once when a glance is due and [allowed]; while not allowed it stays due. Returning true
     * also arms the next check at now + period, so a failed capture retries a period later.
     */
    fun poll(nowMs: Long, allowed: Boolean): Boolean {
        val due = dueAtMs ?: return false
        if (!allowed || nowMs < due) return false
        dueAtMs = nowMs + periodMs
        return true
    }

    /**
     * Call after a glance was actually captured. Re-arms the next check at now + period and
     * returns true (recording [lines]) for the first glance after an app change or one of another
     * app than the last sent; otherwise iff [lines] differ from the last ones sent for [pkg].
     */
    fun taken(pkg: String, lines: List<String>, nowMs: Long): Boolean {
        dueAtMs = nowMs + periodMs
        val hash = lines.hashCode()
        val fresh = appChanged || pkg != lastSentPkg
        appChanged = false
        if (!fresh && lastHash[pkg] == hash) return false
        lastHash[pkg] = hash
        lastSentPkg = pkg
        return true
    }
}
