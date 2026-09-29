package dev.accessaudit.core.parse

/**
 * Parses the compact duration strings Android prints next to appop accesses,
 * such as `-1d2h3m4s5ms`, `+1m30s` or `-9h`.
 *
 * `ms` has to be matched before the single-letter units, otherwise `5ms` reads
 * as 5 minutes followed by a stray `s`.
 */
public object RelativeTime {

    private val TOKEN = Regex("""(\d+)(ms|d|h|m|s)""")

    /**
     * @return signed milliseconds, or null when [text] contains no duration
     *   token at all (so callers can fall back to an absolute timestamp).
     */
    public fun parseSignedMillis(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val negative = trimmed.startsWith("-")
        val body = trimmed.removePrefix("-").removePrefix("+")
        val matches = TOKEN.findAll(body).toList()
        if (matches.isEmpty()) return null

        // Reject strings that are only partly a duration (e.g. a package name
        // that happens to contain "2s"): every character must belong to a token.
        val consumed = matches.sumOf { it.value.length }
        if (consumed != body.length) return null

        var total = 0L
        for (match in matches) {
            val value = match.groupValues[1].toLongOrNull() ?: return null
            total += when (match.groupValues[2]) {
                "ms" -> value
                "s" -> value * 1_000
                "m" -> value * 60_000
                "h" -> value * 3_600_000
                "d" -> value * 86_400_000
                else -> return null
            }
        }
        return if (negative) -total else total
    }

    /** Unsigned variant for `duration=` fields, which are always positive. */
    public fun parseDurationMillis(text: String): Long =
        parseSignedMillis(text)?.let { if (it < 0) -it else it } ?: 0L
}
