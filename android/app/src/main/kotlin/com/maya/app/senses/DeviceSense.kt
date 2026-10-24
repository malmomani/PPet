package com.maya.app.senses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.maya.core.Charging
import com.maya.core.Event
import com.maya.core.Screen

/** Screen on/off and charger plugged/unplugged, via a dynamically registered receiver. */
class DeviceSense(context: Context) : Sense {
    private val ctx: Context = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    override fun start(emit: (Event) -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> emit(Screen(true))
                    Intent.ACTION_SCREEN_OFF -> emit(Screen(false))
                    Intent.ACTION_POWER_CONNECTED -> emit(Charging(true))
                    Intent.ACTION_POWER_DISCONNECTED -> emit(Charging(false))
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }
        receiver = r
    }

    override fun stop() {
        val r = receiver ?: return
        receiver = null
        try {
            ctx.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }
}
