package com.maya.app.senses

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.maya.core.AppOpened
import com.maya.core.AppUsageTracker
import com.maya.core.DoNotWatch
import com.maya.core.Event
import java.time.Instant

/**
 * Polls UsageStatsManager every 5 s for the foreground app and feeds [AppUsageTracker]. Ignores
 * do-not-watch packages, home screens and Maya herself. Without usage access it emits nothing.
 * Polling pauses while the screen is off (see [setScreenOn]).
 */
class AppUsageSense(context: Context, private val doNotWatch: DoNotWatch) : Sense {
    private val ctx: Context = context.applicationContext
    private val pm: PackageManager = ctx.packageManager
    private val usage: UsageStatsManager? = ctx.getSystemService(UsageStatsManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val ownPackage: String = ctx.packageName
    private val launchers: Set<String> = findLaunchers()
    private val labels = HashMap<String, String>()
    private val tracker = AppUsageTracker(isIgnored = { pkg -> isIgnored(pkg) })

    private var emit: ((Event) -> Unit)? = null
    private var started = false
    private var screenOn = true
    private var foreground: String? = null
    private var currentLabel: String = ""
    /** Events before this time (ms) are stale: they happened before the screen last went off. */
    private var ignoreBefore = 0L

    private val poll = object : Runnable {
        override fun run() {
            tick()
            if (started && screenOn) main.postDelayed(this, POLL_MS)
        }
    }

    override fun start(emit: (Event) -> Unit) {
        if (started) return
        this.emit = emit
        started = true
        screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (screenOn) main.post(poll)
    }

    override fun stop() {
        started = false
        main.removeCallbacks(poll)
        emit = null
    }

    /** Screen off closes the current app (AppClosed) and pauses polling; screen on resumes it. */
    fun setScreenOn(on: Boolean) {
        if (on == screenOn) return
        screenOn = on
        main.removeCallbacks(poll)
        if (!on) {
            foreground = null
            ignoreBefore = System.currentTimeMillis()
            deliver(tracker.observe(null, null, Instant.now()))
        } else if (started) {
            main.post(poll)
        }
    }

    /** The app Maya currently sees in the foreground (package, label), if any. */
    fun currentApp(): Pair<String, String>? {
        val pkg = tracker.current() ?: return null
        return Pair(pkg, currentLabel)
    }

    private fun isIgnored(pkg: String): Boolean =
        doNotWatch.isBlocked(pkg) || pkg in launchers || pkg == ownPackage

    private fun tick() {
        if (!started || !screenOn) return
        val now = System.currentTimeMillis()
        val latest = latestResumed(maxOf(now - WINDOW_MS, ignoreBefore), now)
        if (latest != null) foreground = latest
        val pkg = foreground ?: return
        val label = if (pkg == tracker.current() || isIgnored(pkg)) null else labelOf(pkg)
        deliver(tracker.observe(pkg, label, Instant.now()))
    }

    private fun deliver(events: List<Event>) {
        for (e in events) {
            if (e is AppOpened) currentLabel = e.label
            emit?.invoke(e)
        }
    }

    /** Package of the last ACTIVITY_RESUMED event in [begin, end], or null (none, or no usage access). */
    private fun latestResumed(begin: Long, end: Long): String? {
        val manager = usage ?: return null
        val events: UsageEvents = try {
            manager.queryEvents(begin, end) ?: return null
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalStateException) {
            // e.g. user still locked after boot
            return null
        }
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }

    @Suppress("DEPRECATION")
    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    @Suppress("DEPRECATION")
    private fun findLaunchers(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }
            // Settings registers a "FallbackHome" used only during boot; it must not hide the Settings app.
            .filter { !it.name.endsWith("FallbackHome") }
            .map { it.packageName }
            .toSet()
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not resolve home apps: ${e.javaClass.simpleName}")
        emptySet()
    }

    companion object {
        private const val TAG = "AppUsageSense"
        private const val POLL_MS = 5_000L
        private const val WINDOW_MS = 10_000L
    }
}
