package app.aivoo.tools.androidprojector.util

import java.util.regex.Pattern

object UrlUtils {
    private val HTTP_URL = Pattern.compile("^(https?://).+", Pattern.CASE_INSENSITIVE)

    fun isValidHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return HTTP_URL.matcher(url.trim()).matches()
    }
}