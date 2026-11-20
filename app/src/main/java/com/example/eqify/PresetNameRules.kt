package com.example.eqify

/**
 * Single source of truth for user tone-preset names. Pure Kotlin for JVM tests.
 * Names are matched case-insensitively; "Custom" and built-in profile names are reserved.
 */
object PresetNameRules {
    /** Length cap for names created by import. Existing UI flows keep their current behavior. */
    const val MAX_LENGTH = 40

    fun isReserved(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.equals("Custom", ignoreCase = true) ||
            EqProfileManager.defaultProfiles.keys.any { it.equals(trimmed, ignoreCase = true) }
    }

    fun isDuplicate(name: String, existing: Collection<String>, ignore: String? = null): Boolean {
        val trimmed = name.trim()
        return existing.any { it != ignore && it.equals(trimmed, ignoreCase = true) }
    }

    /** Returns null when [name] can be saved as a new preset, otherwise a user-facing reason. */
    fun validateNew(name: String, existing: Collection<String>): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Enter a profile name."
            isReserved(trimmed) || isDuplicate(trimmed, existing) -> "Choose a unique profile name."
            else -> null
        }
    }

    /**
     * A free, non-reserved name derived from [base]: "Night Bass", then
     * "Night Bass (2)", "Night Bass (3)", ... Always within [MAX_LENGTH].
     */
    fun uniqueName(base: String, existing: Collection<String>): String {
        val cleaned = EqProfileCodec.sanitizeName(base, MAX_LENGTH)
            .ifEmpty { EqProfileCodec.FALLBACK_NAME }
        if (!isReserved(cleaned) && !isDuplicate(cleaned, existing)) return cleaned
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = cleaned.take(MAX_LENGTH - suffix.length).trimEnd() + suffix
            if (!isReserved(candidate) && !isDuplicate(candidate, existing)) return candidate
            n++
        }
    }
}
