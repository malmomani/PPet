package com.maya.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Hold-to-talk speech input. [start] begins a session, [stop] ends capture and delivers the result,
 * [cancel] drops it. Every started session ends with exactly one of [Listener.onFinal],
 * [Listener.onNoMatch] or [Listener.onUnavailable] with [Reason.NO_RECOGNIZER], [Reason.PERMISSION]
 * or [Reason.MIC_BUSY] (unless cancelled). [Listener.onReady] reports when the mic is actually
 * capturing. The recognizer also ends a session by itself after ~2 s of silence.
 * Callbacks may arrive synchronously from [start]. Main thread only. Transcripts are never logged.
 *
 * Privacy: on Android 13+ only the on-device recognizer is used, so audio never leaves the phone;
 * without it (or without its English model) input is unavailable. Older Android uses the default
 * recognizer with EXTRA_PREFER_OFFLINE.
 *
 * Mic rights: on Android 14+ a background service may not record, so every session runs inside
 * [VoiceActivity], a translucent foreground activity. Below 14 the recognizer first runs from the
 * service; if Android refuses it the mic (error, never ready, or a silenced mic that never hears
 * speech) the rest of the service run uses the activity.
 */
class VoiceInput(context: Context, private val listener: Listener) {
    interface Listener {
        /** The recognizer is capturing: words spoken from now on are heard (may repeat after a retry). */
        fun onReady()

        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onNoMatch()

        /**
         * [Reason.NO_RECOGNIZER], [Reason.PERMISSION] and [Reason.MIC_BUSY] end the session.
         * [Reason.MIC_BUSY] means the mic failed although RECORD_AUDIO is granted (another app holds
         * it, or Android refused it this time). [Reason.BACKGROUND_DENIED]
         * is only a notice (sent once, below Android 14): the session carries on through
         * [VoiceActivity] if the finger is still down, and later sessions use it from the start.
         */
        fun onUnavailable(reason: Reason)
    }

    enum class Reason { NO_RECOGNIZER, PERMISSION, MIC_BUSY, BACKGROUND_DENIED }

    private val ctx: Context = context
    private val main = Handler(Looper.getMainLooper())
    private val onDeviceOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private val readyTimeout = Runnable { onReadyTimeout() }
    private val finishTimeout = Runnable { if (active) deliverHeard() }
    private val busyRetry = Runnable { if (active) launch() }

    // Remembered for the life of this object (one service run).
    private var activityMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private var reportedBackground = false

    // The current session.
    private var generation = 0
    private var active = false
    private var stopRequested = false
    private var ready = false
    private var heardSpeech = false
    private var busyRetried = false
    private var readyAtMs = 0L
    private var stopAtMs = 0L
    private var lastPartial = ""
    private var direct: SpeechSession? = null
    private var request: VoiceBridge.Request? = null

    /** True when sessions run in [VoiceActivity] (always on Android 14+). */
    val usesActivity: Boolean get() = activityMode

    fun start() {
        if (active) return
        if (!hasMicPermission()) {
            listener.onUnavailable(Reason.PERMISSION)
            return
        }
        if (!recognizerAvailable()) {
            listener.onUnavailable(Reason.NO_RECOGNIZER)
            return
        }
        active = true
        stopRequested = false
        busyRetried = false
        stopAtMs = 0L
        lastPartial = ""
        launch()
    }

    /** The finger was lifted: finish listening and deliver what was heard. */
    fun stop() {
        if (!active || stopRequested) return
        stopRequested = true
        stopAtMs = SystemClock.uptimeMillis()
        if (direct == null && request?.activity == null) {
            // Nothing is recording yet (activity not up, or waiting to retry): nothing was heard.
            deliverHeard()
            return
        }
        direct?.stop()
        request?.activity?.stopListening()
        main.removeCallbacks(finishTimeout)
        main.postDelayed(finishTimeout, FINISH_TIMEOUT_MS)
    }

    /** Drops the current session without a callback. */
    fun cancel() {
        if (active) finishSession()
    }

    fun destroy() {
        cancel()
    }

    // ---- session ----

    private fun launch() {
        val gen = ++generation
        ready = false
        heardSpeech = false
        main.removeCallbacks(readyTimeout)
        val relay = Relay(gen)
        if (activityMode) {
            val req = VoiceBridge.Request(onDeviceOnly, relay)
            request = req
            VoiceBridge.current = req
            val intent = Intent(ctx, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                ctx.startActivity(intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not start the voice activity: ${e.javaClass.simpleName}")
                fail(Reason.PERMISSION)
                return
            }
            main.postDelayed(readyTimeout, ACTIVITY_READY_TIMEOUT_MS)
        } else {
            val s = SpeechSession(ctx, onDeviceOnly, relay)
            if (!s.start()) {
                fallbackToActivity()
                return
            }
            direct = s
            main.postDelayed(readyTimeout, READY_TIMEOUT_MS)
        }
    }

    private fun handleReady() {
        ready = true
        readyAtMs = SystemClock.uptimeMillis()
        main.removeCallbacks(readyTimeout)
        listener.onReady()
    }

    private fun handleResult(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isNotEmpty()) {
            lastPartial = t
        } else {
            noteSilence()
        }
        deliverHeard()
    }

