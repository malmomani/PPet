package com.maya.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Onboarding + settings: brain address/token, permission checklist, do-not-watch list, start/stop. */
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
    private val permissionRows = ArrayList<PermissionRow>()

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
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val denied = grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED
            // Permanently denied: the dialog no longer shows, so send the user to the app's notification settings.
            if (denied && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openNotificationSettings()
            }
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
                startForegroundService(Intent(this, MayaService::class.java))
            } else {
                prefs.enabled = false
            }
        }
        refreshStatus()
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
            startForegroundService(Intent(this, MayaService::class.java))
        }
        refreshStatus()
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
    }
}
