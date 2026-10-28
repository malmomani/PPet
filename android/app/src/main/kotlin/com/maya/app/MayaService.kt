package com.maya.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.maya.app.actions.ActionRunner
import com.maya.app.overlay.OverlayBody
import com.maya.app.senses.AppUsageSense
import com.maya.app.senses.DeviceSense
import com.maya.app.senses.NotificationBridge
import com.maya.app.senses.ScreenBridge
import com.maya.app.senses.ScreenSink
import com.maya.app.senses.realScreenSize
import com.maya.app.voice.VoicePlayer
import com.maya.core.Act
import com.maya.core.Action
import com.maya.core.AppOpened
import com.maya.core.Audio
import com.maya.core.DoNotWatch
import com.maya.core.Event
import com.maya.core.Glance
import com.maya.core.GlanceScheduler
import com.maya.core.Layout
import com.maya.core.Look
import com.maya.core.LookScheduler
import com.maya.core.NotificationFilter
import com.maya.core.NotificationInfo
import com.maya.core.Say
import com.maya.core.Screen
import com.maya.core.Sight
import com.maya.core.SightFailed
import com.maya.core.UserMessage
import com.maya.core.UserShush
import com.maya.core.isPictureHeavy

/** Implemented by the floating overlay (Task 10) and assigned to [MayaService.overlay]. */
interface OverlayHook {
    fun handle(a: Action)
    fun setConnected(on: Boolean)

    /** False while the screen is off or locked: the overlay pauses its frame loop. */
    fun setScreenOn(on: Boolean)

    /** One of her spoken replies started playing. */
    fun onVoiceStarted()

    /** Her spoken reply finished or was stopped. */
    fun onVoiceEnded()

    /**
     * What she can stand on and tease in the current app (Layout.EMPTY: only the floor), the
     * keyboard's top edge in screen pixels while one is open, and [restricted]: no teasing now
     * (keyboard open, a do-not-watch app, or a call). [quiet]: the app's last layout cut at Maya's
     * own window; losing her platform to it is no fall worth telling the brain about.
     */
    fun setScreenLayout(layout: Layout, keyboardTop: Int?, restricted: Boolean, quiet: Boolean)
}

/**
 * Keeps Maya alive: a specialUse foreground service that owns the brain connection, the senses
 * and the overlay. Everything here runs on the main thread.
 */
class MayaService : Service() {
    private enum class Status(val textRes: Int) {
        NAPPING(R.string.notif_napping),
        HERE(R.string.notif_here),
        AUTH(R.string.notif_auth),
    }

    /** The overlay (Task 10). Actions from the brain are forwarded to it. */
    var overlay: OverlayHook? = null
    private var overlayBody: OverlayBody? = null

    private lateinit var prefs: Prefs
    private var connection: BrainConnection? = null
    private var doNotWatch = DoNotWatch(emptySet())
    private var appUsage: AppUsageSense? = null
    private var deviceSense: DeviceSense? = null
    /** Our [NotificationBridge.sink] (it owns this run's NotificationFilter); null while the sense is off. */
    private var notificationSink: ((NotificationInfo) -> Unit)? = null
    private var voicePlayer: VoicePlayer? = null
    /** Runs the brain's `act` actions ("Let Maya use my phone"; it checks the switch itself). */
    private var actions: ActionRunner? = null
    private var running = false
    private var status = Status.NAPPING
    /** The user hid Maya with the notification's Hide action. */
    private var overlayHidden = false
    /**
     * She stepped away because a do-not-watch app (a bank, say) is in front: her window is removed
     * like Hide and comes back when another app is in front. Not the user's Hide: the notification
     * still offers Hide, and a Hide meanwhile keeps her hidden afterwards.
     */
    private var autoHidden = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Maya is listening to the user (hold to talk). */
    private var listening = false
    /** The latest clip that arrived while she was listening: played when listening ends. */
    private var heldAudio: Audio? = null

