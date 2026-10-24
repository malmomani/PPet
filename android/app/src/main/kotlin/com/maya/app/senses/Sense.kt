package com.maya.app.senses

import com.maya.core.Event

/** Something Maya can perceive on the phone. Both calls happen on the main thread. */
interface Sense {
    fun start(emit: (Event) -> Unit)

    fun stop()
}
