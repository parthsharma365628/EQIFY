package com.example.eqify

/**
 * The user's versions of built-in curves, by built-in name (FEATURE_CUSTOM_GENRE_CURVES.md).
 * Stored as "v1:Rock=4,2,-1,-2,1,4,4,3|Pop=…". Pure Kotlin for JVM tests.
 */
object GenreCurveOverrides {
    private const val PREFIX = "v1:"

    /** Every built-in except Flat, which must stay the neutral fallback. */
    fun isCustomisable(name: String): Boolean = name != "Flat" && name in EqProfileManager.defaultProfiles

    /** Eight finite gains within ±12 dB. */
    fun isValidCurve(gains: List<Float>): Boolean =
        gains.size == EqProfileManager.BAND_CENTERS_HZ.size && gains.all { it.isFinite() && it in -12f..12f }

    /** True when [gains] matches the built-in exactly (then no override is stored). */
    fun isDefault(name: String, gains: List<Float>): Boolean =
        EqProfileManager.defaultProfiles[name]?.toList() == gains

    fun encode(map: Map<String, List<Float>>): String = PREFIX + map.entries
        .filter { (name, gains) -> isCustomisable(name) && isValidCurve(gains) }
        .joinToString("|") { (name, gains) -> name + "=" + gains.joinToString(",") }

    /** Unknown names, Flat and malformed curves are skipped, never thrown. */
    fun decode(raw: String?): Map<String, List<Float>> {
        if (raw.isNullOrBlank() || !raw.startsWith(PREFIX)) return emptyMap()
        return raw.removePrefix(PREFIX).split("|").mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0]
            val gains = parts[1].split(",").map { it.trim().toFloatOrNull() ?: return@mapNotNull null }
            if (isCustomisable(name) && isValidCurve(gains)) name to gains else null
        }.toMap()
    }
}
