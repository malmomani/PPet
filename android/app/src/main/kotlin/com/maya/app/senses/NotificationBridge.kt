package com.maya.app.senses

import android.os.Handler
import android.os.Looper
import com.maya.core.NotificationInfo

/**
 * Process-local hand-off from [MayaNotificationListener] to MayaService. The service sets [sink]
 * while it runs with "Let Maya see who messages me" on and clears it in onDestroy; without a sink
 * the listener reads nothing. [onPosted] (set while the screen sense runs) only hears that some
 * notification was posted, nothing about it, so she can skip automatic screenshots while its
 * banner may be on screen. Delivery always happens on the main thread.
 */
object NotificationBridge {
    @Volatile
    var sink: ((NotificationInfo) -> Unit)? = null

    @Volatile
    var onPosted: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun post(info: NotificationInfo) {
        main.post { sink?.invoke(info) }
    }

    /** Some notification was posted (any app but Maya; nothing about it is passed on). */
    fun posted() {
        main.post { onPosted?.invoke() }
    }
}
