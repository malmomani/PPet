package com.maya.app.senses

import android.os.Handler
import android.os.Looper
import com.maya.core.NotificationInfo

/**
 * Process-local hand-off from [MayaNotificationListener] to MayaService. The service sets [sink]
 * while it runs with "Let Maya see who messages me" on and clears it in onDestroy; without a sink
 * the listener does nothing. Delivery always happens on the main thread.
 */
object NotificationBridge {
    @Volatile
    var sink: ((NotificationInfo) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun post(info: NotificationInfo) {
        main.post { sink?.invoke(info) }
    }
}
