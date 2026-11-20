package app.aivoo.tools.androidprojector.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilsTest {
    @Test
    fun acceptsGeneratedNames() {
        assertTrue(PathUtils.isSafeMediaName("upload_123.pdf"))
        assertTrue(PathUtils.isSafeMediaName("upload_123.mp4"))
    }

    @Test
    fun rejectsTraversalAndSeparators() {
        assertFalse(PathUtils.isSafeMediaName(""))
        assertFalse(PathUtils.isSafeMediaName(".."))
        assertFalse(PathUtils.isSafeMediaName("../shared_prefs/x.xml"))
        assertFalse(PathUtils.isSafeMediaName("..\\shared_prefs\\x.xml"))
        assertFalse(PathUtils.isSafeMediaName("nested/file.pdf"))
        assertFalse(PathUtils.isSafeMediaName("nested\\file.pdf"))
        assertFalse(PathUtils.isSafeMediaName("bad\u0000name.pdf"))
    }
}