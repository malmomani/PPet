package com.maya.app.voice

import android.app.Activity
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.WindowManager

/**
 * Process-local hand-off between [VoiceInput] (in the service) and [VoiceActivity]. VoiceInput
 * publishes a [Request] and starts the activity; the activity picks it up, runs the recognizer and
 * reports through [Request.callbacks]. Main thread only.
 */
internal object VoiceBridge {
    class Request(val onDevice: Boolean, val callbacks: SpeechSession.Callbacks) {
        /** The activity serving this request, once it is up. */
        var activity: VoiceActivity? = null

        /** VoiceInput no longer wants this request: the activity must finish without reporting. */
        var cancelled = false
    }

    var current: Request? = null

    /**
     * Reported (instead of a [SpeechRecognizer] error code) when the activity is closed by the
     * system or the user (back, home) before a result: the end of the session, not a recognizer failure.
     */
    const val ERROR_ACTIVITY_CLOSED = -1001
}

/**
 * A translucent, layout-less activity that exists only while Maya listens. A foreground activity
 * always has mic rights, which the overlay service may lack (background while-in-use limits), so
 * [VoiceInput] runs the recognizer from here: always on Android 14+, and below that once the
 * service's own recognizer was refused. Its window is fully transparent, not touchable and not
 * focusable, so the app underneath keeps its touches and keyboard.
 */
class VoiceActivity : Activity() {
    private var request: VoiceBridge.Request? = null
    private var session: SpeechSession? = null
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Invisible and inert: touches pass through to the app below and its keyboard keeps focus.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val attrs = window.attributes
        attrs.alpha = 0f
        window.attributes = attrs
        val req = VoiceBridge.current
        if (savedInstanceState != null || req == null || req.cancelled || req.activity != null) {
            close()
            return
        }
        request = req
        req.activity = this
        val s = SpeechSession(this, req.onDevice, Relay(req))
        if (s.start()) {
            session = s
        } else {
            reported = true
            if (isLive(req)) req.callbacks.onError(SpeechRecognizer.ERROR_CLIENT)
            close()
        }
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        val req = request
        request = null
        if (req != null) {
            if (req.activity === this) req.activity = null
            // Closed by the system or the user (back, home): the session ends with what was heard.
            if (!reported && isLive(req)) {
                reported = true
                req.callbacks.onError(VoiceBridge.ERROR_ACTIVITY_CLOSED)
            }
        }
        super.onDestroy()
    }

    /** The finger was lifted: stop capturing and deliver the result. */
    internal fun stopListening() {
        session?.stop()
    }

    /** Ends the activity without animation (and without reporting, if the request was cancelled). */
    @Suppress("DEPRECATION")
    internal fun close() {
        session?.cancel()
        session = null
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun isLive(req: VoiceBridge.Request): Boolean = !req.cancelled && VoiceBridge.current === req

    private inner class Relay(private val req: VoiceBridge.Request) : SpeechSession.Callbacks {
        private fun target(): SpeechSession.Callbacks? = if (isLive(req)) req.callbacks else null

        override fun onReady() {
            target()?.onReady()
        }

        override fun onSpeechBegun() {
            target()?.onSpeechBegun()
        }

        override fun onPartial(text: String) {
            target()?.onPartial(text)
        }

        override fun onResult(text: String?) {
            reported = true
            target()?.onResult(text)
            close()
        }

        override fun onError(code: Int) {
            reported = true
            target()?.onError(code)
            close()
        }
    }
}
