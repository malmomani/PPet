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
import android.os.PowerManager
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
import kotlin.math.abs

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
     * her poke starts, false when she can't get there, null when she went away (hidden, screen
     * off). Where she can't walk now (keyboard open, a call, a button below her floor) she pokes
     * where she stands. False when she isn't on screen; then it is never called.
     */
    fun press(box: Box, onResult: (Boolean?) -> Unit): Boolean

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
 * (core [blockedReason] on the live active window, the keyguard and "Let Maya use my phone", and
 * nothing at all while a do-not-watch app is in front: [awayHere]), resolves the target on screen,
 * asks before anything risky (core [isRisky] over everything the click would press), then acts:
 * system kinds through Android APIs, press/type/scroll through the accessibility service
 * ([hands]), checking again in the same pass as the click or the typing. A yes is bound to what
 * was asked about: if the button or field found afterwards is not the same one, it fails
 * ("screen changed"). A press walks her body to the button first ([body]). An app she starts
 * must be in front 1.5 s later ([frontApp]). Never logs what is typed, searched or read on screen.
 * Main thread only.
 */
class ActionRunner(
    private val context: Context,
    private val prefs: Prefs,
    private val emit: (Event) -> Unit,
    private val body: () -> ActionBody?,
    private val hands: () -> PhoneHands?,
    private val doNotWatch: (String) -> Boolean,
    /** True while she stepped away from a do-not-watch app, or one is in front: she does nothing. */
    private val awayHere: () -> Boolean,
    /** The app in front now (screen sense, else usage access), or null when neither can tell. */
    private val frontApp: () -> String?,
) {
    /** What the user said yes to pressing: [label] of the matched node, the click target's id and bounds. */
    private data class ButtonSig(val pkg: String?, val viewId: String?, val label: String?, val bounds: Box)

    /** What the user said yes to typing into: the app and the field's id (else its hint). */
    private data class FieldSig(val pkg: String?, val id: String?)

    /** A system kind: why it failed (null: it started), and the package it started, to check it came to the front. */
    private class Outcome(val failure: String?, val launched: String? = null)

    private class Job(val act: Act) {
        val startMs: Long = SystemClock.uptimeMillis()
        /** The user said yes: the button or field must still be [button] / [field]. */
        var confirmed = false
        var button: ButtonSig? = null
        var field: FieldSig? = null
        /** Her wall-clock cap; longer once she asks. */
        var capMs = CAP_MS
    }

    /** What an `allow` check on a button decided: refused (and why), or the button is not the one asked about. */
    private class Check {
        var refused: Pair<String, String>? = null
        var changed = false
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
            "media" -> system(j) { Outcome(media(act.key)) }
            "volume" -> system(j) { Outcome(volume(act.dir)) }
            "timer" -> system(j) { Outcome(timer(act.seconds ?: 0, act.label)) }
            "alarm" -> system(j) { Outcome(alarm(act.hour ?: 0, act.minute ?: 0, act.label)) }
            "web_search" -> system(j) { webSearch(act.query.orEmpty()) }
            "scroll" -> scroll(j)
            "press" -> press(j)
            "type" -> type(j)
            else -> finish(j, FAILED, "unknown action")
        }
    }

    /**
     * Why [act] may not run in the active window of [pkg] (status to detail), or null. Nothing runs
     * while a do-not-watch app is in front; phone kinds need the accessibility service (Screen
     * access and "Let Maya read my screen").
     */
    private fun refusal(act: Act, pkg: String?): Pair<String, String>? {
        val reason = blockedReason(act, pkg, context.packageName, doNotWatch, keyguardLocked(), prefs.allowActions)
        if (reason == "off" || reason == "locked") return BLOCKED to reason
        if (awayHere()) return BLOCKED to "not here"
        if (act.kind in SCREEN_KINDS && hands() == null) return FAILED to "no screen access"
        return reason?.let { BLOCKED to it }
    }

    /** Locked, or the screen is off (nobody can see her ask). */
    private fun keyguardLocked(): Boolean {
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        return locked || !interactive
    }

    /**
     * A system kind: failed, or done; an app it started must be in front [LAUNCH_CHECK_MS] later
     * (else Android blocked the start), when the front app can be told.
     */
    private fun system(j: Job, action: () -> Outcome) {
        val out = action()
        val failure = out.failure
        val launched = out.launched
        when {
            failure != null -> finish(j, FAILED, failure)
            launched == null -> finish(j, DONE, null)
            else -> later(j, LAUNCH_CHECK_MS) {
                val front = frontApp()
                if (front == null || front == launched) finish(j, DONE, null) else finish(j, FAILED, "android blocked it")
            }
        }
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
     * press: find the button; risky → ask first, binding the yes to that button, and start over
     * after a yes (the button found then must be the same one); her body walks to it and pokes it;
     * then it is found again, checked again and clicked (a tap gesture at its centre when it won't
     * take a click).
     */
    private fun press(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        val target = j.act.target.orEmpty()
        val found = h.findButton(target) ?: return finish(j, FAILED, "not on screen")
        if (j.confirmed) {
            val sig = j.button
            if (sig == null || !matches(sig, found)) return finish(j, FAILED, "screen changed")
        } else if (risky(j.act, found)) {
            j.button = sigOf(found)
            return ask(j, pressQuestion(j.act, found)) { press(j) }
        }
        val b = body()
        if (b == null || !b.isShowing) return finish(j, FAILED, "hidden")
        val walking = b.press(found.node.bounds) { reached ->
            callback(j) {
                when (reached) {
                    true -> later(j, POKE_LEAD_MS) { click(j) }
                    false -> finish(j, FAILED, "not on screen")
                    null -> finish(j, FAILED, "hidden")
                }
            }
        }
        if (!walking) finish(j, FAILED, "hidden")
    }

    private fun click(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        val check = Check()
        val clicked: Clicked = h.click(j.act.target.orEmpty()) { pkg, node -> allowPress(j, check, pkg, node, null) }
        val node = clicked.target
        when (clicked.result) {
            HandResult.DONE -> finish(j, DONE, null)
            HandResult.NOT_FOUND -> finish(j, FAILED, "not on screen")
            HandResult.REFUSED -> pressRefused(j, check)
            HandResult.FAILED -> if (node != null) tapFallback(j, h, node.clickable.bounds) else finish(j, FAILED, "couldn't press it")
        }
    }

    /**
     * The button won't take ACTION_CLICK: with touches passing through her, a tap gesture at its
     * centre, found and checked again in the same pass (it must not have moved from [near]).
     */
    private fun tapFallback(j: Job, h: PhoneHands, near: Box) {
        body()?.letTouchesThrough(PASS_THROUGH_MS)
        later(j, PASS_THROUGH_LEAD_MS) {
            val check = Check()
            val result = h.tap(j.act.target.orEmpty(), { pkg, node -> allowPress(j, check, pkg, node, near) }) { ok ->
                callback(j) { if (ok) finish(j, DONE, null) else finish(j, FAILED, "couldn't press it") }
            }
            when (result) {
                HandResult.DONE -> Unit // the gesture's callback finishes it
                HandResult.NOT_FOUND -> finish(j, FAILED, "not on screen")
                HandResult.REFUSED -> pressRefused(j, check)
                HandResult.FAILED -> finish(j, FAILED, "couldn't press it")
            }
        }
    }

    /**
     * The check in the same pass as a click or a tap: allowed here, and the button is the one the
     * user said yes to (or, without a yes, still not risky), and (for a tap) still near [near].
     */
    private fun allowPress(j: Job, check: Check, pkg: String?, node: PressNode, near: Box?): Boolean {
        val refused = refusal(j.act, pkg)
        check.refused = refused
        if (refused != null) return false
        val sig = j.button
        val same = if (j.confirmed) sig != null && pkg == sig.pkg && matches(sig, node) else !risky(j.act, node)
        check.changed = !same || (near != null && !close(near, node.clickable.bounds))
        return !check.changed
    }

    private fun pressRefused(j: Job, check: Check) {
        if (check.changed) finish(j, FAILED, "screen changed") else refuse(j, check.refused)
    }

    /**
     * type: find the field; always ask first, binding the yes to that field; after a yes the field
     * found must be the same one; then the `type` pose, checked again, and the text set.
     */
    private fun type(j: Job) {
        val h = hands() ?: return finish(j, FAILED, "no screen access")
        val into = j.act.into
        val text = j.act.text.orEmpty()
        val field = h.findField(into) ?: return finish(j, FAILED, "no text field")
        if (j.confirmed) {
            if (j.field != fieldSigOf(field.pkg, field.node)) return finish(j, FAILED, "screen changed")
        } else if (isRisky(j.act, field.node)) {
            j.field = fieldSigOf(field.pkg, field.node)
            return ask(j, context.getString(R.string.ask_type) + "\n“" + preview(text, TYPE_PREVIEW_MAX) + "”") { type(j) }
        }
        val b = body()
        if (b == null || !b.isShowing) return finish(j, FAILED, "hidden")
        b.setActPose("type")
        later(j, TYPE_POSE_MS) {
            b.setActPose(null)
            if (!b.isShowing) return@later finish(j, FAILED, "hidden")
            var refused: Pair<String, String>? = null
            var changed = false
            val result = h.setText(into, text) { pkg, node ->
                refused = refusal(j.act, pkg)
                changed = refused == null && j.confirmed && j.field != fieldSigOf(pkg, node)
                refused == null && !changed
            }
            when (result) {
                HandResult.DONE -> finish(j, DONE, null)
                HandResult.NOT_FOUND -> finish(j, FAILED, "no text field")
                HandResult.REFUSED -> if (changed) finish(j, FAILED, "screen changed") else refuse(j, refused)
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

    /**
     * "Can I press {label}?" naming what makes it risky: the label of the first risky node the click
     * would press (the matched node first), else the matched node's, else the target; at most 30 chars.
     */
    private fun pressQuestion(act: Act, found: PressNode): String {
        val riskyLabel = (listOf(found.node) + found.subtree).firstOrNull { isRisky(act, it) && labelOf(it) != null }
        val label = riskyLabel?.let { labelOf(it) } ?: labelOf(found.node) ?: act.target.orEmpty()
        return context.getString(R.string.ask_press, clip(label, PRESS_LABEL_MAX))
    }

    /** A press is risky when anything its click presses is (core [isRisky]): the button and every node inside it. */
    private fun risky(act: Act, found: PressNode): Boolean =
        isRisky(act, found.node) || isRisky(act, found.clickable) || found.subtree.any { isRisky(act, it) }

    private fun sigOf(found: PressNode) = ButtonSig(found.pkg, found.clickable.viewId, labelOf(found.node), found.clickable.bounds)

    /** [found] is the button [sig] describes: same app, click target id and label, about the same place. */
    private fun matches(sig: ButtonSig, found: PressNode): Boolean =
        found.pkg == sig.pkg && found.clickable.viewId == sig.viewId && labelOf(found.node) == sig.label &&
            close(sig.bounds, found.clickable.bounds)

    /** Every edge within [SAME_PLACE_PX]. */
    private fun close(a: Box, b: Box): Boolean =
        abs(a.left - b.left) <= SAME_PLACE_PX && abs(a.top - b.top) <= SAME_PLACE_PX &&
            abs(a.right - b.right) <= SAME_PLACE_PX && abs(a.bottom - b.bottom) <= SAME_PLACE_PX

    private fun fieldSigOf(pkg: String?, field: UiNode) = FieldSig(pkg, field.viewId ?: field.description)

    /** What a button says: its text (not a field's: that is what the user typed), else its description. */
    private fun labelOf(n: UiNode): String? =
        (if (n.editable) null else n.text?.trim()?.takeIf { it.isNotEmpty() }) ?: n.description?.trim()?.takeIf { it.isNotEmpty() }

    // ---- system kinds (null: done; else why it failed) ----

    private fun openApp(name: String): Outcome {
        val pm = context.packageManager
        val apps = launcherActivities(pm).mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) null else ri.loadLabel(pm).toString() to pkg
        }
        val pkg = matchApp(name, apps) ?: return Outcome("no such app")
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return Outcome("no such app")
        return Outcome(startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), pkg)
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
    private fun webSearch(query: String): Outcome {
        val pm = context.packageManager
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_SEARCH + Uri.encode(query)))
        val intent = if (search.resolveActivity(pm) != null) search else view
        val handler = intent.resolveActivity(pm) ?: return Outcome("no browser")
        // A chooser ("android") can't be checked: the app picked from it is unknown.
        val launched = handler.packageName.takeIf { it != ANDROID_PACKAGE }
        return Outcome(startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), launched)
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
        private const val ANDROID_PACKAGE = "android"
        /** A started app must be in front this long after the start, else Android blocked it. */
        private const val LAUNCH_CHECK_MS = 1500L
        /** A button is still the one asked about when every edge moved at most this far. */
        private const val SAME_PLACE_PX = 48

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
