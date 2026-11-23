package com.example.eqify

import kotlin.math.abs
import kotlin.math.roundToInt

/** One line on the sound-layers graph (FEATURES_NEXT_PLAN.md §3). */
data class SoundLayer(val kind: Kind, val gains: List<Float>) {
    enum class Kind(val label: String) { TONE("Tone"), CORRECTION("Headphone correction"), BASS("Bass"), TOTAL("Total") }
}

/** Pure helpers for the sound-layers graph and its plain-language summary. */
object SoundLayers {
    private val BAND_NAMES = listOf("60 Hz", "170 Hz", "310 Hz", "600 Hz", "1 kHz", "3 kHz", "6 kHz", "12 kHz")
    private const val NOTABLE_DB = 1.5f

    /** The bass boost contribution on its own, as [EqProfileManager.combineGains] applies it. */
    fun bassCurve(bassBoost: Float): List<Float> =
        EqProfileManager.combineGains(FloatArray(8), FloatArray(8), bassBoost).toList()

    /**
     * Layers in drawing order. A layer that changes nothing (all ~0 dB) is left
     * out, except Total, which is always shown.
     */
    fun build(tone: List<Float>, correction: List<Float>, bassBoost: Float, total: List<Float>): List<SoundLayer> =
        listOfNotNull(
            SoundLayer(SoundLayer.Kind.TONE, tone).takeUnless { isFlat(tone) },
            SoundLayer(SoundLayer.Kind.CORRECTION, correction).takeUnless { isFlat(correction) },
            SoundLayer(SoundLayer.Kind.BASS, bassCurve(bassBoost)).takeUnless { bassBoost <= 0f },
            SoundLayer(SoundLayer.Kind.TOTAL, total)
        )

    fun isFlat(gains: List<Float>): Boolean = gains.all { abs(it) < 0.05f }

    /**
     * "Cuts 3–6 kHz by up to 4 dB and boosts 60 Hz by up to 2 dB." Groups
     * neighbouring bands moving the same way; the largest changes come first.
     */
    fun describe(gains: List<Float>): String {
        if (gains.size != BAND_NAMES.size || gains.any { !it.isFinite() }) return "No curve to describe."
        if (gains.all { abs(it) < NOTABLE_DB }) {
            return if (gains.all { abs(it) < 0.05f }) "Flat: changes nothing." else "Nearly flat: every change is under 2 dB."
        }
        data class Run(val from: Int, val to: Int, val boost: Boolean, val peak: Float)
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < gains.size) {
            val g = gains[i]
            if (abs(g) < NOTABLE_DB) { i++; continue }
            val boost = g > 0
            var j = i
            var peak = abs(g)
            while (j + 1 < gains.size && abs(gains[j + 1]) >= NOTABLE_DB && (gains[j + 1] > 0) == boost) {
                j++; peak = maxOf(peak, abs(gains[j]))
            }
            runs += Run(i, j, boost, peak)
            i = j + 1
        }
        val phrases = runs.sortedByDescending { it.peak }.take(3).map { r ->
            val verb = if (r.boost) "boosts" else "cuts"
            "$verb ${range(r.from, r.to)} by up to ${r.peak.roundToInt()} dB"
        }
        val sentence = when (phrases.size) {
            1 -> phrases[0]
            2 -> "${phrases[0]} and ${phrases[1]}"
            else -> "${phrases[0]}, ${phrases[1]} and ${phrases[2]}"
        }
        return sentence.replaceFirstChar { it.uppercase() } + "."
    }

    private fun range(from: Int, to: Int): String {
        if (from == to) return BAND_NAMES[from]
        val a = BAND_NAMES[from]
        val b = BAND_NAMES[to]
        val unitA = a.substringAfter(' ')
        val unitB = b.substringAfter(' ')
        return if (unitA == unitB) "${a.substringBefore(' ')}–$b" else "$a–$b"
    }
}
