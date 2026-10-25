package com.maya.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Plays Maya's spoken replies (`audio` actions: base64 WAV). Only the audio for the latest `say`
 * ([onSay]) is played, so audio that arrives late (after a newer line or a reconnect) is dropped.
 * Silent/vibrate ringer modes skip playback. [onStarted] runs when a clip starts. Errors are
 * swallowed: the bubble already shows the text. Main thread only.
 */
class VoicePlayer(context: Context, private val onStarted: () -> Unit = {}) {
    private val ctx: Context = context.applicationContext
    private val audio: AudioManager? = ctx.getSystemService(AudioManager::class.java)
    private val file = File(ctx.cacheDir, FILE_NAME)
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private var latestSayId = ""
    private var player: MediaPlayer? = null
    private var released = false

    /** A `say` arrived: from now on only its audio may play. */
    fun onSay(id: String) {
        latestSayId = id
    }

    fun play(refId: String, dataB64: String) {
        if (released || refId.isEmpty() || refId != latestSayId) return
        val ringer = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringer != AudioManager.RINGER_MODE_NORMAL) return
        stop() // a newer clip replaces the current one
        var mp: MediaPlayer? = null
        try {
            val bytes = Base64.decode(dataB64, Base64.DEFAULT)
            if (bytes.isEmpty()) return
            file.writeBytes(bytes)
            val p = MediaPlayer()
            mp = p
            p.setAudioAttributes(attributes)
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { done -> if (player === done) stop() }
            p.setOnErrorListener { failed, what, _ ->
                Log.w(TAG, "playback error $what")
                if (player === failed) stop()
                true
            }
            p.prepare()
            audio?.requestAudioFocus(focusRequest)
            p.start()
            player = p
            mp = null
            onStarted()
        } catch (e: Exception) {
            // Bad base64 (IllegalArgumentException), unreadable WAV (IOException), wrong state...
            Log.w(TAG, "could not play voice: ${e.javaClass.simpleName}")
            mp?.release()
            abandonFocus()
        }
    }

    /** Stops the current clip, if any. */
    fun stop() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (e: IllegalStateException) {
            // Not started or already stopped.
        }
        p.release()
        abandonFocus()
    }

    fun release() {
        stop()
        released = true
        file.delete()
    }

    private fun abandonFocus() {
        try {
            audio?.abandonAudioFocusRequest(focusRequest)
        } catch (e: RuntimeException) {
            // Nothing to abandon.
        }
    }

    private companion object {
        const val TAG = "MayaVoicePlayer"
        const val FILE_NAME = "maya-voice.wav"
    }
}
