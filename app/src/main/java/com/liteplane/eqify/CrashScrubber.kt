package com.liteplane.eqify

/**
 * Removes listening data and secrets from crash-report text before it leaves
 * the device. Pure Kotlin for JVM tests.
 */
object CrashScrubber {
    const val REDACTED = "[redacted]"
    /** Shorter values (e.g. an artist called "A") would erase ordinary words. */
    const val MIN_SECRET_LENGTH = 3

    // Query strings can carry the song title (iTunes/Last.fm lookups).
    private val queryString = Regex("""(https?://[^\s?#]+)\?[^\s#]*""", RegexOption.IGNORE_CASE)

    fun scrub(text: String?, secrets: Collection<String>): String? {
        if (text.isNullOrEmpty()) return text
        var result = queryString.replace(text) { "${it.groupValues[1]}?$REDACTED" }
        secrets.asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_SECRET_LENGTH }
            .distinct()
            .sortedByDescending { it.length } // longer first so "AB Band" wins over "AB"
            .forEach { secret ->
                result = result.replace(secret, REDACTED, ignoreCase = true)
            }
        return result
    }
}
