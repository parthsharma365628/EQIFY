package com.liteplane.eqify

/**
 * "Genre strength": how strongly automatic genre EQ shapes the sound
 * (FEATURES_NEXT_PLAN.md §2). It scales only the automatic tone, never manual
 * presets, headphone correction or bass. Pure Kotlin.
 */
object GenreStrength {
    const val DEFAULT = 1f
    const val MIN = 0f
    const val MAX = 1.25f
    const val STEP = 0.05f

    /** Clamps to [MIN, MAX] on 5% steps; invalid values become [DEFAULT]. */
    fun normalise(value: Float?): Float {
        if (value == null || !value.isFinite()) return DEFAULT
        val clamped = value.coerceIn(MIN, MAX)
        return Math.round(clamped / STEP) * STEP
    }

    /** The automatic tone at [strength]; 100% returns the curve unchanged. */
    fun apply(tone: FloatArray, strength: Float): FloatArray {
        val s = normalise(strength)
        if (s == DEFAULT) return tone.copyOf()
        return FloatArray(tone.size) { i -> (tone[i] * s).coerceIn(-12f, 12f) }
    }

    fun label(strength: Float): String {
        val s = normalise(strength)
        val word = when {
            s <= 0.5f -> "Subtle"
            s <= 1f -> "Balanced"
            else -> "Strong"
        }
        return "$word, ${Math.round(s * 100)}%"
    }
}
