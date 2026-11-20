package app.aivoo.tools.androidprojector.util

object VersionUtils {
    fun parse(value: String?): List<Int>? {
        val normalized = value?.trim()?.removePrefix("v")?.substringBefore('-') ?: return null
        if (normalized.isBlank()) return null
        val parts = normalized.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return when (numbers.size) {
            1 -> listOf(numbers[0], 0, 0)
            2 -> listOf(numbers[0], numbers[1], 0)
            else -> numbers
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        if (candidate == current) return false
        val c = parse(candidate) ?: return true
        val i = parse(current) ?: return true
        for (index in 0 until 3) {
            val cv = c[index]
            val iv = i[index]
            if (cv > iv) return true
            if (cv < iv) return false
        }
        return false
    }
}