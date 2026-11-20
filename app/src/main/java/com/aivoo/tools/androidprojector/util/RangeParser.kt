package app.aivoo.tools.androidprojector.util

object RangeParser {
    sealed interface Result {
        data class Partial(val start: Long, val end: Long) : Result
        object Unsatisfiable : Result
        object NotRange : Result
    }

    fun parse(header: String?, total: Long): Result {
        if (header == null || !header.startsWith("bytes=")) return Result.NotRange
        val spec = header.removePrefix("bytes=").trim()
        if (spec.contains(',') || spec.length < 2 || spec.indexOf('-') != spec.lastIndexOf('-')) {
            return Result.Unsatisfiable
        }
        val dash = spec.indexOf('-')
        if (dash < 0 || (dash == 0 && spec.length == 2)) return Result.Unsatisfiable

        val firstText = spec.substring(0, dash)
        val lastText = spec.substring(dash + 1)
        if (firstText.isNotEmpty() && firstText.toLongOrNull() == null) return Result.Unsatisfiable
        if (lastText.isNotEmpty() && lastText.toLongOrNull() == null) return Result.Unsatisfiable

        val first = firstText.toLongOrNull()
        val last = lastText.toLongOrNull()
        val (start, end) = when {
            first == null && last != null -> {
                val suffix = last.coerceAtLeast(0)
                if (suffix == 0L || total == 0L) return Result.Unsatisfiable
                (total - minOf(suffix, total)) to (total - 1)
            }
            first != null && last == null -> first to (total - 1)
            first != null && last != null -> first to minOf(last, total - 1)
            else -> return Result.Unsatisfiable
        }
        if (start < 0 || end < start || start >= total) return Result.Unsatisfiable
        return Result.Partial(start, end)
    }
}