package com.maya.app.senses

import com.maya.core.Layout

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
}

/** One screen glance: [app] is the label (1–100 chars), [lines] the visible text, [targets] tease tags. */
data class GlanceData(val packageName: String, val app: String, val lines: List<String>, val targets: List<String>)

/**
 * Process-local hand-off between [MayaAccessibilityService] and MayaService, like
 * [NotificationBridge]. The service sets [sink] while it runs with "Let Maya read my screen" on
 * and clears it (only if still its own) when it stops; without a sink the accessibility service
 * reads nothing. The accessibility service sets [service] while it is connected. Both are only
 * used on the main thread.
 */
object ScreenBridge {
    @Volatile
    var sink: ScreenSink? = null

    @Volatile
    var service: GlanceSource? = null

    /**
     * Set by MayaService: false while her overlay is not visible (hidden from the notification, not
     * shown yet) or the screen is off or locked. The accessibility service rebuilds no layouts
     * then; MayaService calls [GlanceSource.refresh] when it turns true again.
     */
    @Volatile
    var active: Boolean = false
}
