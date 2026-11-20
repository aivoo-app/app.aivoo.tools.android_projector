package app.aivoo.tools.androidprojector.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {
    @Test
    fun parsesTags() {
        assertTrue(VersionUtils.isNewer("v1.2.3", "v1.2.2"))
        assertTrue(VersionUtils.isNewer("v1.3.0", "v1.2.9"))
        assertTrue(VersionUtils.isNewer("v2.0.0", "v1.9.9"))
        assertTrue(VersionUtils.isNewer("v1.2", "v1.1.9"))
    }

    @Test
    fun rejectsOlderAndEqual() {
        assertFalse(VersionUtils.isNewer("v1.2.2", "v1.2.3"))
        assertFalse(VersionUtils.isNewer("v1.2.3", "v1.2.3"))
        assertFalse(VersionUtils.isNewer("v1.0", "v1.0.0"))
        assertFalse(VersionUtils.isNewer("v0.0.10", "v1.0"))
    }

    @Test
    fun handlesDefaultVersion() {
        assertTrue(VersionUtils.isNewer("v1.0.1", "v1.0"))
        assertFalse(VersionUtils.isNewer("v1.0", "v1.0"))
    }
}