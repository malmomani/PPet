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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.maya.app.overlay.OverlayBody
import com.maya.app.senses.AppUsageSense
import com.maya.app.senses.DeviceSense
import com.maya.core.Action
import com.maya.core.AppOpened
import com.maya.core.DoNotWatch
import com.maya.core.Event
import com.maya.core.Screen
import com.maya.core.UserMessage
import com.maya.core.UserShush

/** Implemented by the floating overlay (Task 10) and assigned to [MayaService.overlay]. */
interface OverlayHook {
    fun handle(a: Action)
    fun setConnected(on: Boolean)

    /** False while the screen is off or locked: the overlay pauses its frame loop. */
    fun setScreenOn(on: Boolean)
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
    private var running = false
    private var status = Status.NAPPING
    /** The user hid Maya with the notification's Hide action. */
    private var overlayHidden = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            ACTION_SHH -> emit(UserShush())
            ACTION_HIDE -> toggleHidden()
        }
        // After the actions, so Hide never creates an overlay only to hide it again. No-op if already
        // up or hidden; picks up an overlay permission granted since the last start.
        startOverlay()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopNetworkWatch()
        overlayBody?.destroy()
        overlayBody = null
        overlay = null
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
        val conn = BrainConnection(url, ::onAction, ::onConnected, ::onAuthError, ::screenSize)
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

    /** Shows Maya's body if we may draw over other apps. Starts napping until the brain connects. */
    private fun startOverlay() {
        if (overlayHidden || overlayBody != null || !Settings.canDrawOverlays(this)) return
        val body = try {
            OverlayBody(this, ::emit)
        } catch (e: Exception) {
            Log.w(TAG, "overlay unavailable: ${e.javaClass.simpleName}")
            return
        }
        body.setConnected(status == Status.HERE)
        body.setScreenOn(screenUsable())
        overlayBody = body
        overlay = body
        body.show()
    }

    /** Hide/Show from the notification. With no overlay yet, showing is left to [startOverlay]. */
    private fun toggleHidden() {
        overlayHidden = !overlayHidden
        updateNotification() // the action now reads Show / Hide
        val body = overlayBody ?: return
        if (overlayHidden) body.hide() else body.show()
    }

    // ---- senses ----

    private fun startSenses() {
        val usageSense = AppUsageSense(this, doNotWatch)
        appUsage = usageSense
        usageSense.start(::emit)
        val device = DeviceSense(this)
        deviceSense = device
        device.start(::onDeviceEvent)
    }

    private fun stopSenses() {
        appUsage?.stop()
        appUsage = null
        deviceSense?.stop()
        deviceSense = null
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
            } else {
                overlay?.setScreenOn(false)
                appUsage?.setScreenOn(false) // emits AppClosed for the current app first
                emit(e)
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

    private fun onAction(a: Action) {
        overlay?.handle(a)
    }

    private fun onConnected(on: Boolean) {
        if (!running) return
        status = if (on) Status.HERE else Status.NAPPING
        updateNotification()
        overlay?.setConnected(on)
        if (on) reemitState()
    }

    private fun onAuthError() {
        if (!running) return
        status = Status.AUTH
        updateNotification()
        overlay?.setConnected(false)
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
        private const val TAG = "MayaService"
    }
}
