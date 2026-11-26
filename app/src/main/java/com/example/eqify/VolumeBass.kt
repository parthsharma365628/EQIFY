package com.example.eqify

/**
 * Volume-aware bass (FEATURES_BATCH2_PLAN.md §1): above [THRESHOLD] of media
 * volume the bass boost eases linearly down to [MIN_SCALE] at full volume, so
 * loud listening does not distort or trip the limiter. Pure; unit-tested.
 * The user's chosen bass level is never changed, only the applied amount.
 */
object VolumeBass {
    const val THRESHOLD = 0.6f
    const val MIN_SCALE = 0.5f
    /** Applied bass is rounded to this step, so each volume press is one small, clean change. */
    const val STEP_DB = 0.5f

    fun scale(volumeFraction: Float): Float {
        if (!volumeFraction.isFinite() || volumeFraction <= THRESHOLD) return 1f
        val t = ((volumeFraction - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
        return 1f - t * (1f - MIN_SCALE)
    }

    fun apply(levelDb: Float, volumeFraction: Float, enabled: Boolean): Float {
        if (!levelDb.isFinite() || levelDb <= 0f) return 0f
        if (!enabled) return levelDb
        val scaled = levelDb * scale(volumeFraction)
        return Math.round(scaled / STEP_DB) * STEP_DB
    }

    /** Media volume as 0..1; 0 when the stream reports no range. */
    fun fraction(volume: Int, max: Int): Float =
        if (max <= 0) 0f else (volume.toFloat() / max).coerceIn(0f, 1f)
}
