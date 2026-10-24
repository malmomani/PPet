package com.maya.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.maya.core.Action
import com.maya.core.AppClosed
import com.maya.core.AppOpened
import com.maya.core.Backoff
import com.maya.core.Charging
import com.maya.core.ErrorMsg
import com.maya.core.Event
import com.maya.core.Hello
import com.maya.core.MayaVersion
import com.maya.core.OfflineQueue
import com.maya.core.Protocol
import com.maya.core.Screen
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The phone's single WebSocket to the brain. All state lives on the main thread: OkHttp callbacks
 * are posted to the main [Handler], and [send]/[start]/[stop] must be called from the main thread
 * (calls from other threads are re-posted). The URL carries the token, so it is never logged.
 */
class BrainConnection(
    private val url: String,
    private val onAction: (Action) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onAuthError: () -> Unit,
    private val screenSize: () -> Pair<Int, Int>,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val backoff = Backoff()
    private val queue = OfflineQueue()
    private val reconnect = Runnable { connect() }

    private var socket: WebSocket? = null
    private var connected = false
    private var stopped = true

    fun start() {
        if (!onMain()) {
            main.post { start() }
            return
        }
        if (!stopped) return
        stopped = false
        connect()
    }

    fun stop() {
        if (!onMain()) {
            main.post { stop() }
            return
        }
        stopped = true
        main.removeCallbacks(reconnect)
        val ws = socket
        socket = null
        connected = false
        ws?.close(NORMAL_CLOSURE, null)
    }

    /** Sends now when connected; otherwise queues sense events (app/screen/charging) and drops user events. */
    fun send(e: Event) {
        if (!onMain()) {
            main.post { send(e) }
            return
        }
        val ws = socket
        if (connected && ws != null && ws.send(Protocol.encode(e))) return
        if (e is AppOpened || e is AppClosed || e is Screen || e is Charging) queue.add(e)
    }

    private fun connect() {
        if (stopped) return
        main.removeCallbacks(reconnect)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "brain address is not a valid URL")
            onConnected(false)
            scheduleReconnect()
            return
        }
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (stopped) return
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, backoff.nextDelayMs())
    }

    private fun handleOpen(ws: WebSocket) {
        if (ws !== socket || stopped) return
        Log.i(TAG, "connected to brain")
        connected = true
        backoff.reset()
        val size = screenSize()
        ws.send(Protocol.encode(Hello(MayaVersion.CLIENT, size.first, size.second)))
        val summary = queue.drainSummary()
        if (summary != null) ws.send(Protocol.encode(summary))
        onConnected(true)
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        if (ws !== socket || stopped) return
        val action = Protocol.decodeAction(text) ?: return
        if (action is ErrorMsg && action.code == "auth") {
            handleAuthError(ws)
            return
        }
        onAction(action)
    }

    private fun handleClosed(ws: WebSocket, code: Int?) {
        if (ws !== socket) return
        socket = null
        connected = false
        if (code == CLOSE_AUTH) {
            handleAuthError(ws)
            return
        }
        if (stopped) return
        Log.i(TAG, "brain connection lost")
        onConnected(false)
        scheduleReconnect()
    }

    private fun handleAuthError(ws: WebSocket) {
        Log.w(TAG, "brain rejected the token")
        stopped = true
        main.removeCallbacks(reconnect)
        socket = null
        connected = false
        ws.close(NORMAL_CLOSURE, null)
        onAuthError()
    }

    private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post { handleOpen(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { handleMessage(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            main.post { handleClosed(webSocket, code) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { handleClosed(webSocket, code) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Only the exception class: messages can contain the host.
            Log.w(TAG, "brain connection failed: ${t.javaClass.simpleName}")
            main.post { handleClosed(webSocket, null) }
        }
    }

    companion object {
        private const val TAG = "BrainConnection"
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_AUTH = 4401
    }
}
