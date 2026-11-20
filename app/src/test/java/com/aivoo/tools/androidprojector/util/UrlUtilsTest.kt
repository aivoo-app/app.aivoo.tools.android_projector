package app.aivoo.tools.androidprojector.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun acceptsHttpUrls() {
        assertTrue(UrlUtils.isValidHttpUrl("http://example.com"))
        assertTrue(UrlUtils.isValidHttpUrl("https://example.com/path?q=1"))
        assertTrue(UrlUtils.isValidHttpUrl("  HTTPS://EXAMPLE.COM  "))
    }

    @Test
    fun rejectsOtherSchemesAndGarbage() {
        assertFalse(UrlUtils.isValidHttpUrl(null))
        assertFalse(UrlUtils.isValidHttpUrl(""))
        assertFalse(UrlUtils.isValidHttpUrl("ftp://example.com"))
        assertFalse(UrlUtils.isValidHttpUrl("file:///etc/passwd"))
        assertFalse(UrlUtils.isValidHttpUrl("javascript:alert(1)"))
        assertFalse(UrlUtils.isValidHttpUrl("example.com"))
        assertFalse(UrlUtils.isValidHttpUrl("http://"))
    }
}