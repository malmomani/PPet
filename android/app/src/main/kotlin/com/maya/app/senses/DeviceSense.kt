package com.maya.app.senses

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.maya.core.Charging
import com.maya.core.Event
import com.maya.core.Screen

/**
 * Screen on/off and charger plugged/unplugged, via a dynamically registered receiver.
 *
 * "Screen on" means the user can use the phone: it is sent on unlock (USER_PRESENT), or on
 * SCREEN_ON when the keyguard is not locked. Lighting up the lock screen is not "on". Repeated
 * screen states are dropped (with no lock, SCREEN_ON and USER_PRESENT both arrive).
 */
class DeviceSense(context: Context) : Sense {
    private val ctx: Context = context.applicationContext
    private val keyguard: KeyguardManager? = ctx.getSystemService(KeyguardManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var lastScreenOn: Boolean? = null

    override fun start(emit: (Event) -> Unit) {
        if (receiver != null) return
        lastScreenOn = null
        val screen = { on: Boolean ->
            if (lastScreenOn != on) {
                lastScreenOn = on
                emit(Screen(on))
            }
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> if (keyguard?.isKeyguardLocked != true) screen(true)
                    Intent.ACTION_USER_PRESENT -> screen(true)
                    Intent.ACTION_SCREEN_OFF -> screen(false)
                    Intent.ACTION_POWER_CONNECTED -> emit(Charging(true))
                    Intent.ACTION_POWER_DISCONNECTED -> emit(Charging(false))
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
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