    private fun handleError(code: Int) {
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                if (activityMode) fail(Reason.PERMISSION) else fallbackToActivity()
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                if (activityMode) fail(micFailure()) else fallbackToActivity()
            }
            // No English model for the recognizer. Never fall back to a recognizer that might send
            // audio off the phone: the user installs offline English speech instead.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                fail(Reason.NO_RECOGNIZER)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                if (busyRetried || stopRequested) {
                    deliverHeard()
                } else {
                    busyRetried = true
                    tearDown()
                    main.removeCallbacks(readyTimeout)
                    main.postDelayed(busyRetry, BUSY_RETRY_MS)
                }
            }
            // The recognizer service failed before it ever listened (typically: no offline model for
            // the on-device recognizer). After a release, these are just the end of a short hold.
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT ->
                if (!ready && !stopRequested) {
                    fail(Reason.NO_RECOGNIZER)
                } else {
                    noteSilence()
                    deliverHeard()
                }
            else -> {
                // No match, speech timeout, network errors: deliver what we have.
                noteSilence()
                deliverHeard()
            }
        }
    }

    private fun onReadyTimeout() {
        if (!active || ready) return
        when {
            stopRequested -> deliverHeard() // a quick release, not a refused mic: keep the mode
            activityMode -> fail(micFailure())
            else -> fallbackToActivity()
        }
    }

    /**
     * Below Android 14, the service's recognizer may get a silenced mic instead of an error. If it
     * was ready, the finger stayed down a while and not a single word was heard, assume that
     * happened and use the activity from the next hold on.
     */
    private fun noteSilence() {
        if (activityMode || !ready || heardSpeech || lastPartial.isNotBlank()) return
        val end = if (stopAtMs > 0L) stopAtMs else SystemClock.uptimeMillis()
        if (end - readyAtMs < SILENT_HOLD_MS) return
        activityMode = true
        Log.i(TAG, "service recognizer heard nothing; using the voice activity from now on")
    }

    private fun fallbackToActivity() {
        tearDown()
        activityMode = true
        if (!reportedBackground) {
            reportedBackground = true
            Log.i(TAG, "service recognizer refused the mic; using the voice activity")
            listener.onUnavailable(Reason.BACKGROUND_DENIED)
            if (!active) return // the listener cancelled
        }
        if (stopRequested) deliverHeard() else launch()
    }

    private fun deliverHeard() {
        val text = lastPartial.trim()
        finishSession()
        if (text.isEmpty()) listener.onNoMatch() else listener.onFinal(text)
    }

    private fun fail(reason: Reason) {
        finishSession()
        listener.onUnavailable(reason)
    }

    private fun finishSession() {
        active = false
        generation++
        main.removeCallbacks(readyTimeout)
        main.removeCallbacks(finishTimeout)
        main.removeCallbacks(busyRetry)
        tearDown()
    }

    /** Stops the running recognizer (service or activity) without ending the session. */
    private fun tearDown() {
        direct?.cancel()
        direct = null
        val req = request ?: return
        request = null
        req.cancelled = true
        if (VoiceBridge.current === req) VoiceBridge.current = null
        req.activity?.close()
    }

    // ---- availability ----

    private fun hasMicPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The mic never started: blame the permission only when it really is missing. */
    private fun micFailure(): Reason = if (hasMicPermission()) Reason.MIC_BUSY else Reason.PERMISSION

    /** Android 13+: the on-device recognizer only. Older: any recognizer service. */
    private fun recognizerAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        } else {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        }
    } catch (e: RuntimeException) {
        false
    }

    /** Forwards one launch's callbacks; anything from an older launch or a finished session is dropped. */
    private inner class Relay(private val gen: Int) : SpeechSession.Callbacks {
        private fun live(): Boolean = gen == generation && active

        override fun onReady() {
            if (live()) handleReady()
        }

        override fun onSpeechBegun() {
            if (live()) heardSpeech = true
        }

        override fun onPartial(text: String) {
            if (!live()) return
            heardSpeech = true
            lastPartial = text
            listener.onPartial(text)
        }

        override fun onResult(text: String?) {
            if (live()) handleResult(text)
        }

        override fun onError(code: Int) {
            if (live()) handleError(code)
        }
    }

    private companion object {
        const val TAG = "MayaVoiceInput"
        const val READY_TIMEOUT_MS = 1500L
        const val ACTIVITY_READY_TIMEOUT_MS = 4000L
        const val FINISH_TIMEOUT_MS = 5000L
        const val SILENT_HOLD_MS = 1500L
        const val BUSY_RETRY_MS = 300L
    }
}
