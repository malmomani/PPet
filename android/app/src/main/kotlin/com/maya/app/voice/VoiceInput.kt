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
import com.maya.app.Prefs

/**
 * Hold-to-talk speech input. [start] begins a session, [stop] ends capture and delivers the result,
 * [cancel] drops it. Every started session ends with exactly one of [Listener.onFinal],
 * [Listener.onNoMatch] or [Listener.onUnavailable] with a [Reason] other than
 * [Reason.BACKGROUND_DENIED] (unless cancelled). [Listener.onReady] reports when the mic is
 * actually capturing. The recognizer also ends a session by itself after ~2 s of silence.
 * Callbacks may arrive synchronously from [start]. Main thread only. Transcripts are never logged.
 *
 * Privacy / recognizer choice: on Android 13+ the on-device recognizer is used first, so audio
 * stays on the phone. If it is missing, or fails before it is ready (no English model, service
 * error), and [Prefs.allowOnlineSpeech] is on, the same session is retried once with the default
 * recognizer (Google voice typing) with EXTRA_PREFER_OFFLINE, which may process audio online; the
 * failure is remembered for the rest of the process, so later sessions go straight to the default
 * recognizer (see [forgetOnDeviceFailure]). With the pref off, only the on-device recognizer is
 * used and input is unavailable without it. Older Android always uses the default recognizer with
 * EXTRA_PREFER_OFFLINE.
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
         * Every reason except [Reason.BACKGROUND_DENIED] ends the session.
         * [Reason.MIC_BUSY] means the mic failed although RECORD_AUDIO is granted (another app holds
         * it, or Android refused it this time). [Reason.BACKGROUND_DENIED]
         * is only a notice (sent once, below Android 14): the session carries on through
         * [VoiceActivity] if the finger is still down, and later sessions use it from the start.
         */
        fun onUnavailable(reason: Reason)
    }

    enum class Reason {
        /** The phone has no speech recognizer at all. */
        NO_RECOGNIZER,

        /** Only the on-device recognizer may be used, and it is missing or has no English model. */
        OFFLINE_MISSING,

        /** The default recognizer (Google voice typing) failed, e.g. no network and no offline pack. */
        RECOGNITION_FAILED,
        PERMISSION,
        MIC_BUSY,
        BACKGROUND_DENIED,
    }

    private val ctx: Context = context
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
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

    /** This launch uses the on-device recognizer (Android 13+); otherwise the default one. */
    private var useOnDevice = false

    /** An on-device failure before ready may still retry this session with the default recognizer. */
    private var canFallBack = false
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
        val unavailable = chooseRecognizer()
        if (unavailable != null) {
            listener.onUnavailable(unavailable)
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
            val req = VoiceBridge.Request(useOnDevice, relay)
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
            val s = SpeechSession(ctx, useOnDevice, relay)
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
            // No English model for the recognizer. The on-device one may hand over to the default
            // recognizer (only when the user allows it); otherwise input is unavailable.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                if (!ready && canFallBack) fallBackToDefault(code) else fail(recognizerFailure())
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
                    if (canFallBack) fallBackToDefault(code) else fail(recognizerFailure())
                } else {
                    noteSilence()
                    deliverHeard()
                }
            // The default recognizer had no offline model and no network. Words heard so far still count.
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                if (!useOnDevice && lastPartial.isBlank()) {
                    fail(Reason.RECOGNITION_FAILED)
                } else {
                    noteSilence()
                    deliverHeard()
                }
            else -> {
                // No match, speech timeout: deliver what we have.
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

    /**
     * The on-device recognizer failed before it was ready: remember that for this process and run
     * the same session once more with the default recognizer (Google voice typing).
     */
    private fun fallBackToDefault(code: Int) {
        onDeviceFailed = true
        useOnDevice = false
        canFallBack = false
        Log.i(TAG, "on-device recognizer failed (error $code); using the default recognizer")
        tearDown()
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

    /** Why the recognizer in use could not listen at all. */
    private fun recognizerFailure(): Reason = if (useOnDevice) Reason.OFFLINE_MISSING else Reason.RECOGNITION_FAILED

    /**
     * Picks the recognizer for a new session (sets [useOnDevice] and [canFallBack]). Returns why
     * input is unavailable, or null when a recognizer was chosen.
     */
    private fun chooseRecognizer(): Reason? {
        val defaultAvailable = defaultRecognizerAvailable()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            useOnDevice = false
            canFallBack = false
            return if (defaultAvailable) null else Reason.NO_RECOGNIZER
        }
        val onDeviceAvailable = onDeviceRecognizerAvailable()
        if (!prefs.allowOnlineSpeech) {
            // Strict: on-device only, so audio never leaves the phone.
            useOnDevice = true
            canFallBack = false
            return when {
                onDeviceAvailable -> null
                defaultAvailable -> Reason.OFFLINE_MISSING
                else -> Reason.NO_RECOGNIZER
            }
        }
        return when {
            onDeviceAvailable && !onDeviceFailed -> {
                useOnDevice = true
                canFallBack = defaultAvailable
                null
            }
            defaultAvailable -> {
                useOnDevice = false
                canFallBack = false
                null
            }
            onDeviceAvailable -> {
                // It failed earlier, but it is all there is.
                useOnDevice = true
                canFallBack = false
                null
            }
            else -> Reason.NO_RECOGNIZER
        }
    }

    /** Android 12+ API, only called on Android 13+. */
    private fun onDeviceRecognizerAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    } catch (e: RuntimeException) {
        false
    }

    private fun defaultRecognizerAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(ctx)
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

    companion object {
        private const val TAG = "MayaVoiceInput"
        private const val READY_TIMEOUT_MS = 1500L
        private const val ACTIVITY_READY_TIMEOUT_MS = 4000L
        private const val FINISH_TIMEOUT_MS = 5000L
        private const val SILENT_HOLD_MS = 1500L
        private const val BUSY_RETRY_MS = 300L

        /** The on-device recognizer failed in this process: later sessions use the default one. Main thread only. */
        private var onDeviceFailed = false

        /** Try the on-device recognizer again from the next session (the user changed the speech setting). */
        fun forgetOnDeviceFailure() {
            onDeviceFailed = false
        }
    }
}
