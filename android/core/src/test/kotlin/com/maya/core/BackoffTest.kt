package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun backoff_doubles_and_caps() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000), List(8) { b.nextDelayMs() })
        b.reset(); assertEquals(1000L, b.nextDelayMs())
    }
}
