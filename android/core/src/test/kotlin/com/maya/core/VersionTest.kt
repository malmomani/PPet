package com.maya.core

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun client_version_is_semver() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(MayaVersion.CLIENT))
    }
}
