package app.aivoo.tools.androidprojector.util

object PathUtils {
    /**
     * Uploaded files are stored flat in cacheDir. Reject anything that could be
     * interpreted as a path, including encoded separators after NanoHTTPD has
     * already URL-decoded the request URI.
     */
    fun isSafeMediaName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.indexOf('/') >= 0) return false
        if (name.indexOf('\\') >= 0) return false
        if (name.indexOf('\u0000') >= 0) return false
        if (name.contains("..")) return false
        return true
    }
}