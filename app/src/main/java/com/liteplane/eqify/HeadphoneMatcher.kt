package com.liteplane.eqify

/**
 * Matches a connected device's name to an AutoEQ catalog model
 * (FEATURE_HEADPHONE_DETECTION.md). Pure Kotlin for JVM tests.
 */
object HeadphoneMatcher {

    sealed interface Result {
        /** Safe to select automatically. */
        data class Confident(val model: String) : Result
        /** Plausible, but ask the user first. */
        data class Likely(val model: String) : Result
        data object None : Result
    }

    /** Device names shorter than this never match by containment ("Buds", "Pro"). */
    const val MIN_DEVICE_CHARS = 5
    /** Catalog names shorter than this never match inside a longer device name. */
    const val MIN_CATALOG_CHARS = 6

    fun match(deviceName: String, catalog: List<String>): Result {
        val device = normalise(stripNoise(deviceName))
        if (device.isEmpty()) return Result.None

        var exact: String? = null
        val strong = mutableListOf<String>()
        val partial = mutableListOf<String>()
        for (candidate in catalog) {
            val c = normalise(candidate)
            if (c.isEmpty()) continue
            when {
                c == device -> { exact = candidate; break }
                // Brand omitted on the device: "Sony WH-1000XM5" <- "WH-1000XM5".
                device.length >= MIN_DEVICE_CHARS && c.endsWith(device) -> strong += candidate
                // Extra words on the device: "Parth's Galaxy Buds2 Pro" contains "Galaxy Buds2 Pro".
                c.length >= MIN_CATALOG_CHARS && device.contains(c) -> strong += candidate
                device.length >= MIN_DEVICE_CHARS && c.contains(device) -> partial += candidate
            }
        }
        exact?.let { return Result.Confident(it) }
        if (strong.size == 1) return Result.Confident(strong.single())
        // Several strong hits: the one whose length is closest to the device name.
        if (strong.isNotEmpty()) return Result.Likely(strong.minBy { kotlin.math.abs(normalise(it).length - device.length) })
        if (partial.isNotEmpty()) return Result.Likely(partial.minBy { normalise(it).length })
        return Result.None
    }

    /** Lowercase letters and digits only: "WH-1000XM5" -> "wh1000xm5". */
    fun normalise(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Removes things a catalog name never has: a leading "LE" (LE Audio
     * devices) and an owner word such as "Parth's" / "Parths'".
     */
    fun stripNoise(name: String): String = name.trim()
        .replace(Regex("""^LE[\s_\-]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^\S+['’]s?\s+""", RegexOption.IGNORE_CASE), "")
        .trim()
}
