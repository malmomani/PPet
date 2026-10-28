package com.maya.app.actions

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import android.view.KeyEvent
import com.maya.app.Prefs
import com.maya.app.R
import com.maya.app.senses.Clicked
import com.maya.app.senses.HandResult
import com.maya.app.senses.PhoneHands
import com.maya.app.senses.PressNode
import com.maya.core.Act
import com.maya.core.ActResult
import com.maya.core.Box
import com.maya.core.Event
import com.maya.core.UiNode
import com.maya.core.blockedReason
import com.maya.core.isRisky
import com.maya.core.matchApp

/** What the action runner needs from her body (OverlayBody). Main thread only. */
interface ActionBody {
    /** True while her window is on screen, so she can ask and walk. */
    val isShowing: Boolean

    /**
     * Asks [question] in her bubble with ✔ / ✖ (pose `ask` meanwhile). [onAnswer] is called at most
     * once: true for ✔, false for ✖ or a tap on her, null when the question went away with her
     * (hidden, screen off). False when she can't ask now (hidden, listening); then it is never called.
     */
    fun confirm(question: String, onAnswer: (Boolean?) -> Unit): Boolean

    /** Withdraws the question, if any, without calling its callback. */
    fun cancelConfirm()

    /**
     * She walks to [box] and pokes it (core `commandPress`); [onResult] is called once: true when
     * her poke starts, false when she can't get there. Where she can't walk now (keyboard open, a
     * call, a button below her floor) she pokes where she stands. False when she isn't on screen;
     * then it is never called.
     */
    fun press(box: Box, onResult: (Boolean) -> Unit): Boolean

    /** Forgets the press under way, if any, without calling its callback. */
    fun cancelPress()

    /** Holds the pose [name] (e.g. `type`) until cleared with null. */
    fun setActPose(name: String?)

    /** Lets touches pass through her window for [ms] (a fallback tap must reach the app, not her). */
    fun letTouchesThrough(ms: Long)
}

/**
 * Runs the brain's `act` actions (spec §5.3), one at a time, and answers every one with exactly one
 * [ActResult]: done, declined (✖), timeout (no answer in 15 s), failed or blocked. Checks first
 * (core [blockedReason] on the live active window, the keyguard and "Let Maya use my phone"),
 * resolves the target on screen, asks before anything risky (core [isRisky]), then acts: system
 * kinds through Android APIs, press/type/scroll through the accessibility service ([hands]),
 * checking again in the same pass as the click or the typing. A press walks her body to the
 * button first ([body]). Never logs what is typed, searched or read on screen. Main thread only.
 */
