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
 * [Listener.onNoMatch] or [Listener.onUnavailable] with [Reason.NO_RECOGNIZER]/[Reason.PERMISSION]
 * (unless cancelled). Callbacks may arrive synchronously from [start]. Main thread only.
 *
 * The recognizer first runs from the service. If Android refuses it the mic there (error, never
 * ready, or a silenced mic that never hears speech), the rest of the service run uses
 * [VoiceActivity] instead, a translucent foreground activity that always may record.
 * Transcripts are never logged.
 */
class VoiceInput(context: Context, private val listener: Listener) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onNoMatch()

        /**
         * [Reason.NO_RECOGNIZER] and [Reason.PERMISSION] end the session. [Reason.BACKGROUND_DENIED]
         * is only a notice (sent once): the session carries on through [VoiceActivity] if the finger
         * is still down, and later sessions use it from the start.
         */
        fun onUnavailable(reason: Reason)
    }

    enum class Reason { NO_RECOGNIZER, PERMISSION, BACKGROUND_DENIED }

    private val ctx: Context = context
    private val main = Handler(Looper.getMainLooper())
    private val readyTimeout = Runnable { onReadyTimeout() }
    private val finishTimeout = Runnable { if (active) deliverHeard() }

    // Remembered for the life of this object (one service run).
    private var activityMode = false
    private var onDeviceOk = true
    private var reportedBackground = false

    // The current session.
    private var generation = 0
    private var active = false
    private var stopRequested = false
    private var ready = false
    private var heardSpeech = false
    private var readyAtMs = 0L
    private var stopAtMs = 0L
    private var lastPartial = ""
    private var usedOnDevice = false
    private var direct: SpeechSession? = null
    private var request: VoiceBridge.Request? = null

    /** True once the service's own recognizer was refused the mic and [VoiceActivity] is used. */
    val usesActivity: Boolean get() = activityMode

    fun start() {
        if (active) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onUnavailable(Reason.PERMISSION)
            return
        }
        if (!recognizerAvailable()) {
            listener.onUnavailable(Reason.NO_RECOGNIZER)
            return
        }
        active = true
        stopRequested = false
        stopAtMs = 0L
        lastPartial = ""
        launch()
    }

    /** The finger was lifted: finish listening and deliver what was heard. */
    fun stop() {
        if (!active || stopRequested) return
        stopRequested = true
        stopAtMs = SystemClock.uptimeMillis()
        if (activityMode) {
            val activity = request?.activity
            if (activity == null) {
                // The activity never came up, so nothing was recorded.
                deliverHeard()
                return
            }
            activity.stopListening()
        } else {
            direct?.stop()
        }
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
        usedOnDevice = onDeviceOk && onDeviceAvailable()
        main.removeCallbacks(readyTimeout)
        val relay = Relay(gen)
        if (activityMode) {
            val req = VoiceBridge.Request(usedOnDevice, relay)
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
            val s = SpeechSession(ctx, usedOnDevice, relay)
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
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO -> {
                if (activityMode) fail(Reason.PERMISSION) else fallbackToActivity()
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                // The on-device recognizer lacks English: use the regular one from now on.
                val retry = usedOnDevice
                onDeviceOk = false
                when {
                    !retry || !recognizerAvailable() -> fail(Reason.NO_RECOGNIZER)
                    stopRequested -> deliverHeard()
                    else -> relaunch()
                }
            }
            else -> {
                // No match, speech timeout, client/network errors: deliver what we have.
                noteSilence()
                deliverHeard()
            }
        }
    }

    private fun onReadyTimeout() {
        if (!active || ready) return
        if (activityMode) fail(Reason.PERMISSION) else fallbackToActivity()
    }

    /**
     * From the service, Android may hand the recognizer a silenced mic instead of an error. If it was
     * ready, the finger stayed down a while and not a single word was heard, assume that happened
     * and use the activity from the next hold on.
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

    private fun relaunch() {
        tearDown()
        launch()
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

    private fun onDeviceAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    } catch (e: RuntimeException) {
        false
    }

    private fun recognizerAvailable(): Boolean = try {
        (onDeviceOk && onDeviceAvailable()) || SpeechRecognizer.isRecognitionAvailable(ctx)
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
    }
}
