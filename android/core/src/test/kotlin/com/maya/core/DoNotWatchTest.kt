package com.maya.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoNotWatchTest {
    @Test fun blocks_defaults_and_bank_names() {
        val d = DoNotWatch(DEFAULT_DO_NOT_WATCH)
        assertTrue(d.isBlocked("com.x8bit.bitwarden")); assertTrue(d.isBlocked("com.chase.SUPERBANKapp"))
        assertFalse(d.isBlocked("com.zhiliaoapp.musically"))
    }
}
