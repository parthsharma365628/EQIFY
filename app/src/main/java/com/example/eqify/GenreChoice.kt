package com.example.eqify

/**
 * What a song's genre correction points at: a built-in genre ("Rock") or one of
 * the user's own presets, stored as "preset:Late Night". Keeping both in the
 * existing correctedGenre column means no database migration, and genre values
 * can never collide with presets because built-in names never start with the prefix.
 * Pure Kotlin for JVM tests.
 */
object GenreChoice {
    const val PRESET_PREFIX = "preset:"

    fun preset(name: String): String = PRESET_PREFIX + name.trim()

    fun isPreset(value: String): Boolean =
        value.startsWith(PRESET_PREFIX) && value.length > PRESET_PREFIX.length

    /** The preset name for a preset value, otherwise null. */
    fun presetName(value: String): String? =
        if (isPreset(value)) value.removePrefix(PRESET_PREFIX) else null

    /** What the user sees: "Late Night (your preset)" or the genre name as is. */
    fun label(value: String): String = presetName(value)?.let { "$it (your preset)" } ?: value

    /** Short form for chips and the active-preset name: just the preset or genre name. */
    fun shortLabel(value: String): String = presetName(value) ?: value

    /** A value a correction may store: a supported genre, or a non-blank preset reference. */
    fun isValidCorrection(value: String): Boolean =
        value in EqProfileManager.correctionGenres || isPreset(value)
}
