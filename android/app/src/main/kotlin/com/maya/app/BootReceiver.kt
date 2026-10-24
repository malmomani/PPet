package com.maya.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts Maya after a reboot if she was running before (prefs.enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.enabled || prefs.wsUrl() == null) return
        try {
            context.startForegroundService(Intent(context, MayaService::class.java))
        } catch (e: RuntimeException) {
            Log.w("BootReceiver", "could not start Maya at boot: ${e.javaClass.simpleName}")
        }
    }
}