    // Screen sense ("Let Maya read my screen"); all null/empty while it is off.
    private val main = Handler(Looper.getMainLooper())
    /** Our [ScreenBridge.sink]. */
    private var screenSink: ScreenSink? = null
    /** Recreated on every connect, so she glances at the current screen soon after (re)connecting. */
    private var glanceScheduler: GlanceScheduler? = null
    /** The foreground app as the accessibility service last reported it. */
    private var screenPkg: String? = null
    /** The latest layout and the app it came from (null: none yet), kept for a new overlay. */
    private var screenLayoutPkg: String? = null
    private var screenLayout: Layout = Layout.EMPTY
    private var screenKeyboardTop: Int? = null
    private var screenRestricted = false
    /** What we last told [ScreenBridge.active]. */
    private var screenActive = false
    /** Limits her looks (screenshots); null while the screen sense is off. */
    private var lookScheduler: LookScheduler? = null
    /** The screenshot in flight, if any. */
    private var shot: Shot? = null
    /** The app, line count and time of the latest glance taken (for the picture-heavy rule). */
    private var lastGlancePkg: String? = null
    private var lastGlanceLines = 0
    private var lastGlanceMs = 0L
    private val shotTimeout = Runnable { shotFailed() }
    private val glanceTick = object : Runnable {
        override fun run() {
            onGlanceTick()
            if (running && screenSink != null) main.postDelayed(this, GLANCE_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: started via startForegroundService, we must call
        // startForeground even if we are about to stop.
        if (!enterForeground()) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (!running && !startMaya()) return Service.START_NOT_STICKY
        when (intent?.action) {
            ACTION_SHH -> {
                heldAudio = null
                voicePlayer?.stop()
                emit(UserShush())
            }
            ACTION_HIDE -> toggleHidden()
        }
        // After the actions, so Hide never creates an overlay only to hide it again. No-op if already
        // up or hidden; picks up an overlay permission granted since the last start.
        startOverlay()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        running = false
        actions?.cancel("stopped")
        actions = null
        dropShot()
        stopNetworkWatch()
        overlayBody?.destroy()
        overlayBody = null
        overlay = null
        heldAudio = null
        voicePlayer?.release()
        voicePlayer = null
        stopSenses()
        connection?.stop()
        connection = null
        super.onDestroy()
    }

    /** Sends an event to the brain (queued while offline for sense events). */
    fun emit(e: Event) {
        val out: Event = if (e is UserMessage) {
            val text = e.text.trim().take(MAX_MESSAGE_CHARS)
            if (text.isBlank()) return
            if (text == e.text) e else e.copy(text = text)
        } else {
            e
        }
        connection?.send(out)
    }

    private fun startMaya(): Boolean {
        val url = prefs.wsUrl()
        if (!prefs.enabled || url == null) {
            if (url == null) Toast.makeText(this, R.string.toast_need_settings, Toast.LENGTH_LONG).show()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        running = true
        status = Status.NAPPING
        doNotWatch = DoNotWatch(prefs.doNotWatch)
        voicePlayer = VoicePlayer(
            this,
            onStarted = { overlay?.onVoiceStarted() },
            onEnded = { overlay?.onVoiceEnded() },
        )
        actions = ActionRunner(
            this,
            prefs,
            ::emit,
            body = { overlayBody },
            // press/type/scroll need Screen access and "Let Maya read my screen"
            hands = { if (screenSink != null) ScreenBridge.hands else null },
            doNotWatch = { pkg -> doNotWatch.isBlocked(pkg) },
            awayHere = ::awayHere,
            frontApp = { screenSource()?.frontApp() ?: appUsage?.foregroundNow() },
        )
        val conn = BrainConnection(url, prefs.voiceMode, ::onAction, ::onConnected, ::onAuthError, ::screenSize)
        connection = conn
        conn.start()
        startSenses()
        startNetworkWatch()
        return true
    }

    // ---- network ----

    /** A network coming back (Wi-Fi joined, Tailscale up) is a good moment to retry the brain at once. */
    private fun startNetworkWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connection?.reconnectNow() // delivered on the main thread (handler below)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "no network callback: ${e.javaClass.simpleName}")
        }
    }

    private fun stopNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    // ---- overlay ----

    /**
     * Creates Maya's body if we may draw over other apps, and shows it unless she has stepped away
     * from a do-not-watch app. Starts napping until the brain connects.
     */
    private fun startOverlay() {
        if (overlayHidden || overlayBody != null || !Settings.canDrawOverlays(this)) return
        val body = try {
            OverlayBody(this, ::emit, ::onListening)
        } catch (e: Exception) {
            Log.w(TAG, "overlay unavailable: ${e.javaClass.simpleName}")
            return
        }
        body.setConnected(status == Status.HERE)
        body.setScreenOn(screenUsable())
        if (screenLayoutPkg != null) body.setScreenLayout(screenLayout, screenKeyboardTop, screenRestricted, ownLayout())
        overlayBody = body
        overlay = body
        probeFront()
        if (!autoHidden) body.show()
        updateScreenActive()
    }

    /** Hide/Show from the notification. With no overlay yet, showing is left to [startOverlay]. */
    private fun toggleHidden() {
        overlayHidden = !overlayHidden
        updateNotification() // the action now reads Show / Hide
        if (!overlayHidden) probeFront() // the app in front may have changed while she was hidden
        applyVisibility()
    }

    /** Her window is up unless the user hid her or she stepped away from a do-not-watch app. */
    private fun applyVisibility() {
        val hidden = overlayHidden || autoHidden
        if (hidden) {
            heldAudio = null
            voicePlayer?.stop()
        }
        val body = overlayBody ?: return
        if (hidden) body.hide() else body.show()
        updateScreenActive()
    }

    /**
     * The app in front changed (from the screen sense or the app-usage sense, whichever noticed
     * last). A do-not-watch app makes her step away; she comes back once no do-not-watch app
     * window is visible any more (split screen, an autofill or fingerprint pop-up over the bank
     * keep her away). Maya's own windows and the system UI (notification shade) change nothing.
     */
    private fun onFrontApp(pkg: String?) {
        if (!running || pkg == null || pkg == packageName || pkg == SYSTEM_UI) return
        setAway(doNotWatch.isBlocked(pkg) || screenSource()?.blockedAppVisible() == true)
    }

    private fun setAway(away: Boolean) {
        if (away == autoHidden) return
        autoHidden = away
        applyVisibility()
    }

    /**
     * She does nothing on the phone now: she stepped away from a do-not-watch app, or one is in
     * front (or anywhere on screen) by the screen sense or by usage access.
     */
    private fun awayHere(): Boolean {
        if (autoHidden) return true
        val source = screenSource()
        if (source?.blockedAppVisible() == true) return true
        return listOfNotNull(source?.frontApp(), appUsage?.foregroundNow()).any { doNotWatch.isBlocked(it) }
    }

    /** The accessibility service, while the screen sense is on. */
    private fun screenSource() = if (screenSink != null) ScreenBridge.service else null

    /**
     * Before she shows: which app is in front right now? The topmost app window (screen sense),
     * else the app-usage sense's app; if neither can tell, she shows.
     */
    private fun probeFront() {
        val source = screenSource()
        val top = source?.frontApp()
        autoHidden = if (top != null) {
            doNotWatch.isBlocked(top) || source.blockedAppVisible()
        } else {
            appUsage?.foregroundPackage()?.let { doNotWatch.isBlocked(it) } ?: false
        }
    }

    /** While she is away: back as soon as no do-not-watch window is visible (no app change needed). */
    private fun recheckAway() {
        if (!autoHidden) return
        val source = screenSource() ?: return
        val top = source.frontApp() ?: return
        setAway(doNotWatch.isBlocked(top) || source.blockedAppVisible())
    }

    // ---- senses ----

    private fun startSenses() {
        val usageSense = AppUsageSense(this, doNotWatch, ::onFrontApp)
        appUsage = usageSense
        usageSense.start(::emit)
        val device = DeviceSense(this)
        deviceSense = device
        device.start(::onDeviceEvent)
        if (prefs.seeNotifications) {
            // The listener (Notification access) hands notifications over on the main thread.
            val filter = NotificationFilter(doNotWatch::isBlocked, packageName)
            val sink: (NotificationInfo) -> Unit = { info -> filter.accept(info)?.let { emit(it) } }
            notificationSink = sink
            NotificationBridge.sink = sink
        }
        if (prefs.readScreen) {
            // The accessibility service (Screen access) reports on the main thread.
            glanceScheduler = GlanceScheduler()
            lookScheduler = LookScheduler()
            val sink = object : ScreenSink {
                override fun onLayout(pkg: String, layout: Layout, keyboardTop: Int?) = onScreenLayout(pkg, layout, keyboardTop)

                override fun onApp(pkg: String?) = onScreenApp(pkg)
            }
            screenSink = sink
            ScreenBridge.sink = sink
            screenActive = false
            ScreenBridge.active = false // until the overlay shows (startOverlay -> updateScreenActive)
            main.postDelayed(glanceTick, GLANCE_TICK_MS)
        }
    }

    private fun stopSenses() {
        appUsage?.stop()
        appUsage = null
        deviceSense?.stop()
        deviceSense = null
        // Only clear our own sink, never one a newer service instance already set.
        if (NotificationBridge.sink === notificationSink) NotificationBridge.sink = null
        notificationSink = null
        main.removeCallbacks(glanceTick)
        if (ScreenBridge.sink === screenSink) {
            ScreenBridge.sink = null
            ScreenBridge.active = false
        }
        screenSink = null
        screenActive = false
        glanceScheduler = null
        lookScheduler = null
        dropShot()
        screenPkg = null
    }

    /** The accessibility service saw another foreground app (Maya's own windows never count). */
    private fun onScreenApp(pkg: String?) {
        if (!running) return
        screenPkg = pkg
        glanceScheduler?.onApp(pkg, SystemClock.elapsedRealtime())
        onFrontApp(pkg)
    }

    /** A new layout of the current app: handed to her body with whether teasing is off for now. */
    private fun onScreenLayout(pkg: String, layout: Layout, keyboardTop: Int?) {
        if (!running) return
        screenLayoutPkg = pkg
        screenLayout = layout
        screenKeyboardTop = keyboardTop
        screenRestricted = isRestricted(pkg, keyboardTop)
        overlay?.setScreenLayout(layout, keyboardTop, screenRestricted, ownLayout())
    }

    /** The last layout is the app's, cut at Maya's own window (the chat, her settings) in front of it. */
    private fun ownLayout(): Boolean = screenLayoutPkg == packageName

    /** No teasing while the user types, in a do-not-watch app (or over one), or during a call. */
    private fun isRestricted(pkg: String, keyboardTop: Int?): Boolean =
        keyboardTop != null || doNotWatch.isBlocked(pkg) || screenPkg?.let { doNotWatch.isBlocked(it) } == true || inCall()

    /**
     * Layouts are only worth building while she is visible: overlay up and not hidden, screen on and
     * unlocked. Becoming visible again asks the accessibility service for a fresh layout. Stepping
     * away from a do-not-watch app keeps it active, so she hears when another app comes to the front.
     */
    private fun updateScreenActive() {
        if (screenSink == null || ScreenBridge.sink !== screenSink) return
        val active = running && overlayBody != null && !overlayHidden && screenUsable()
        if (active == screenActive) return
        screenActive = active
        ScreenBridge.active = active
        if (active) ScreenBridge.service?.refresh()
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Every [GLANCE_TICK_MS]: keeps "restricted" current (a call starts without any screen change)
     * and, when the scheduler says a glance is due, takes one and sends it if the text changed.
     */
    private fun onGlanceTick() {
        val scheduler = glanceScheduler ?: return
        if (ScreenBridge.service == null && screenLayoutPkg != null) forgetScreen(scheduler)
        recheckAway()
        val layoutPkg = screenLayoutPkg
        if (layoutPkg != null) {
            val restricted = isRestricted(layoutPkg, screenKeyboardTop)
            if (restricted != screenRestricted) {
                screenRestricted = restricted
                overlay?.setScreenLayout(screenLayout, screenKeyboardTop, restricted, ownLayout())
            }
        }
        val pkg = screenPkg
        val now = SystemClock.elapsedRealtime()
        // The last layout must be the app's own and current (she is visible, so layouts are being
        // rebuilt): while Maya's window, system UI or the lock screen is in front, or she is hidden,
        // the glance stays due instead of being spent on a screen she may not read. Nor while the
        // user types (keyboard open): it is taken once the keyboard closes.
        val allowed = status == Status.HERE && screenActive && pkg != null && screenLayoutPkg == pkg &&
            screenKeyboardTop == null && !doNotWatch.isBlocked(pkg) && screenUsable()
        autoLook(now)
        if (!scheduler.poll(now, allowed)) return
        val g = ScreenBridge.service?.glance() ?: return
        if (doNotWatch.isBlocked(g.packageName)) return
        lastGlancePkg = g.packageName
        lastGlanceLines = g.lines.size
        lastGlanceMs = now
        if (scheduler.taken(g.packageName, g.lines, now)) emit(Glance(g.packageName, g.app, g.lines, g.targets))
    }

    // ---- vision ----

    /** A screenshot in flight: the app it is of, and whether the brain asked for it. */
    private class Shot(val pkg: String, var asked: Boolean)

    /**
     * The app she may look at (screenshot) right now, or null: connected, Screen access on and the
     * service up, she is visible (not hidden, not stepped away), the screen on and unlocked, the
     * current layout is the app's own (not Maya's window, system UI or the lock screen), no
     * keyboard (no typing, so no password being entered), and the app is not do-not-watch,
     * Settings, system UI or Maya.
     */
    private fun lookablePkg(): String? {
        val pkg = screenPkg ?: return null
        if (screenSink == null || ScreenBridge.service == null || status != Status.HERE) return null
        if (!screenActive || overlayHidden || autoHidden || !screenUsable()) return null
        if (screenLayoutPkg != pkg || screenKeyboardTop != null) return null
        if (pkg in NEVER_LOOK || pkg == packageName || doNotWatch.isBlocked(pkg)) return null
        return pkg
    }

    /** Every glance tick: a screenshot when the screen is mostly pictures and [LookScheduler] says one is due. */
    private fun autoLook(now: Long) {
        val looks = lookScheduler ?: return
        // Not allowed: nothing is recorded, so the look stays due for when it is.
        val pkg = lookablePkg() ?: return
        if (shot != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val (w, h) = realScreenSize(this)
        // Lines of a recent glance of this app; unknown (none in the last minute): only the area rule.
        val fresh = lastGlancePkg == pkg && now - lastGlanceMs <= GLANCE_LINES_MAX_AGE_MS
        val lines = if (fresh) lastGlanceLines else Int.MAX_VALUE
        val heavy = isPictureHeavy(screenLayout.images, w, h, lines)
        if (looks.autoDue(pkg, heavy, allowed = true, nowMs = now)) takeShot(pkg, asked = false)
    }

    /** The brain asked her to look now: a screenshot if allowed (one per 20 s), else sight_failed. */
    private fun onLook() {
        val looks = lookScheduler
        val pkg = lookablePkg()
        if (looks == null || pkg == null) {
            emit(SightFailed("blocked"))
            return
        }
        if (!looks.askedAllowed(SystemClock.elapsedRealtime())) {
            emit(SightFailed("blocked"))
            return
        }
        val pending = shot
        if (pending != null) {
            pending.asked = true // the look under way answers the question
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            emit(SightFailed("error")) // no screenshots before Android 11
            return
        }
        takeShot(pkg, asked = true)
    }

    private fun takeShot(pkg: String, asked: Boolean) {
        val source = ScreenBridge.service
        if (source == null) {
            if (asked) emit(SightFailed("error"))
            return
        }
        val s = Shot(pkg, asked)
        shot = s
        main.postDelayed(shotTimeout, SHOT_TIMEOUT_MS)
        source.screenshot(pkg) { image -> onShot(s, image) }
    }

    /**
     * The screenshot [s] came back as base64 JPEG ([image] null: it failed or the screen could not
     * be captured). Sent as a sight if she may still look at the same app; held only in memory.
     */
    private fun onShot(s: Shot, image: String?) {
        if (shot !== s) return // timed out, or the screen sense stopped meanwhile
        shot = null
        main.removeCallbacks(shotTimeout)
        if (!running) return
        if (image == null) {
            if (s.asked) emit(SightFailed("error"))
            return
        }
        val source = ScreenBridge.service
        if (lookablePkg() != s.pkg || source == null) {
            if (s.asked) emit(SightFailed("blocked"))
            return
        }
        emit(Sight(s.pkg, source.appName(s.pkg), if (s.asked) "asked" else "auto", image))
        overlayBody?.lookedAtScreen()
    }

    /** No answer from the system in time: an asked look failed. */
    private fun shotFailed() {
        val s = shot ?: return
        shot = null
        if (running && s.asked) emit(SightFailed("error"))
    }

    /** Forgets the screenshot in flight (its result, if it still comes, is ignored). */
    private fun dropShot() {
        shot = null
        main.removeCallbacks(shotTimeout)
    }

    /** Screen access was turned off while she ran: the last layout is stale, so she keeps only the floor. */
    private fun forgetScreen(scheduler: GlanceScheduler) {
        screenPkg = null
        scheduler.onApp(null, SystemClock.elapsedRealtime())
        screenLayoutPkg = null
        screenLayout = Layout.EMPTY
        screenKeyboardTop = null
        screenRestricted = false
        overlay?.setScreenLayout(Layout.EMPTY, null, false, false)
        // The screen sense is gone: whether she stays away is now up to the app-usage sense.
        val wasAway = autoHidden
        probeFront()
        if (autoHidden != wasAway) applyVisibility()
    }

    /** On every connect: fresh glance memory, and a glance at the current app soon. */
    private fun restartGlances() {
        if (glanceScheduler == null) return
        val scheduler = GlanceScheduler()
        glanceScheduler = scheduler
        scheduler.onApp(screenPkg, SystemClock.elapsedRealtime())
    }

    /**
     * Screen changes (on = on and unlocked, see [DeviceSense]) also drive app-usage polling and the
     * overlay's frame loop; order matters so the brain sees a tidy sequence.
     */
    private fun onDeviceEvent(e: Event) {
        if (e is Screen) {
            if (e.on) {
                connection?.reconnectNow() // she should wake up promptly when the user is back
                emit(e)
                appUsage?.setScreenOn(true)
                overlay?.setScreenOn(true)
                updateScreenActive()
                // the brain forgot the screen when it went off: a fresh glance at the app soon
                glanceScheduler?.onApp(screenPkg, SystemClock.elapsedRealtime())
            } else {
                overlay?.setScreenOn(false)
                updateScreenActive()
                appUsage?.setScreenOn(false) // emits AppClosed for the current app first
                emit(e)
                glanceScheduler?.onApp(null, SystemClock.elapsedRealtime())
            }
        } else {
            emit(e)
        }
    }

    /** The brain forgets the current app on hello: tell it the screen state and what is open now. */
    private fun reemitState() {
        emit(Screen(screenUsable()))
        val current = appUsage?.currentApp() ?: return
        emit(AppOpened(current.first, current.second))
    }

    /** The screen is on and unlocked, i.e. the user can actually see Maya. */
    private fun screenUsable(): Boolean {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    /**
     * Her voice stops the moment the user starts talking. A reply voiced while she listened plays
     * once listening ends, if it still belongs to her latest line (VoicePlayer checks the say id).
     */
    private fun onListening(on: Boolean) {
        listening = on
        if (on) {
            voicePlayer?.stop()
            return
        }
        val clip = heldAudio ?: return
        heldAudio = null
        if (!overlayHidden && !autoHidden) voicePlayer?.play(clip.refId, clip.dataB64)
    }

    private fun onAction(a: Action) {
        when (a) {
            is Say -> voicePlayer?.onSay(a.id)
            is Audio -> when {
                overlayHidden || autoHidden -> Unit
                listening -> heldAudio = a // keep only the most recent
                else -> voicePlayer?.play(a.refId, a.dataB64)
            }
            is Look -> onLook()
            is Act -> actions?.run(a)
            else -> Unit
        }
        overlay?.handle(a)
    }

    private fun onConnected(on: Boolean) {
        if (!running) return
        status = if (on) Status.HERE else Status.NAPPING
        updateNotification()
        overlay?.setConnected(on)
        if (on) {
            reemitState()
            restartGlances()
        } else {
            actions?.cancel("offline") // its result could not be sent, and would be stale later
        }
    }

    private fun onAuthError() {
        if (!running) return
        status = Status.AUTH
        updateNotification()
        overlay?.setConnected(false)
        actions?.cancel("offline")
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // ---- notification ----

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun enterForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start in the foreground: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(this, 0, openIntent, piFlags)
        val shh = PendingIntent.getService(this, 1, Intent(this, MayaService::class.java).setAction(ACTION_SHH), piFlags)
        val hide = PendingIntent.getService(this, 2, Intent(this, MayaService::class.java).setAction(ACTION_HIDE), piFlags)
        val icon = Icon.createWithResource(this, R.drawable.ic_maya)
        val hideLabel = if (overlayHidden) R.string.action_show else R.string.action_hide
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_maya)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(status.textRes))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icon, getString(R.string.action_shh), shh).build())
            .addAction(Notification.Action.Builder(icon, getString(hideLabel), hide).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_SHH = "com.maya.app.SHH"
        const val ACTION_HIDE = "com.maya.app.HIDE"
        private const val CHANNEL_ID = "maya"
        private const val NOTIFICATION_ID = 1
        private const val MAX_MESSAGE_CHARS = 2000
        private const val GLANCE_TICK_MS = 1000L
        /** An asked look the system has not answered within this long has failed. */
        private const val SHOT_TIMEOUT_MS = 3000L
        /** A glance's line count counts for the picture-heavy rule this long. */
        private const val GLANCE_LINES_MAX_AGE_MS = 60_000L
        private const val SYSTEM_UI = "com.android.systemui"
        /** Never looked at (besides do-not-watch apps and Maya herself). */
        private val NEVER_LOOK = setOf(SYSTEM_UI, "com.android.settings")
        private const val TAG = "MayaService"
    }
}
