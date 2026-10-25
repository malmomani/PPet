package com.maya.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.maya.app.senses.MayaNotificationListener
import com.maya.app.voice.OfflineSpeech
import com.maya.app.voice.OfflineSpeechStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Onboarding + settings: brain address/token, permission checklist, Maya's voice, messages,
 * do-not-watch list, start/stop.
 */
class SettingsActivity : Activity() {
    private class PermissionRow(
        val label: String,
        val status: TextView,
        val button: Button,
        val granted: () -> Boolean,
    )

    private lateinit var prefs: Prefs
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var doNotWatchField: EditText
    private lateinit var toggleButton: Button
    private lateinit var offlineSpeechRow: LinearLayout
    private val permissionRows = ArrayList<PermissionRow>()

    // Offline English speech (Android 13+ can check). Hidden until a check says it is missing.
    private var offlineSpeech = OfflineSpeechStatus.INSTALLED
    private var offlineCheckRunning = false
    /** The recognizer asked to download English; kept until we close (destroying it may drop the request). */
    private var downloadRecognizer: SpeechRecognizer? = null
    private var downloadRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)

        root.addView(text(getString(R.string.app_name), 26f, bold = true), matchWrap())
        root.addView(text(getString(R.string.settings_intro), 15f), matchWrap(top = 4))

        // Connection
        root.addView(text(getString(R.string.section_connection), 18f, bold = true), matchWrap(top = 20))
        hostField = field(R.string.hint_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        portField = field(R.string.hint_port, InputType.TYPE_CLASS_NUMBER)
        tokenField = field(R.string.hint_token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(hostField, matchWrap(top = 4))
        root.addView(portField, matchWrap(top = 4))
        root.addView(tokenField, matchWrap(top = 4))
        val showToken = CheckBox(this)
        showToken.setText(R.string.show_token)
        showToken.setOnCheckedChangeListener { _, checked -> setTokenVisible(checked) }
        root.addView(showToken, wrapWrap())
        val saveButton = Button(this)
        saveButton.setText(R.string.save)
        saveButton.setOnClickListener { onSaveClicked() }
        root.addView(saveButton, wrapWrap(top = 4))

        // Permissions
        root.addView(text(getString(R.string.section_permissions), 18f, bold = true), matchWrap(top = 20))
        addPermissionRow(root, R.string.perm_overlay, { hasOverlay() }) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addPermissionRow(root, R.string.perm_usage, { hasUsageAccess() }) {
            openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addPermissionRow(root, R.string.perm_notifications, { hasNotifications() }) { requestNotifications() }
        addPermissionRow(root, R.string.perm_battery, { ignoresBatteryOptimizations() }) {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        addPermissionRow(root, R.string.perm_mic, { hasMic() }) { requestMic() }
        addPermissionRow(root, R.string.perm_notification_access, { hasNotificationAccess() }) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Maya's voice
        root.addView(text(getString(R.string.section_voice), 18f, bold = true), matchWrap(top = 20))
        root.addView(voiceModeGroup(), matchWrap(top = 4))
        offlineSpeechRow = LinearLayout(this)
        offlineSpeechRow.orientation = LinearLayout.HORIZONTAL
        offlineSpeechRow.gravity = Gravity.CENTER_VERTICAL
        offlineSpeechRow.addView(
            text("✗  " + getString(R.string.offline_speech_missing), 16f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val setupSpeech = Button(this)
        setupSpeech.setText(R.string.offline_speech_setup)
        setupSpeech.setOnClickListener { onSetUpOfflineSpeech() }
        offlineSpeechRow.addView(
            setupSpeech,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(offlineSpeechRow, matchWrap(top = 4))

        // Messages (notification access)
        root.addView(text(getString(R.string.section_messages), 18f, bold = true), matchWrap(top = 20))
        val seeNotifications = Switch(this)
        seeNotifications.setText(R.string.see_notifications)
        seeNotifications.textSize = 16f
        seeNotifications.isChecked = prefs.seeNotifications
        seeNotifications.setOnCheckedChangeListener { _, checked -> onSeeNotificationsChanged(checked) }
        root.addView(seeNotifications, matchWrap(top = 4))
        root.addView(text(getString(R.string.see_notifications_note), 14f), matchWrap(top = 2))

        // Do-not-watch list
        root.addView(text(getString(R.string.section_do_not_watch), 18f, bold = true), matchWrap(top = 20))
        doNotWatchField = EditText(this)
        doNotWatchField.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        doNotWatchField.minLines = 4
        doNotWatchField.gravity = Gravity.TOP or Gravity.START
        doNotWatchField.textSize = 14f
        root.addView(doNotWatchField, matchWrap(top = 4))
        val saveListButton = Button(this)
        saveListButton.setText(R.string.save)
        saveListButton.setOnClickListener { onSaveClicked() }
        root.addView(saveListButton, wrapWrap(top = 4))

        // Start / stop
        toggleButton = Button(this)
        toggleButton.setOnClickListener { onToggleClicked() }
        root.addView(toggleButton, matchWrap(top = 24))

        val scroll = ScrollView(this)
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }

        loadFields()
    }

    override fun onResume() {
        super.onResume()
        // Idempotent: brings Maya back if she should be running but isn't (killed, app updated),
        // and lets a running service pick up an overlay permission granted while we were away.
        if (prefs.enabled && prefs.wsUrl() != null) startMayaService()
        refreshStatus()
    }

    override fun onDestroy() {
        val r = downloadRecognizer
        downloadRecognizer = null
        if (r != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) OfflineSpeech.destroyQuietly(r)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Permanently denied: the dialog no longer shows, so send the user to the app's notification settings.
            if (denied && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openNotificationSettings()
            }
        }
        if (requestCode == REQ_MIC && denied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppDetails()
        }
        refreshStatus()
    }

    // ---- actions ----

    private fun onSaveClicked() {
        if (!save()) return
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        if (prefs.enabled) {
            // Restart so the service picks up the new address/token/list.
            stopService(Intent(this, MayaService::class.java))
            if (prefs.wsUrl() != null) {
                startMayaService()
            } else {
                prefs.enabled = false
            }
        }
        refreshStatus()
    }

    private fun onVoiceModeChosen(mode: String) {
        if (mode == prefs.voiceMode) return
        prefs.voiceMode = mode
        restartMayaService() // so the brain hears the new mode in the next hello
    }

    private fun onSeeNotificationsChanged(on: Boolean) {
        if (on == prefs.seeNotifications) return
        prefs.seeNotifications = on
        restartMayaService() // so the service starts or stops listening
    }

    private fun onToggleClicked() {
        if (prefs.enabled) {
            prefs.enabled = false
            stopService(Intent(this, MayaService::class.java))
        } else {
            if (!save()) return
            if (prefs.wsUrl() == null) {
                Toast.makeText(this, R.string.toast_need_settings, Toast.LENGTH_LONG).show()
                return
            }
            prefs.enabled = true
            startMayaService()
        }
        refreshStatus()
    }

    /** Restarts a running Maya so the service picks up a changed setting. No-op when she is off. */
    private fun restartMayaService() {
        if (prefs.enabled && prefs.wsUrl() != null) {
            stopService(Intent(this, MayaService::class.java))
            startMayaService()
        }
    }

    private fun startMayaService() {
        try {
            startForegroundService(Intent(this, MayaService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException. We are
            // in the foreground, so this should not happen; never crash the settings screen over it.
            Log.w(TAG, "could not start Maya: ${e.javaClass.simpleName}")
        }
    }

    /** Switches the token field between hidden (password dots) and readable, keeping the cursor at the end. */
    private fun setTokenVisible(visible: Boolean) {
        val variation = if (visible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tokenField.inputType = InputType.TYPE_CLASS_TEXT or variation
        tokenField.setSelection(tokenField.text.length)
    }

    /** Validates and stores the form. Returns false (with a field error) when something is invalid. */
    private fun save(): Boolean {
        val host = hostField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            portField.error = getString(R.string.error_port)
            return false
        }
        if (host.isNotEmpty() && "http://$host:$port/".toHttpUrlOrNull() == null) {
            hostField.error = getString(R.string.error_host)
            return false
        }
        prefs.host = host
        prefs.port = port
        prefs.token = token
        prefs.doNotWatch = doNotWatchField.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return true
    }

    private fun loadFields() {
        hostField.setText(prefs.host)
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        doNotWatchField.setText(prefs.doNotWatch.sorted().joinToString("\n"))
    }

    private fun refreshStatus() {
        for (row in permissionRows) {
            val ok = row.granted()
            row.status.text = (if (ok) "✓  " else "✗  ") + row.label
            row.button.visibility = if (ok) View.GONE else View.VISIBLE
        }
        toggleButton.setText(if (prefs.enabled) R.string.stop_maya else R.string.start_maya)
        showOfflineSpeech()
        checkOfflineSpeech()
    }

    // ---- permission checks ----

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun hasNotifications(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val nm = getSystemService(NotificationManager::class.java)
        return runtimeGranted && (nm?.areNotificationsEnabled() ?: true)
    }

    private fun ignoresBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

    /** Notification access: our listener is in the system's enabled list. */
    private fun hasNotificationAccess(): Boolean {
        val component = ComponentName(this, MayaNotificationListener::class.java)
        return try {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        } catch (e: SecurityException) {
            getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) ?: false
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        if (hasMic()) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    /**
     * Android 13+: asks the on-device recognizer whether English is installed. Without it (or
     * without any on-device recognizer) hold-to-talk is unavailable, so the row offers to set it up.
     * When the recognizer can't tell, the row stays hidden.
     */
    private fun checkOfflineSpeech() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || offlineCheckRunning) return
        offlineCheckRunning = true
        OfflineSpeech.check(this) { status ->
            offlineCheckRunning = false
            if (!isDestroyed) {
                offlineSpeech = status
                showOfflineSpeech()
            }
        }
    }

    private fun showOfflineSpeech() {
        val missing = offlineSpeech == OfflineSpeechStatus.MISSING || offlineSpeech == OfflineSpeechStatus.NO_RECOGNIZER
        offlineSpeechRow.visibility = if (missing) View.VISIBLE else View.GONE
    }

    /**
     * The first tap asks the on-device recognizer to download English (US); without a recognizer,
     * if the request fails, or on a later tap (nothing happened), it opens the voice input settings.
     */
    private fun onSetUpOfflineSpeech() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            offlineSpeech == OfflineSpeechStatus.MISSING &&
            !downloadRequested
        ) {
            val r = OfflineSpeech.requestEnglishDownload(this)
            if (r != null) {
                downloadRecognizer = r
                downloadRequested = true
                Toast.makeText(this, R.string.offline_speech_downloading, Toast.LENGTH_LONG).show()
                return
            }
        }
        openVoiceInputSettings()
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                // Nothing else to try.
            }
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            // Nothing else to try.
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: ActivityNotFoundException) {
                // Nothing else to try.
            }
        }
    }

    // ---- view helpers ----

    private fun addPermissionRow(parent: LinearLayout, labelRes: Int, granted: () -> Boolean, grant: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val status = text("", 16f)
        row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val button = Button(this)
        button.setText(R.string.grant)
        button.setOnClickListener { grant() }
        row.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        parent.addView(row, matchWrap(top = 4))
        permissionRows.add(PermissionRow(getString(labelRes), status, button, granted))
    }

    /** Off / Only when I talk to her / Always, saved as soon as one is picked. */
    private fun voiceModeGroup(): RadioGroup {
        val group = RadioGroup(this)
        group.orientation = LinearLayout.VERTICAL
        val choices = listOf(
            Prefs.VOICE_OFF to R.string.voice_off,
            Prefs.VOICE_REPLIES to R.string.voice_replies,
            Prefs.VOICE_ALWAYS to R.string.voice_always,
        )
        val current = prefs.voiceMode
        for ((mode, labelRes) in choices) {
            val button = RadioButton(this)
            button.id = View.generateViewId()
            button.setText(labelRes)
            button.textSize = 16f
            group.addView(button)
            if (mode == current) group.check(button.id)
            button.setOnCheckedChangeListener { _, checked -> if (checked) onVoiceModeChosen(mode) }
        }
        return group
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = value
        tv.textSize = sizeSp
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        return tv
    }

    private fun field(hintRes: Int, type: Int): EditText {
        val et = EditText(this)
        et.setHint(hintRes)
        // inputType without MULTI_LINE already makes it single-line; calling setSingleLine() would
        // replace the password transformation and show the token in clear text.
        et.inputType = type
        return et
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        return lp
    }

    private fun wrapWrap(top: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        return lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** targetSdk 35 is edge-to-edge on Android 15: pad the content clear of the system bars and keyboard. */
    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            return WindowInsets.CONSUMED
        }
        v.setPadding(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom,
        )
        return insets.consumeSystemWindowInsets()
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private const val REQ_MIC = 2
        private const val TAG = "SettingsActivity"
    }
}
