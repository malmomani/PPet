package com.maya.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * One run of a [SpeechRecognizer] (created, listening, then destroyed). Used by [VoiceInput] from
 * the service and by [VoiceActivity] when the mic needs a foreground activity. With [onDevice] it
 * uses the on-device recognizer (audio stays on the phone). Main thread only.
 * After [Callbacks.onResult] or [Callbacks.onError] nothing more is reported.
 */
internal class SpeechSession(
    private val context: Context,
    private val onDevice: Boolean,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onReady()
        fun onSpeechBegun()
        fun onPartial(text: String)

        /** The recognizer's best guess; null or blank when it heard nothing. */
        fun onResult(text: String?)
        fun onError(code: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    /** Creates the recognizer and starts listening. Returns false (and holds nothing) on failure. */
    fun start(): Boolean {
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "no speech recognizer: ${e.javaClass.simpleName}")
            return false
        }
        recognizer = r
        return try {
            r.setRecognitionListener(listener)
            r.startListening(buildIntent())
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start listening: ${e.javaClass.simpleName}")
            destroy()
            false
        }
    }

    /** Stop capturing; the recognizer then delivers its result (or an error). */
    fun stop() {
        if (finished) return
        try {
            recognizer?.stopListening()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopListening failed: ${e.javaClass.simpleName}")
        }
    }

    /** Abandon the run: nothing more is reported. */
    fun cancel() {
        if (!finished) {
            try {
                recognizer?.cancel()
            } catch (e: RuntimeException) {
                // Already gone.
            }
        }
        destroy()
    }

    fun destroy() {
        finished = true
        release()
    }

    private fun release() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.destroy()
        } catch (e: RuntimeException) {
            // Already unbound.
        }
    }

    /**
     * Marks the run done so nothing more is reported, and posts the release of the recognizer. The
     * owner may free it sooner: [cancel]/[destroy] called from inside the final callback (as
     * [VoiceActivity] does) release it right away, and the posted release is then a no-op.
     */
    private fun finishSoon() {
        finished = true
        main.post { release() }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Listening ends on finger release (stop) or after ~2 s of silence, whichever comes first.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!finished) callbacks.onReady()
        }

        override fun onBeginningOfSpeech() {
            if (!finished) callbacks.onSpeechBegun()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (finished) return
            finishSoon()
            callbacks.onError(error)
        }

        override fun onResults(results: Bundle?) {
            if (finished) return
            finishSoon()
            callbacks.onResult(firstResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (finished) return
            val text = firstResult(partialResults)
            if (!text.isNullOrBlank()) callbacks.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "MayaSpeech"
        const val LANGUAGE = "en-US"
        const val SILENCE_MS = 2_000L
    }
}