class ActionRunner(
    private val context: Context,
    private val prefs: Prefs,
    private val emit: (Event) -> Unit,
    private val body: () -> ActionBody?,
    private val hands: () -> PhoneHands?,
    private val doNotWatch: (String) -> Boolean,
) {
    private class Job(val act: Act) {
        val startMs: Long = SystemClock.uptimeMillis()
        /** The user said yes: risk is no longer asked about. */
        var confirmed = false
        /** Her wall-clock cap; longer once she asks. */
        var capMs = CAP_MS
    }

    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val capTask = Runnable { job?.let { finish(it, FAILED, "took too long") } }
    private val confirmTimeout = Runnable { job?.let { finish(it, TIMEOUT, null) } }

    /** Starts [act], or answers it at once (blocked "busy" while another one runs). */
    fun run(act: Act) {
        if (job != null) {
            send(act, BLOCKED, "busy")
            return
        }
        val j = Job(act)
        job = j
        main.postDelayed(capTask, CAP_MS)
        guarded(j) { start(j) }
    }

    /** Ends the action under way, if any, as failed([detail]) (disconnected, stopping). */
    fun cancel(detail: String) {
        job?.let { finish(it, FAILED, detail) }
    }

    // ---- flow ----

    private fun start(j: Job) {
        val act = j.act
        val refused = refusal(act, hands()?.activePackage())
        if (refused != null) return finish(j, refused.first, refused.second)
        when (act.kind) {
            "open_app" -> system(j) { openApp(act.app.orEmpty()) }
            "media" -> system(j) { media(act.key) }
            "volume" -> system(j) { volume(act.dir) }
            "timer" -> system(j) { timer(act.seconds ?: 0, act.label) }
            "alarm" -> system(j) { alarm(act.hour ?: 0, act.minute ?: 0, act.label) }
            "web_search" -> system(j) { webSearch(act.query.orEmpty()) }
            "scroll" -> scroll(j)
            "press" -> press(j)
            "type" -> type(j)
            else -> finish(j, FAILED, "unknown action")
        }
    }

    /**
     * Why [act] may not run in the active window of [pkg] (status to detail), or null. Phone kinds
     * need the accessibility service (Screen access and "Let Maya read my screen").
     */
    private fun refusal(act: Act, pkg: String?): Pair<String, String>? {
        val reason = blockedReason(act, pkg, context.packageName, doNotWatch, keyguardLocked(), prefs.allowActions)
        if (reason == "off" || reason == "locked") return BLOCKED to reason
        if (act.kind in SCREEN_KINDS && hands() == null) return FAILED to "no screen access"
        return reason?.let { BLOCKED to it }
    }

    private fun keyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true

    /** A system kind: done, or the failure [action] reports. */
    private fun system(j: Job, action: () -> String?) {
        val failure = action()
        if (failure == null) finish(j, DONE, null) else finish(j, FAILED, failure)
    }

    private fun scroll(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        var refused: Pair<String, String>? = null
        val result = h.scroll(j.act.dir == "down") { pkg ->
            refused = refusal(j.act, pkg)
            refused == null
        }
        when (result) {
            HandResult.DONE -> finish(j, DONE, null)
            HandResult.NOT_FOUND -> finish(j, FAILED, "nothing to scroll")
            HandResult.REFUSED -> refuse(j, refused)
            HandResult.FAILED -> finish(j, FAILED, "couldn't scroll")
        }
    }

    /**
     * press: find the button; risky → ask first (and start over after a yes); her body walks to it
     * and pokes it; then it is found again, checked again and clicked (a tap gesture at its centre
     * when it won't take a click).
     */
    private fun press(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        val target = j.act.target.orEmpty()
        val found = h.findButton(target) ?: return finish(j, FAILED, "not on screen")
        if (!j.confirmed && risky(j.act, found)) return ask(j, pressQuestion(found, target)) { press(j) }
        val b = body()
        if (b == null || !b.isShowing) return finish(j, FAILED, "hidden")
        val walking = b.press(found.node.bounds) { reached ->
            callback(j) {
                if (reached) later(j, POKE_LEAD_MS) { click(j) } else finish(j, FAILED, "not on screen")
            }
        }
        if (!walking) finish(j, FAILED, "hidden")
    }

    private fun click(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        var refused: Pair<String, String>? = null
        var needsYes = false
        val clicked: Clicked = h.click(j.act.target.orEmpty()) { pkg, node ->
            refused = refusal(j.act, pkg)
            // the screen moved while she walked: a button that is risky now still needs a yes
            needsYes = refused == null && !j.confirmed && risky(j.act, node)
            refused == null && !needsYes
        }
        val node = clicked.target
        when (clicked.result) {
            HandResult.DONE -> finish(j, DONE, null)
            HandResult.NOT_FOUND -> finish(j, FAILED, "not on screen")
            HandResult.REFUSED -> if (needsYes && node != null) {
                ask(j, pressQuestion(node, j.act.target.orEmpty())) { press(j) }
            } else {
                refuse(j, refused)
            }
            HandResult.FAILED -> if (node != null) tapFallback(j, h, node.node.bounds) else finish(j, FAILED, "couldn't press it")
        }
    }

    /** The button won't take ACTION_CLICK: a tap gesture at its centre, with touches passing through her. */
    private fun tapFallback(j: Job, h: PhoneHands, box: Box) {
        body()?.letTouchesThrough(PASS_THROUGH_MS)
        later(j, PASS_THROUGH_LEAD_MS) {
            // checked again: the tap goes to whatever is under that point now
            val again = refusal(j.act, h.activePackage())
            if (again != null) return@later finish(j, again.first, again.second)
            val sent = h.tap(box.centerX, (box.top + box.bottom) / 2) { ok ->
                callback(j) { if (ok) finish(j, DONE, null) else finish(j, FAILED, "couldn't press it") }
            }
            if (!sent) finish(j, FAILED, "couldn't press it")
        }
    }

    /** type: find the field; always ask first; then the `type` pose, checked again, and the text set. */
    private fun type(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        val into = j.act.into
        val text = j.act.text.orEmpty()
        val field = h.findField(into) ?: return finish(j, FAILED, "no text field")
        if (!j.confirmed && isRisky(j.act, field)) {
            return ask(j, context.getString(R.string.ask_type) + "\n“" + preview(text, TYPE_PREVIEW_MAX) + "”") { type(j) }
        }
        val b = body()
        if (b == null || !b.isShowing) return finish(j, FAILED, "hidden")
        b.setActPose("type")
        later(j, TYPE_POSE_MS) {
            b.setActPose(null)
            var refused: Pair<String, String>? = null
            val result = h.setText(into, text) { pkg, _ ->
                refused = refusal(j.act, pkg)
                refused == null
            }
            when (result) {
                HandResult.DONE -> finish(j, DONE, null)
                HandResult.NOT_FOUND -> finish(j, FAILED, "no text field")
                HandResult.REFUSED -> refuse(j, refused)
                HandResult.FAILED -> finish(j, FAILED, "couldn't type")
            }
        }
    }

    /**
     * Asks [question] in her bubble; ✔ → [then] (after a moment, so the tap on the bubble is over),
     * ✖ → declined, no answer in 15 s → timeout. Her cap grows by the 15 s she waits.
     */
    private fun ask(j: Job, question: String, then: () -> Unit) {
        val b = body()
        if (b == null || !b.isShowing) return finish(j, FAILED, "hidden")
        val asked = b.confirm(question) { yes ->
            callback(j) {
                main.removeCallbacks(confirmTimeout)
                when (yes) {
                    true -> {
                        j.confirmed = true
                        later(j, AFTER_YES_MS, then)
                    }
                    false -> finish(j, DECLINED, null)
                    null -> finish(j, FAILED, "hidden")
                }
            }
        }
        if (!asked) return finish(j, FAILED, "couldn't ask")
        j.capMs += CONFIRM_MS
        main.removeCallbacks(capTask)
        main.postDelayed(capTask, (j.startMs + j.capMs - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        main.postDelayed(confirmTimeout, CONFIRM_MS)
    }

    /** Ends [j] (if it is still the current one) and sends its one result. */
    private fun finish(j: Job, status: String, detail: String?) {
        if (job !== j) return
        job = null
        main.removeCallbacks(capTask)
        main.removeCallbacks(confirmTimeout)
        main.removeCallbacksAndMessages(j)
        body()?.let {
            it.cancelConfirm()
            it.cancelPress()
            it.setActPose(null)
        }
        Log.i(TAG, "${j.act.kind}: $status") // never the text, query, app or label
        send(j.act, status, detail)
    }

    private fun send(act: Act, status: String, detail: String?) = emit(ActResult(act.id, status, detail))

    // ---- helpers ----

    /** Runs [block] for [j] if it is still the current action; an unexpected exception fails it. */
    private fun guarded(j: Job, block: () -> Unit) {
        if (job !== j) return
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "${j.act.kind} failed: ${e.javaClass.simpleName}")
            finish(j, FAILED, "error")
        }
    }

    /** [block] after [ms], if [j] is still current (dropped when it ends). */
    private fun later(j: Job, ms: Long, block: () -> Unit) {
        main.postDelayed({ guarded(j, block) }, j, ms)
    }

    /** A callback from her body or the accessibility service: run on the next loop turn, never inside theirs. */
    private fun callback(j: Job, block: () -> Unit) = later(j, 0L, block)

    /**
     * The hands refused: [refused] is why when our own check did, else the active window is one she
     * never reads (blocked "not here", or why the live check says not).
     */
    private fun refuse(j: Job, refused: Pair<String, String>?) {
        val r = refused ?: refusal(j.act, hands()?.activePackage()) ?: (BLOCKED to "not here")
        finish(j, r.first, r.second)
    }

    /** "Can I press {label}?" with the button's own label (at most 30 chars), else the target. */
    private fun pressQuestion(found: PressNode, target: String): String =
        context.getString(R.string.ask_press, clip(labelOf(found.node) ?: target, PRESS_LABEL_MAX))

    /** A press is risky when the button or the node its click goes to is (core [isRisky]). */
    private fun risky(act: Act, found: PressNode): Boolean = isRisky(act, found.node) || isRisky(act, found.clickable)

    /** What a button says: its text (not a field's: that is what the user typed), else its description. */
    private fun labelOf(n: UiNode): String? =
        (if (n.editable) null else n.text?.trim()?.takeIf { it.isNotEmpty() }) ?: n.description?.trim()?.takeIf { it.isNotEmpty() }

    // ---- system kinds (null: done; else why it failed) ----

    private fun openApp(name: String): String? {
        val pm = context.packageManager
        val apps = launcherActivities(pm).mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) null else ri.loadLabel(pm).toString() to pkg
        }
        val pkg = matchApp(name, apps) ?: return "no such app"
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "no such app"
        return startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launcherActivities(pm: PackageManager): List<ResolveInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            queryLegacy(pm, launcher)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLegacy(pm: PackageManager, intent: Intent): List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

    private fun media(key: String?): String? {
        val code = when (key) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "unknown key"
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return "no audio"
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    private fun volume(dir: String?): String? {
        val direction = when (dir) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return "unknown direction"
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return "no audio"
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            null
        } catch (e: SecurityException) {
            "not allowed" // e.g. Do Not Disturb
        }
    }

    private fun timer(seconds: Int, label: String?): String? {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startClock(intent)
    }

    private fun alarm(hour: Int, minute: Int, label: String?): String? {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startClock(intent)
    }

    private fun startClock(intent: Intent): String? {
        if (intent.resolveActivity(context.packageManager) == null) return "no clock app"
        return startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The phone's web search; without one, Google in the browser. */
    private fun webSearch(query: String): String? {
        val pm = context.packageManager
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (search.resolveActivity(pm) != null) return startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_SEARCH + Uri.encode(query)))
        if (view.resolveActivity(pm) == null) return "no browser"
        return startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Starts an activity from the service: allowed in the background because she holds an overlay
     * window (and a bound accessibility service). Null when started, else why not.
     */
    private fun startActivity(intent: Intent): String? = try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "no app for that"
    } catch (e: SecurityException) {
        "not allowed"
    }

    companion object {
        private const val TAG = "MayaActions"
        const val DONE = "done"
        const val DECLINED = "declined"
        const val TIMEOUT = "timeout"
        const val FAILED = "failed"
        const val BLOCKED = "blocked"

        /** Kinds that act inside the app in front, through the accessibility service. */
        private val SCREEN_KINDS = setOf("press", "type", "scroll")

        /** Her own wall-clock cap per action; asking adds [CONFIRM_MS]. */
        private const val CAP_MS = 20_000L
        /** No answer to her question in this long: timeout. */
        private const val CONFIRM_MS = 15_000L
        /** After ✔: the tap on her bubble ends before the screen is read again (it may count as the active window). */
        private const val AFTER_YES_MS = 500L
        /** Her poke starts this long before the click lands. */
        private const val POKE_LEAD_MS = 250L
        /** She holds the `type` pose this long before the text appears. */
        private const val TYPE_POSE_MS = 1500L
        /** A fallback tap waits this long for her window to let touches through, which lasts [PASS_THROUGH_MS]. */
        private const val PASS_THROUGH_LEAD_MS = 150L
        private const val PASS_THROUGH_MS = 600L
        private const val PRESS_LABEL_MAX = 30
        private const val TYPE_PREVIEW_MAX = 60
        private const val GOOGLE_SEARCH = "https://www.google.com/search?q="

        /** [s] trimmed to at most [max] chars ("…" included when cut), never splitting a surrogate pair. */
        internal fun clip(s: String, max: Int): String {
            val t = s.trim()
            if (t.length <= max) return t
            return take(t, max - 1).trimEnd() + "…"
        }

        /** The first [max] chars of [s], then "…" when there is more; never splitting a surrogate pair. */
        internal fun preview(s: String, max: Int): String = if (s.length <= max) s else take(s, max) + "…"

        private fun take(s: String, n: Int): String {
            val t = s.take(n)
            return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
        }
    }
}
