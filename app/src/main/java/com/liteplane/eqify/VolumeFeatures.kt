package com.liteplane.eqify

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Volume cap (FEATURES_BATCH3_PLAN.md §A). Pure; unit-tested.
 * The cap only ever lowers the volume, so it can never fight another limiter.
 */
object VolumeCap {
    const val MIN = 0.5f
    const val MAX = 0.9f
    const val DEFAULT = 0.7f
    private const val STEP = 0.1f

    fun normalise(value: Float?): Float {
        if (value == null || !value.isFinite()) return DEFAULT
        val stepped = (value / STEP).roundToInt() * STEP
        return stepped.coerceIn(MIN, MAX)
    }

    /** Highest allowed volume index for [capFraction] of [max]; at least 1 so music stays audible. */
    fun capIndex(max: Int, capFraction: Float): Int =
        if (max <= 0) 0 else floor(max * normalise(capFraction) + 1e-4f).toInt().coerceIn(1, max)

    /** The index to set when [volume] is above the cap, or null when it's fine. */
    fun clampIndex(volume: Int, max: Int, capFraction: Float): Int? {
        if (max <= 0) return null
        val cap = capIndex(max, capFraction)
        return if (volume > cap) cap else null
    }

    /** Never restore above the cap (used by the sleep timer). */
    fun limit(volume: Int, max: Int, enabled: Boolean, capFraction: Float): Int =
        if (!enabled || max <= 0) volume else minOf(volume, capIndex(max, capFraction))
}

/**
 * Loudness compensation (FEATURES_BATCH3_PLAN.md §B, #2). Below [START] of media
 * volume it adds a bass shelf and a small treble lift, strongest at [FULL] and
 * below. It is zero at [START] and above, so it never overlaps volume-aware bass
 * (which works only above [VolumeBass.THRESHOLD]). Pure; unit-tested.
 */
object LoudnessCompensation {
    const val START = 0.5f
    const val FULL = 0.1f
    const val MAX_BASS_DB = 6f
    const val MAX_TREBLE_DB = 3f
    private const val STEP_DB = 0.5f

    /** Per-band shape at full strength, matching [EqProfileManager.BAND_CENTERS_HZ] (60 Hz … 12 kHz). */
    private val SHAPE = floatArrayOf(
        MAX_BASS_DB, MAX_BASS_DB * 0.75f, MAX_BASS_DB * 0.35f, 0f,
        0f, 0f, MAX_TREBLE_DB * 0.5f, MAX_TREBLE_DB
    )

    val ZERO: List<Float> = List(8) { 0f }

    fun amount(volumeFraction: Float): Float {
        if (!volumeFraction.isFinite() || volumeFraction >= START) return 0f
        return ((START - volumeFraction) / (START - FULL)).coerceIn(0f, 1f)
    }

    fun curve(volumeFraction: Float, enabled: Boolean): List<Float> {
        if (!enabled) return ZERO
        val t = amount(volumeFraction)
        if (t <= 0f) return ZERO
        return SHAPE.map { Math.round(it * t / STEP_DB) * STEP_DB }
    }

    fun add(gains: FloatArray, loudness: List<Float>): FloatArray =
        FloatArray(gains.size) { i -> (gains[i] + loudness.getOrElse(i) { 0f }).coerceIn(-12f, 12f) }

    /** Its biggest boost, for the Home note. */
    fun peak(loudness: List<Float>): Float = loudness.maxOrNull() ?: 0f
}
