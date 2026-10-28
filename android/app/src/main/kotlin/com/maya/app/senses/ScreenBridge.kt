package com.maya.app.senses

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.maya.core.Layout
import com.maya.core.UiNode

/** Receives what [MayaAccessibilityService] sees. Called on the main thread. */
interface ScreenSink {
    /**
     * The foreground app's platforms and tease targets (Layout.EMPTY when it may not be read:
     * do-not-watch, system UI, the lock screen), plus the keyboard's top edge in screen pixels while
     * a keyboard is open. While Maya's own window is in front, [pkg] is Maya's package and the layout
     * is the last app's, without what lies at or below her window or the keyboard.
     */
    fun onLayout(pkg: String, layout: Layout, keyboardTop: Int?)

    /** The app in the foreground changed (Maya's own windows never count as a change). */
    fun onApp(pkg: String?)
}

/** The accessibility service, while it is connected. Called on the main thread. */
interface GlanceSource {
    /** The visible text and tease tags of the current app, or null when it may not or cannot be read. */
    fun glance(): GlanceData?

    /** Report the current app and layout to the sink soon (a sink was just attached). */
    fun refresh()

    /**
     * A screenshot of the whole screen while [pkg] is in front, as base64 JPEG (long side at most
     * 1024 px, at most [MAX_SCREENSHOT_BYTES] before base64), or null when it failed, the phone is
     * older than Android 11, or the screen may not be captured (checked live right before the
     * screenshot and again before it is decoded: [pkg] must be the active window and no
     * do-not-watch app, Settings, system UI or Maya window may be visible). [onResult] is called
     * once, on the main thread; decoding and encoding run on a background thread. The image lives
     * only in memory: never written to disk, never logged.
     */
    fun screenshot(pkg: String, onResult: (String?) -> Unit)

    /** The app's label for [pkg] (1–100 chars; the package name when it has none). */
    fun appName(pkg: String): String

    /**
     * True while any visible app window (split screen, a pop-up over another app) belongs to a
     * do-not-watch app. Only the windows' package names are looked at.
     */
    fun blockedAppVisible(): Boolean

    /** The package of the topmost visible app window other than Maya's own, or null when it can't tell. */
    fun frontApp(): String?
}

/**
 * A button found for a press in the active window: [node] is the node the target matched (a label
 * inside a button counts), [clickable] the node a click goes to ([node] itself or its nearest
 * clickable ancestor). Both are fresh from the live tree.
 */
data class PressNode(val node: UiNode, val clickable: UiNode)

/** How a [PhoneHands] call ended. */
enum class HandResult {
    /** The action was performed. */
    DONE,

    /** Nothing in the active window matched (or there is no active window). */
    NOT_FOUND,

    /** The caller's check refused the node or the app it is in; nothing was done. */
    REFUSED,

    /** The node was found and allowed, but Android would not perform the action. */
    FAILED,
}

/** A [PhoneHands.click]: its [result] and the node it found ([PressNode.node]'s centre is where a fallback tap goes). */
data class Clicked(val result: HandResult, val target: PressNode?)

/**
 * Maya's hands: the accessibility service acting in the active window (rootInActiveWindow) for the
 * action runner. Every call reads the live node tree afresh (nothing is kept between calls) and
 * never reads into password fields or the text of editable ones. The `allow` checks get the
 * package of the active window and the node, read in the same pass as the action, so a refusal
 * cannot be raced. Nothing read here is logged. Called on the main thread.
 */
interface PhoneHands {
    /** The package of the active window right now, or null when there is none. */
    fun activePackage(): String?

    /** The button [target] means in the active window (core matchLabel over the live tree), or null. */
    fun findButton(target: String): PressNode?

    /**
     * Finds [target] again and, when [allow] accepts the active window's package and the button,
     * clicks it: `ACTION_CLICK` on its clickable node, then on the clickable ancestors above that.
     */
    fun click(target: String, allow: (String?, PressNode) -> Boolean): Clicked

    /** A tap gesture at ([x], [y]) in screen pixels; [onDone] gets whether it completed. False if it could not be sent. */
    fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit): Boolean

    /**
     * The text field to type into: the focused editable field, else the editable field whose hint
     * or description matches [into]. Never a password field.
     */
    fun findField(into: String?): UiNode?

    /** Finds the field again ([findField]) and, when [allow] accepts, sets its text to [text] (ACTION_SET_TEXT). */
    fun setText(into: String?, text: String, allow: (String?, UiNode) -> Boolean): HandResult

    /** Scrolls the largest scrollable node of the active window forward ([forward]) or backward, when [allow] accepts the package. */
    fun scroll(forward: Boolean, allow: (String?) -> Boolean): HandResult
}

/** A screenshot JPEG never exceeds this many bytes (base64 ~533 000 chars; the brain drops more than 600 000). */
const val MAX_SCREENSHOT_BYTES = 400_000

/** The full screen size in px (width, height) of the default display in its current rotation. */
@Suppress("DEPRECATION")
internal fun realScreenSize(context: Context): Pair<Int, Int> {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    if (display != null) {
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) return Pair(dm.widthPixels, dm.heightPixels)
    }
    val dm = context.resources.displayMetrics
    return Pair(dm.widthPixels, dm.heightPixels)
}

/** One screen glance: [app] is the label (1–100 chars), [lines] the visible text, [targets] tease tags. */
data class GlanceData(val packageName: String, val app: String, val lines: List<String>, val targets: List<String>)

/**
 * Process-local hand-off between [MayaAccessibilityService] and MayaService, like
 * [NotificationBridge]. The service sets [sink] while it runs with "Let Maya read my screen" on
 * and clears it (only if still its own) when it stops; without a sink the accessibility service
 * reads nothing. The accessibility service sets [service] and [hands] while it is connected. All are only
 * used on the main thread.
 */
object ScreenBridge {
    @Volatile
    var sink: ScreenSink? = null

    @Volatile
    var service: GlanceSource? = null

    /** The accessibility service's hands (press, type, scroll), while it is connected. */
    @Volatile
    var hands: PhoneHands? = null

    /**
     * Set by MayaService: false while her overlay is not up (hidden from the notification, not
     * shown yet) or the screen is off or locked. The accessibility service rebuilds no layouts
     * then; MayaService calls [GlanceSource.refresh] when it turns true again. It stays true while
     * she has stepped away from a do-not-watch app, so the service still reports the app changing
     * (nothing of a do-not-watch app is ever read).
     */
    @Volatile
    var active: Boolean = false
}
