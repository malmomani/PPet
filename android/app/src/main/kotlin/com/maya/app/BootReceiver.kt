package com.maya.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts Maya after a reboot or an app update if she was running before (prefs.enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.enabled || prefs.wsUrl() == null) return
        try {
            context.startForegroundService(Intent(context, MayaService::class.java))
        } catch (e: RuntimeException) {
            Log.w("BootReceiver", "could not start Maya: ${e.javaClass.simpleName}")
        }
    }
}
