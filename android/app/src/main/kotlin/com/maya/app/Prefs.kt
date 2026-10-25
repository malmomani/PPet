package com.maya.app

import android.content.Context
import android.content.SharedPreferences
import com.maya.core.DEFAULT_DO_NOT_WATCH
import java.net.URLEncoder

/** Maya's settings, stored in the private SharedPreferences file "maya". */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var doNotWatch: Set<String>
        get() = sp.getStringSet(KEY_DO_NOT_WATCH, null)?.toSet() ?: DEFAULT_DO_NOT_WATCH
        set(value) = sp.edit().putStringSet(KEY_DO_NOT_WATCH, HashSet(value)).apply()

    /** When Maya speaks her replies: "off", "replies" (only to voice messages) or "always". */
    var voiceMode: String
        get() = sp.getString(KEY_VOICE_MODE, null)?.takeIf { it in VOICE_MODES } ?: DEFAULT_VOICE_MODE
        set(value) = sp.edit().putString(KEY_VOICE_MODE, value).apply()

    /** "Let Maya see who messages me": app + sender names from notifications, never the text. */
    var seeNotifications: Boolean
        get() = sp.getBoolean(KEY_SEE_NOTIFICATIONS, true)
        set(value) = sp.edit().putBoolean(KEY_SEE_NOTIFICATIONS, value).apply()

    /** "Let Maya read my screen": the visible text and buttons of the current app (Screen access). */
    var readScreen: Boolean
        get() = sp.getBoolean(KEY_READ_SCREEN, true)
        set(value) = sp.edit().putBoolean(KEY_READ_SCREEN, value).apply()

    /** The brain's WebSocket URL, or null until both host and token are set. Contains the token: never log it. */
    fun wsUrl(): String? {
        val h = host.trim()
        val t = token.trim()
        if (h.isBlank() || t.isBlank()) return null
        return "ws://$h:$port/ws?token=" + URLEncoder.encode(t, "UTF-8")
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val VOICE_OFF = "off"
        const val VOICE_REPLIES = "replies"
        const val VOICE_ALWAYS = "always"
        const val DEFAULT_VOICE_MODE = VOICE_REPLIES
        val VOICE_MODES = listOf(VOICE_OFF, VOICE_REPLIES, VOICE_ALWAYS)
        private const val FILE = "maya"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DO_NOT_WATCH = "do_not_watch"
        private const val KEY_VOICE_MODE = "voice_mode"
        private const val KEY_SEE_NOTIFICATIONS = "see_notifications"
        private const val KEY_READ_SCREEN = "read_screen"
    }
}
