package com.maya.app.voice

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** What the on-device recognizer knows about English (see [OfflineSpeech.check]). */
enum class OfflineSpeechStatus {
    /** English is installed for offline recognition. */
    INSTALLED,

    /** The on-device recognizer exists but has no English model: [OfflineSpeech.requestEnglishDownload] can fetch it. */
    MISSING,

    /** There is no on-device recognizer at all. */
    NO_RECOGNIZER,

    /** The recognizer could not say (error, timeout). */
    UNKNOWN,
}

/**
 * Android 13+ only: asks the on-device recognizer whether its English model is installed, and asks
 * it to download one. Every API 33 class (RecognitionSupport, RecognitionSupportCallback) is
 * referenced only from here, so older Android never loads them; callers check
 * `Build.VERSION.SDK_INT >= TIRAMISU` first. Main thread only. Never throws.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
internal object OfflineSpeech {
    private const val TAG = "MayaOfflineSpeech"
    private const val LANGUAGE = "en-US"
    private const val CHECK_TIMEOUT_MS = 5000L

    /**
     * Reports the status exactly once through [onResult], on the main thread (possibly before this
     * returns). The probe recognizer is destroyed before [onResult] runs.
     */
    fun check(context: Context, onResult: (OfflineSpeechStatus) -> Unit) {
        val app = context.applicationContext
        val available = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        } catch (e: RuntimeException) {
            false
        }
        if (!available) {
            onResult(OfflineSpeechStatus.NO_RECOGNIZER)
            return
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no on-device recognizer: ${e.javaClass.simpleName}")
            onResult(OfflineSpeechStatus.UNKNOWN)
            return
        }
        Probe(app, recognizer, onResult).start()
    }

    /**
     * Asks the on-device recognizer to download English (US). Returns the recognizer used, which the
     * caller destroys later (destroying it at once could drop the request), or null when the
     * request could not be made (then send the user to the voice input settings instead).
     */
    fun requestEnglishDownload(context: Context): SpeechRecognizer? {
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no on-device recognizer: ${e.javaClass.simpleName}")
            return null
        }
        return try {
            r.triggerModelDownload(englishIntent())
            r
        } catch (e: RuntimeException) {
            Log.w(TAG, "model download refused: ${e.javaClass.simpleName}")
            destroyQuietly(r)
            null
        }
    }

    fun destroyQuietly(r: SpeechRecognizer) {
        try {
            r.destroy()
        } catch (e: RuntimeException) {
            // Already unbound.
        }
    }

    private fun englishIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)

    /** en-US first, but any English model (en, en-GB, en_IN...) will do. */
    private fun isEnglish(tag: String): Boolean =
        tag.equals(LANGUAGE, ignoreCase = true) ||
            tag.equals("en", ignoreCase = true) ||
            tag.startsWith("en-", ignoreCase = true) ||
            tag.startsWith("en_", ignoreCase = true)

    /** One support check; finishes once (result, error, exception or timeout). */
    private class Probe(
        private val context: Context,
        private val recognizer: SpeechRecognizer,
        private val onResult: (OfflineSpeechStatus) -> Unit,
    ) : RecognitionSupportCallback {
        private val main = Handler(Looper.getMainLooper())
        private val timeout = Runnable { finish(OfflineSpeechStatus.UNKNOWN) }
        private var done = false

        fun start() {
            main.postDelayed(timeout, OfflineSpeech.CHECK_TIMEOUT_MS)
            try {
                recognizer.checkRecognitionSupport(OfflineSpeech.englishIntent(), context.mainExecutor, this)
            } catch (e: RuntimeException) {
                Log.w(OfflineSpeech.TAG, "support check failed: ${e.javaClass.simpleName}")
                finish(OfflineSpeechStatus.UNKNOWN)
            }
        }

        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
            val installed = try {
                recognitionSupport.installedOnDeviceLanguages.any { OfflineSpeech.isEnglish(it) }
            } catch (e: RuntimeException) {
                finish(OfflineSpeechStatus.UNKNOWN)
                return
            }
            finish(if (installed) OfflineSpeechStatus.INSTALLED else OfflineSpeechStatus.MISSING)
        }

        override fun onError(error: Int) {
            Log.w(OfflineSpeech.TAG, "support check error $error")
            finish(OfflineSpeechStatus.UNKNOWN)
        }

        private fun finish(status: OfflineSpeechStatus) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            OfflineSpeech.destroyQuietly(recognizer)
            onResult(status)
        }
    }
}
