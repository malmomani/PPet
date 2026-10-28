package com.maya.app.senses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.maya.app.Prefs
import com.maya.app.overlay.SpriteMetrics
import com.maya.core.Box
import com.maya.core.DoNotWatch
import com.maya.core.Layout
import com.maya.core.TEASE_TAGS
import com.maya.core.TextNode
import com.maya.core.UiNode
import com.maya.core.buildLayout
import com.maya.core.extractLines
import com.maya.core.matchLabel
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * Maya's eyes and hands. Needs the special "Accessibility" permission (config:
 * res/xml/accessibility_config.xml). Reads only the active window's node tree plus the window
 * list (to find the keyboard), and only while MayaService runs with "Let Maya read my screen" on
 * ([ScreenBridge.sink] set):
 * - on events it rebuilds the layout (platforms + tease targets + picture boxes) at most every
 *   500 ms and reports it, and app changes, to the sink;
 * - it reads the visible text only when MayaService asks for a [glance];
 * - it takes a [screenshot] (Android 11+) only when MayaService asks for one; MayaService decides
 *   whether the screen may be looked at;
 * - as [PhoneHands] it clicks, types, scrolls or taps only when the action runner asks (with "Let
 *   Maya use my phone" on); the runner decides whether it may, and checks again in the same pass.
 * Never reads password nodes, the content of editable nodes (or anything inside them), do-not-watch
 * apps, Maya herself, system UI or the lock screen. Never logs screen text or screenshots, never
 * writes a screenshot to disk. Callbacks and every call into it happen on the main thread.
 */
class MayaAccessibilityService : AccessibilityService(), GlanceSource, PhoneHands {
    private class Seen(val ui: List<UiNode>, val text: List<TextNode>)

    /**
     * A visible node of one live pass, for acting on it. [clickIdx] is the index (in the pass) of
     * the nearest clickable, enabled node at or above it, -1 if none. [field] is an editable,
     * non-password node.
     */
    private class LiveNode(
        val ui: UiNode,
        val info: AccessibilityNodeInfo,
        val clickIdx: Int,
        val field: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
    )

    /**
     * One pass over the active window: its package and visible nodes (parents before children).
     * Not [readable] (no nodes) for apps she never reads: do-not-watch, Settings, system UI, Maya
     * herself, or a locked or dark screen.
     */
    private class LivePass(val pkg: String?, val readable: Boolean, val nodes: List<LiveNode>)

    private val main = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()

    /** The sink our state was last reported to; a new sink (a new MayaService run) is told everything again. */
    private var boundSink: ScreenSink? = null
    /** Read from Prefs whenever a new sink is bound (the service restarts when the list is saved). */
    private var doNotWatch = DoNotWatch(emptySet())
    /** The app last reported to [boundSink] with onApp. */
    private var reportedPkg: String? = null
    /** The last layout of another app; re-sent (trimmed) while Maya's own window is in front. */
    private var appLayout: Layout = Layout.EMPTY
    /** The keyboard top seen by the last rebuild, to tell a keyboard move from Maya's own window moving. */
    private var lastKeyboardTop: Int? = null

    private var lastLayoutMs = 0L
    private var layoutPending = false
    private val layoutTask = Runnable {
        layoutPending = false
        rebuild()
    }

    private var metrics: SpriteMetrics? = null
    private var metricsDensity = 0f

    /** Decodes and encodes screenshots off the main thread; null until connected and after onDestroy. */
    private var encoder: ExecutorService? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (encoder?.isShutdown != false) encoder = Executors.newSingleThreadExecutor()
        ScreenBridge.service = this
        ScreenBridge.hands = this
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ScreenBridge.sink == null || !ScreenBridge.active) return
        // Her own windows move ~30 times a second and her bubble changes: never a reason to re-read
        // the app. A window of hers appearing (the chat) still counts.
        if (event.packageName?.toString() == packageName &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }
        // Only window bounds changed (her overlay moving, usually): matters only if the keyboard moved.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_BOUNDS &&
            !keyboardMoved()
        ) {
            return
        }
        scheduleLayout()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        encoder?.shutdown()
        encoder = null
        super.onDestroy()
    }

    override fun refresh() {
        if (ScreenBridge.sink == null || !ScreenBridge.active) return
        scheduleLayout()
    }

    override fun glance(): GlanceData? {
        val sink = ScreenBridge.sink ?: return null
        bindTo(sink)
        return try {
            val root = rootInActiveWindow ?: return null
            try {
                val pkg = root.packageName?.toString() ?: return null
                if (!readable(pkg)) return null
                val seen = collect(root, withText = true)
                val tags = layoutOf(seen.ui, keyboardTop()).targets.map { it.tag }.toSet()
                GlanceData(pkg, appName(pkg), extractLines(seen.text), TEASE_TAGS.filter { it in tags })
            } finally {
                release(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not glance at the screen: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun screenshot(pkg: String, onResult: (String?) -> Unit) {
        val executor = encoder
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || executor == null) {
            onResult(null)
            return
        }
        Screenshots.take(this, pkg, executor, onResult)
    }

    override fun appName(pkg: String): String = labels.getOrPut(pkg) { clampAppName(labelOf(pkg), pkg) }

    override fun blockedAppVisible(): Boolean {
        val sink = ScreenBridge.sink ?: return false
        bindTo(sink)
        return appWindowPackages()?.any { it != null && doNotWatch.isBlocked(it) } ?: false
    }

    override fun frontApp(): String? {
        if (ScreenBridge.sink == null) return null
        return appWindowPackages()?.firstOrNull { it != null && it != packageName }
    }

    /**
     * Live check right before (and right after) a screenshot: [pkg] is readable and is the active
     * window, and no visible app window belongs to a do-not-watch app, Settings, system UI or Maya
     * (or to an app whose window can't be identified).
     */
    internal fun mayCapture(pkg: String): Boolean {
        val sink = ScreenBridge.sink ?: return false
        bindTo(sink)
        return try {
            if (!readable(pkg)) return false
            val root = rootInActiveWindow ?: return false
            val active = try {
                root.packageName?.toString()
            } finally {
                release(root)
            }
            if (active != pkg) return false
            val apps = appWindowPackages() ?: return false
            apps.none { it == null || it == packageName || it in NEVER_CAPTURE || doNotWatch.isBlocked(it) }
        } catch (e: Exception) {
            Log.w(TAG, "could not check the windows: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Packages of the visible app windows (split screen panes, pop-up activities), topmost first;
     * an entry is null when a window's package can't be read. Null when the window list is not
     * available. Reads nothing but each window's package name.
     */
    private fun appWindowPackages(): List<String?>? {
        val list: List<AccessibilityWindowInfo> = try {
            windows
        } catch (e: Exception) {
            null
        } ?: return null
        val out = ArrayList<String?>()
        try {
            for (w in list.sortedByDescending { it.layer }) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root
                if (root == null) {
                    out.add(null)
                    continue
                }
                try {
                    out.add(root.packageName?.toString())
                } finally {
                    release(root)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not list the windows: ${e.javaClass.simpleName}")
            return null
        } finally {
            for (w in list) release(w)
        }
        return out
    }

    private fun detach() {
        main.removeCallbacks(layoutTask)
        layoutPending = false
        boundSink = null
        if (ScreenBridge.service === this) ScreenBridge.service = null
        if (ScreenBridge.hands === this) ScreenBridge.hands = null
    }

    private fun bindTo(sink: ScreenSink) {
        if (sink === boundSink) return
        boundSink = sink
        reportedPkg = null
        appLayout = Layout.EMPTY
        lastKeyboardTop = null
        doNotWatch = DoNotWatch(Prefs(this).doNotWatch)
    }

    // ---- hands ("Let Maya use my phone") ----

    override fun activePackage(): String? {
        if (ScreenBridge.sink == null) return null
        return try {
            val root = rootInActiveWindow ?: return null
            try {
                root.packageName?.toString()
            } finally {
                release(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the active window: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun findButton(target: String): PressNode? = withLiveTree { pass ->
        matchButton(pass, target)?.let { pressNodeOf(pass, it) }
    }

    override fun click(target: String, allow: (String?, PressNode) -> Boolean): Clicked = withLiveTree { pass ->
        val n = matchButton(pass, target)
        val press = n?.let { pressNodeOf(pass, it) }
        when {
            !pass.readable -> Clicked(HandResult.REFUSED, null)
            n == null || press == null -> Clicked(HandResult.NOT_FOUND, null)
            !allow(pass.pkg, press) -> Clicked(HandResult.REFUSED, press)
            // the node itself when clickable, else its nearest clickable ancestor
            pass.nodes[n.clickIdx].info.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> Clicked(HandResult.DONE, press)
            else -> Clicked(HandResult.FAILED, press)
        }
    } ?: Clicked(HandResult.NOT_FOUND, null)

    override fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit): Boolean {
        if (ScreenBridge.sink == null) return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)

            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }
        return try {
            dispatchGesture(gesture, callback, main)
        } catch (e: Exception) {
            Log.w(TAG, "could not tap: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun findField(into: String?): UiNode? = withLiveTree { pass -> fieldOf(pass, into)?.ui }

    override fun setText(into: String?, text: String, allow: (String?, UiNode) -> Boolean): HandResult = withLiveTree { pass ->
        val f = fieldOf(pass, into)
        when {
            !pass.readable -> HandResult.REFUSED
            f == null -> HandResult.NOT_FOUND
            // read again in this pass: never a password field, whatever the app did meanwhile
            f.info.isPassword || !f.info.isEditable || !allow(pass.pkg, f.ui) -> HandResult.REFUSED
            else -> {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                if (f.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) HandResult.DONE else HandResult.FAILED
            }
        }
    } ?: HandResult.NOT_FOUND

    override fun scroll(forward: Boolean, allow: (String?) -> Boolean): HandResult = withLiveTree { pass ->
        val s = pass.nodes.filter { it.scrollable }.maxByOrNull { it.ui.bounds.width.toLong() * it.ui.bounds.height }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        when {
            !pass.readable || !allow(pass.pkg) -> HandResult.REFUSED
            s == null -> HandResult.NOT_FOUND
            s.info.performAction(action) -> HandResult.DONE
            else -> HandResult.FAILED
        }
    } ?: HandResult.NOT_FOUND

    /**
     * Runs [block] over a fresh pass of the active window, then releases every node it read. Null
     * without a sink (the screen sense is off), without an active window, or when reading failed.
     * The nodes of an app she never reads are not read at all (the pass is not readable).
     */
    private fun <T> withLiveTree(block: (LivePass) -> T): T? {
        val sink = ScreenBridge.sink ?: return null
        bindTo(sink)
        val all = ArrayList<AccessibilityNodeInfo>()
        return try {
            val root = rootInActiveWindow ?: return null
            all.add(root)
            val pkg = root.packageName?.toString()
            val readable = pkg != null && pkg !in NEVER_CAPTURE && readable(pkg)
            block(LivePass(pkg, readable, if (readable) liveNodes(root, all) else emptyList()))
        } catch (e: Exception) {
            Log.w(TAG, "could not act on the screen: ${e.javaClass.simpleName}")
            null
        } finally {
            for (n in all) release(n)
        }
    }

    /**
     * Depth-first over the visible nodes from [root], at most [MAX_NODES]; every node read is added
     * to [all] (for releasing). Like [collect], password nodes give no text or description and
     * editable nodes no text, and neither is descended into. A node inside a clickable one counts
     * as clickable (a label in a button finds the button). An editable node's description is its
     * hint plus its content description (what `into` is matched against).
     */
    private fun liveNodes(root: AccessibilityNodeInfo, all: MutableList<AccessibilityNodeInfo>): List<LiveNode> {
        val out = ArrayList<LiveNode>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val r = Rect()
        stack.addLast(root to -1)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, parentClick) = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(r)
            val password = node.isPassword
            val editable = node.isEditable
            val clickIdx = if (node.isClickable && node.isEnabled) out.size else parentClick
            val description = when {
                password -> null
                editable -> fieldLabel(node)
                else -> node.contentDescription?.toString()
            }
            val ui = UiNode(
                bounds = Box(r.left, r.top, r.right, r.bottom),
                clickable = clickIdx >= 0,
                editable = editable,
                image = false,
                viewId = node.viewIdResourceName,
                description = description,
                text = if (password || editable) null else node.text?.toString(),
            )
            out.add(LiveNode(ui, node, clickIdx, editable && !password, node.isFocused, node.isScrollable))
            if (password || editable) continue
            // Pushed last-first so the first child is visited first.
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                all.add(child)
                stack.addLast(child to clickIdx)
            }
        }
        return out
    }

    /** What a text field says about itself: its hint and its content description (never its text). */
    private fun fieldLabel(node: AccessibilityNodeInfo): String? =
        listOfNotNull(node.hintText?.toString(), node.contentDescription?.toString())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }

    /** The node [target] means: core [matchLabel] over the pass (the smallest match wins). */
    private fun matchButton(pass: LivePass, target: String): LiveNode? {
        val ui = pass.nodes.map { it.ui }
        val m = matchLabel(target, ui) ?: return null
        return pass.nodes.getOrNull(ui.indexOfFirst { it === m })
    }

    private fun pressNodeOf(pass: LivePass, n: LiveNode): PressNode? =
        pass.nodes.getOrNull(n.clickIdx)?.let { PressNode(n.ui, it.ui) }

    /** The focused text field, else the one whose hint or description matches [into]; never a password field. */
    private fun fieldOf(pass: LivePass, into: String?): LiveNode? {
        val fields = pass.nodes.filter { it.field }
        fields.firstOrNull { it.focused }?.let { return it }
        if (into.isNullOrBlank()) return null
        // matchLabel only looks at clickable nodes; any field may be typed into
        val ui = fields.map { it.ui.copy(clickable = true) }
        val m = matchLabel(into, ui) ?: return null
        return fields.getOrNull(ui.indexOfFirst { it === m })
    }

    // ---- layout ----

    /** Trailing-edge throttle: at most one rebuild per [LAYOUT_GAP_MS], and the latest state always lands. */
    private fun scheduleLayout() {
        if (layoutPending) return
        layoutPending = true
        val wait = (lastLayoutMs + LAYOUT_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        main.postDelayed(layoutTask, wait)
    }

    private fun rebuild() {
        val sink = ScreenBridge.sink ?: return
        if (!ScreenBridge.active) return // overlay hidden or screen unusable: refresh() follows when back
        lastLayoutMs = SystemClock.uptimeMillis()
        bindTo(sink)
        try {
            val keyboardTop = keyboardTop()
            lastKeyboardTop = keyboardTop
            val root = rootInActiveWindow ?: return
            try {
                val pkg = root.packageName?.toString() ?: return
                if (pkg == packageName) {
                    // Maya's own window (the chat, her settings) is in front: the app behind is still
                    // "the app" (no onApp) and is not re-read; she keeps its platforms above her window
                    // and the keyboard.
                    sink.onLayout(pkg, trimmedAppLayout(root, keyboardTop), keyboardTop)
                    return
                }
                if (pkg != reportedPkg) {
                    reportedPkg = pkg
                    sink.onApp(pkg)
                }
                val layout = if (readable(pkg)) layoutOf(collect(root, withText = false).ui, keyboardTop) else Layout.EMPTY
                appLayout = layout
                sink.onLayout(pkg, layout, keyboardTop)
            } finally {
                release(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the screen: ${e.javaClass.simpleName}")
        }
    }

    private fun keyboardMoved(): Boolean = try {
        keyboardTop() != lastKeyboardTop
    } catch (e: Exception) {
        true // can't tell: rebuild, which logs the problem
    }

    /** [appLayout] without the platforms and targets at or below Maya's own window or the keyboard. */
    private fun trimmedAppLayout(ownRoot: AccessibilityNodeInfo, keyboardTop: Int?): Layout {
        val r = Rect()
        ownRoot.getBoundsInScreen(r)
        val cut = minOf(keyboardTop ?: Int.MAX_VALUE, r.top)
        return Layout(
            appLayout.platforms.filter { it.top < cut },
            appLayout.targets.filter { it.bounds.top < cut },
        )
    }

    private fun layoutOf(ui: List<UiNode>, keyboardTop: Int?): Layout {
        val sprite = spriteMetrics()
        val (screenW, screenH) = realScreenSize(this)
        return buildLayout(
            nodes = ui,
            screenW = screenW,
            spriteW = sprite.width,
            spriteH = sprite.height,
            statusBarH = statusBarHeight(),
            keyboardTop = keyboardTop,
            minImagePx = dp(MIN_IMAGE_DP),
            screenH = screenH,
        )
    }

    /** False for apps she must never read: do-not-watch, Maya herself, system UI, a locked or dark screen. */
    private fun readable(pkg: String): Boolean {
        if (pkg == packageName || pkg == SYSTEM_UI || doNotWatch.isBlocked(pkg)) return false
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: false
        return interactive && !locked
    }

    // ---- node tree ----

    /**
     * Depth-first over the visible nodes, at most [MAX_NODES] per pass. Password nodes give no
     * text or description and editable nodes no text; neither is descended into. Text nodes are
     * collected only when [withText].
     */
    private fun collect(root: AccessibilityNodeInfo, withText: Boolean): Seen {
        val ui = ArrayList<UiNode>()
        val text = ArrayList<TextNode>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val r = Rect()
        stack.addLast(root)
        var visited = 0
        try {
            while (stack.isNotEmpty() && visited < MAX_NODES) {
                val node = stack.removeLast()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        node.getBoundsInScreen(r)
                        val password = node.isPassword
                        val editable = node.isEditable
                        val description = if (password) null else node.contentDescription?.toString()
                        val label = if (password || editable) null else node.text?.toString()
                        ui.add(
                            UiNode(
                                bounds = Box(r.left, r.top, r.right, r.bottom),
                                clickable = node.isClickable,
                                editable = editable,
                                image = isImage(node),
                                viewId = node.viewIdResourceName,
                                description = description,
                                text = label,
                            ),
                        )
                        if (!password && !editable) {
                            if (withText) {
                                val line = if (!label.isNullOrBlank()) label else description
                                if (!line.isNullOrBlank()) text.add(TextNode(line, r.top, r.left, password = false, editable = false))
                            }
                            // Pushed last-first so the first child is visited first.
                            for (i in node.childCount - 1 downTo 0) {
                                val child = node.getChild(i)
                                if (child != null) stack.addLast(child)
                            }
                        }
                    }
                } finally {
                    if (node !== root) release(node)
                }
            }
        } finally {
            while (stack.isNotEmpty()) {
                val left = stack.removeLast()
                if (left !== root) release(left)
            }
        }
        return Seen(ui, text)
    }

    private fun isImage(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.endsWith("ImageView") || cls.endsWith("ImageButton") || cls == "android.widget.Image"
    }

    /** Top edge (screen px) of the input-method window, or null when no keyboard is showing. */
    private fun keyboardTop(): Int? {
        val list: List<AccessibilityWindowInfo>? = windows
        if (list == null) return null
        val r = Rect()
        var top: Int? = null
        for (w in list) {
            if (top == null && w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                w.getBoundsInScreen(r)
                if (r.height() > 0) top = r.top
            }
            release(w)
        }
        return top
    }

    // ---- sizes ----

    private fun spriteMetrics(): SpriteMetrics {
        val density = resources.displayMetrics.density
        val cached = metrics
        if (cached != null && density == metricsDensity) return cached
        val fresh = SpriteMetrics.forContext(this)
        metrics = fresh
        metricsDensity = density
        return fresh
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) {
            try {
                resources.getDimensionPixelSize(id)
            } catch (e: Resources.NotFoundException) {
                0
            }
        } else {
            0
        }
        return if (px > 0) px else dp(STATUS_BAR_FALLBACK_DP)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun labelOf(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val TAG = "MayaScreen"
        const val SYSTEM_UI = "com.android.systemui"
        /** Never in a screenshot (besides do-not-watch apps and Maya herself). */
        val NEVER_CAPTURE = setOf(SYSTEM_UI, "com.android.settings")
        const val MAX_NODES = 600
        /** A fallback tap (when a button won't take ACTION_CLICK) lasts this long. */
        const val TAP_MS = 50L
        const val LAYOUT_GAP_MS = 500L
        const val MIN_IMAGE_DP = 48f
        const val STATUS_BAR_FALLBACK_DP = 24f
    }
}

/**
 * Screenshots for [MayaAccessibilityService.screenshot] (Android 11+, `canTakeScreenshot` in the
 * config). Kept out of the service class so older phones never load the API 30 types. Everything
 * stays in memory; neither the image nor its bytes are logged.
 */
@TargetApi(Build.VERSION_CODES.R)
private object Screenshots {
    private const val TAG = "MayaSight"
    private const val MAX_SIDE_PX = 1024
    private const val START_QUALITY = 70
    private const val MIN_QUALITY = 30
    private const val QUALITY_STEP = 10

    /**
     * Asks the system for a screenshot of the default display while [pkg] may be captured
     * ([MayaAccessibilityService.mayCapture], checked before asking and again before decoding);
     * [onResult] gets the base64 JPEG or null, once, on the main thread. Decoding, scaling and
     * encoding run on [executor]. The system allows about one screenshot per second: a refusal
     * (e.g. ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) is a failure.
     */
    fun take(
        service: MayaAccessibilityService,
        pkg: String,
        executor: ExecutorService,
        onResult: (String?) -> Unit,
    ) {
        if (!service.mayCapture(pkg)) {
            onResult(null)
            return
        }
        val main = service.mainExecutor
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                // The screen may have changed while the system took it: check again before decoding.
                if (!service.mayCapture(pkg)) {
                    closeQuietly(screenshot)
                    onResult(null)
                    return
                }
                try {
                    executor.execute {
                        val image = encode(screenshot)
                        main.execute { onResult(image) }
                    }
                } catch (e: RejectedExecutionException) {
                    closeQuietly(screenshot)
                    onResult(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "no screenshot (error $errorCode)")
                onResult(null)
            }
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "no screenshot: ${e.javaClass.simpleName}")
            onResult(null)
        }
    }

    private fun closeQuietly(shot: AccessibilityService.ScreenshotResult) {
        try {
            shot.hardwareBuffer.close()
        } catch (e: Exception) {
            // Already closed.
        }
    }

    /**
     * Hardware buffer -> software bitmap -> scaled -> JPEG of at most [MAX_SCREENSHOT_BYTES] ->
     * base64; null on failure. Runs on the encoder thread; always closes the buffer.
     */
    private fun encode(shot: AccessibilityService.ScreenshotResult): String? {
        val buffer = shot.hardwareBuffer
        var hardware: Bitmap? = null
        var software: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return null
            software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            hardware.recycle()
            hardware = null
            buffer.close()
            scaled = scaleDown(software)
            compress(scaled)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } catch (e: Exception) {
            Log.w(TAG, "could not encode the screenshot: ${e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "could not encode the screenshot: out of memory")
            null
        } finally {
            hardware?.recycle()
            if (scaled != null && scaled !== software) scaled.recycle()
            software?.recycle()
            if (!buffer.isClosed) buffer.close()
        }
    }

    /** [src] itself when its long side is at most [MAX_SIDE_PX], else a smaller copy. */
    private fun scaleDown(src: Bitmap): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= MAX_SIDE_PX) return src
        val w = (src.width.toLong() * MAX_SIDE_PX / long).toInt().coerceAtLeast(1)
        val h = (src.height.toLong() * MAX_SIDE_PX / long).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** JPEG at quality 70, lowered by 10 until it fits [MAX_SCREENSHOT_BYTES]; null if even 30 is too big. */
    private fun compress(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        var quality = START_QUALITY
        while (quality >= MIN_QUALITY) {
            out.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            if (out.size() <= MAX_SCREENSHOT_BYTES) return out.toByteArray()
            quality -= QUALITY_STEP
        }
        return null
    }
}

private const val MAX_APP_CHARS = 100

/**
 * The app name for a glance: 1–[MAX_APP_CHARS] chars (the brain rejects anything else). The label,
 * trimmed and cut without splitting a surrogate pair; the package name when there is no label.
 */
internal fun clampAppName(label: String?, pkg: String): String {
    val name = capChars(label?.trim().orEmpty(), MAX_APP_CHARS).trim()
    if (name.isNotEmpty()) return name
    return capChars(pkg.trim(), MAX_APP_CHARS).ifEmpty { "app" }
}

private fun capChars(s: String, max: Int): String {
    val t = s.take(max)
    return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
}

/** Node infos must be recycled before Android 13; from 13 on recycling is a no-op. */
@Suppress("DEPRECATION")
private fun release(n: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    try {
        n.recycle()
    } catch (e: IllegalStateException) {
        // Already recycled.
    }
}

@Suppress("DEPRECATION")
private fun release(w: AccessibilityWindowInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    try {
        w.recycle()
    } catch (e: IllegalStateException) {
        // Already recycled.
    }
}
