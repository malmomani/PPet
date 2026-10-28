package com.maya.app.senses

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Maya's eyes. Needs the special "Accessibility" permission (config: res/xml/accessibility_config.xml;
 * it never performs gestures or clicks). Reads only the active window's node tree plus the window
 * list (to find the keyboard), and only while MayaService runs with "Let Maya read my screen" on
 * ([ScreenBridge.sink] set):
 * - on events it rebuilds the layout (platforms + tease targets + picture boxes) at most every
 *   500 ms and reports it, and app changes, to the sink;
 * - it reads the visible text only when MayaService asks for a [glance];
 * - it takes a [screenshot] (Android 11+) only when MayaService asks for one; MayaService decides
 *   whether the screen may be looked at.
 * Never reads password nodes, the content of editable nodes (or anything inside them), do-not-watch
 * apps, Maya herself, system UI or the lock screen. Never logs screen text or screenshots, never
 * writes a screenshot to disk. Callbacks and every call into it happen on the main thread.
 */
class MayaAccessibilityService : AccessibilityService(), GlanceSource {
    private class Seen(val ui: List<UiNode>, val text: List<TextNode>)

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenBridge.service = this
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

    override fun screenshot(onResult: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        Screenshots.take(this, onResult)
    }

    override fun appName(pkg: String): String = labels.getOrPut(pkg) { clampAppName(labelOf(pkg), pkg) }

    private fun detach() {
        main.removeCallbacks(layoutTask)
        layoutPending = false
        boundSink = null
        if (ScreenBridge.service === this) ScreenBridge.service = null
    }

    private fun bindTo(sink: ScreenSink) {
        if (sink === boundSink) return
        boundSink = sink
        reportedPkg = null
        appLayout = Layout.EMPTY
        lastKeyboardTop = null
        doNotWatch = DoNotWatch(Prefs(this).doNotWatch)
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
        const val MAX_NODES = 600
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
     * Asks the system for a screenshot of the default display; [onResult] gets the JPEG or null,
     * once, on the main thread. The system allows about one per second: a refusal (e.g.
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) is a failure.
     */
    fun take(service: AccessibilityService, onResult: (ByteArray?) -> Unit) {
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                onResult(encode(screenshot))
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

    /** Hardware buffer -> software bitmap -> scaled -> JPEG of at most [MAX_SCREENSHOT_BYTES]; null on failure. */
    private fun encode(shot: AccessibilityService.ScreenshotResult): ByteArray? {
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
            compress(scaled)
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
