package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Pure logic for FEATURES_BATCH4_PLAN.md; unit-tested in Batch4Test.

/** S1: lowers EQ output by its mean boost while comparing with Original. */
object LevelMatch {
    const val MAX_OFFSET_DB = 12f

    /** Positive dB to subtract from every band; 0 when the curve doesn't boost on average. */
    fun offset(gains: FloatArray): Float {
        if (gains.isEmpty()) return 0f
        val mean = gains.map { if (it.isFinite()) it else 0f }.average().toFloat()
        return Math.round(mean.coerceIn(0f, MAX_OFFSET_DB) * 2f) / 2f
    }

    fun apply(gains: FloatArray, offsetDb: Float): FloatArray =
        if (offsetDb <= 0f) gains else FloatArray(gains.size) { (gains[it] - offsetDb).coerceIn(-12f, 12f) }
}

/** S2: stereo balance, −100 (left) … +100 (right). Only the far side is turned down. */
object StereoBalance {
    const val MAX_CUT_DB = 12f

    fun normalise(value: Float?): Float =
        if (value == null || !value.isFinite()) 0f else (Math.round(value / 5f) * 5f).coerceIn(-100f, 100f)

    /** (left dB, right dB), each ≤ 0. */
    fun channelGains(balance: Float): Pair<Float, Float> {
        val b = normalise(balance) / 100f
        // "+ 0f" turns -0.0 into 0.0 so centre is exactly neutral.
        return if (b >= 0f) (-MAX_CUT_DB * b + 0f) to 0f else 0f to (MAX_CUT_DB * b + 0f)
    }

    fun label(balance: Float): String {
        val b = normalise(balance).toInt()
        return when {
            b == 0 -> "Centre"
            b < 0 -> "${-b}% left"
            else -> "$b% right"
        }
    }
}

/** S4: favourite presets, in the user's order. */
object PresetFavorites {
    fun toggle(list: List<String>, name: String): List<String> =
        if (list.any { it.equals(name, true) }) list.filterNot { it.equals(name, true) } else list + name

    fun move(list: List<String>, name: String, up: Boolean): List<String> {
        val i = list.indexOfFirst { it.equals(name, true) }
        if (i < 0) return list
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return list
        return list.toMutableList().apply { this[i] = this[j].also { this[j] = this[i] } }
    }

    fun rename(list: List<String>, from: String, to: String): List<String> =
        list.map { if (it.equals(from, true)) to else it }.distinctBy { it.lowercase() }

    fun remove(list: List<String>, name: String): List<String> = list.filterNot { it.equals(name, true) }

    /** Favourites that still exist, in favourite order. */
    fun present(list: List<String>, available: Collection<String>): List<String> =
        list.mapNotNull { fav -> available.firstOrNull { it.equals(fav, true) } }.distinct()
}

/** S6: in-memory log of automatic changes. Never persisted, never has song titles. */
object ChangeLog {
    data class Entry(val atMillis: Long, val text: String)
    const val CAPACITY = 30
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(text: String, atMillis: Long = System.currentTimeMillis()) {
        val current = _entries.value
        if (current.firstOrNull()?.text == text) return            // no duplicate in a row
        _entries.value = (listOf(Entry(atMillis, text)) + current).take(CAPACITY)
    }

    fun clear() { _entries.value = emptyList() }
}

/** S7: last media volume per output, stored as fractions so a new phone with other steps still works. */
object DeviceVolumes {
    private const val PREFIX = "v1:"

    fun encode(map: Map<String, Float>): String = PREFIX + map.entries
        .filter { it.key.isNotBlank() && it.value.isFinite() }
        .joinToString("|") { URLEncoder.encode(it.key, "UTF-8") + "=" + it.value.coerceIn(0f, 1f) }

    fun decode(raw: String?): Map<String, Float> {
        if (raw.isNullOrBlank() || !raw.startsWith(PREFIX)) return emptyMap()
        return raw.removePrefix(PREFIX).split("|").mapNotNull { e ->
            val kv = e.split("=", limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(kv[0], "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
            val v = kv[1].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (key == null || v == null) null else key to v
        }.toMap()
    }

    fun toIndex(fraction: Float, max: Int): Int = if (max <= 0) 0 else Math.round(fraction.coerceIn(0f, 1f) * max)
}

/**
 * L: safe-listening estimate (FEATURES_BATCH4_PLAN.md, research section).
 * WHO–ITU H.870: 80 dB (adults) or 75 dB (sensitive) for 40 h a week, 3 dB exchange rate,
 * rolling 7 days. The level at the ear is estimated, not measured.
 */
object SafeListening {
    enum class Mode(val referenceDb: Float, val label: String) {
        ADULT(80f, "Adults (80 dB, 40 h a week)"),
        SENSITIVE(75f, "Sensitive or children (75 dB, 40 h a week)");
        companion object {
            fun fromStorage(v: String?) = entries.firstOrNull { it.name == v } ?: ADULT
        }
    }

    const val MAX_OUTPUT_DBA = 100f          // EN 50332 ceiling for personal players
    const val WEEK_HOURS = 40.0
    const val WINDOW_DAYS = 7
    const val NOTE_FROM = 0.8                 // Home shows a note from 80%
    private const val MAX_EQ_BOOST = 6f

    /** Android's default media volume curve: (percent, dB) points. */
    private val CURVE = listOf(0f to -96f, 1f to -58f, 20f to -40f, 60f to -17f, 100f to 0f)

    fun volumeAttenuationDb(fraction: Float): Float {
        if (!fraction.isFinite() || fraction <= 0f) return -96f
        val p = (fraction * 100f).coerceIn(0f, 100f)
        for (i in 1 until CURVE.size) {
            val (p0, d0) = CURVE[i - 1]; val (p1, d1) = CURVE[i]
            if (p <= p1) return d0 + (d1 - d0) * (p - p0) / (p1 - p0)
        }
        return 0f
    }

    /** Mean positive output gain, capped: louder EQ, more exposure. */
    fun eqBoostDb(outputGains: FloatArray): Float =
        if (outputGains.isEmpty()) 0f
        else outputGains.map { if (it.isFinite()) it.coerceAtLeast(0f) else 0f }.average().toFloat().coerceIn(0f, MAX_EQ_BOOST)

    fun estimatedLevelDb(fraction: Float, outputGains: FloatArray): Float =
        MAX_OUTPUT_DBA + volumeAttenuationDb(fraction) + eqBoostDb(outputGains)

    /** Share of the weekly allowance used by [seconds] at [levelDb]. */
    fun doseFor(levelDb: Float, seconds: Double, mode: Mode): Double =
        if (!levelDb.isFinite() || seconds <= 0.0) 0.0
        else 10.0.pow((levelDb - mode.referenceDb) / 10.0) * seconds / (WEEK_HOURS * 3600.0)

    // ── 7-day window as day buckets ───────────────────────────────────
    private const val PREFIX = "v1:"

    fun encode(days: Map<LocalDate, Double>): String = PREFIX + days.entries
        .filter { it.value.isFinite() && it.value > 0.0 }
        .sortedBy { it.key }
        .joinToString("|") { "${it.key}=${"%.6f".format(java.util.Locale.ROOT, it.value)}" }

    fun decode(raw: String?): Map<LocalDate, Double> {
        if (raw.isNullOrBlank() || !raw.startsWith(PREFIX)) return emptyMap()
        return raw.removePrefix(PREFIX).split("|").mapNotNull { e ->
            val kv = e.split("=", limit = 2)
            val day = kv.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val v = kv.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            if (day == null || v == null) null else day to v
        }.toMap()
    }

    /** Keeps only the last [WINDOW_DAYS] days ending [today]. */
    fun prune(days: Map<LocalDate, Double>, today: LocalDate): Map<LocalDate, Double> {
        val first = today.minusDays((WINDOW_DAYS - 1).toLong())
        return days.filterKeys { !it.isBefore(first) && !it.isAfter(today) }
    }

    fun weekly(days: Map<LocalDate, Double>, today: LocalDate): Double = prune(days, today).values.sum()

    fun add(days: Map<LocalDate, Double>, today: LocalDate, dose: Double): Map<LocalDate, Double> =
        prune(days + (today to ((days[today] ?: 0.0) + dose)), today)

    /** The 100% step to alert for, or null: 1 at 100%, 2 at 200%… once each. */
    fun alertStep(weekly: Double, lastAlerted: Int): Int? {
        val step = weekly.toInt()
        return if (step >= 1 && step > lastAlerted) step else null
    }

    fun percent(weekly: Double): Int = Math.round(weekly * 100).toInt()
}

/** P: AutoEQ / Equalizer APO ParametricEQ.txt → EQify's 8 bands. */
object ParametricEqImporter {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }
    data class Filter(val type: Type, val hz: Double, val gainDb: Double, val q: Double)
    data class Result(val gains: FloatArray, val limited: Boolean, val filters: Int)

    private const val FS = 48_000.0
    private val LINE = Regex(
        """filter\s*\d*\s*:\s*on\s+(pk|peq|lsc|ls|hsc|hs)\s+fc\s+([\d.]+)\s*hz\s+gain\s+(-?[\d.]+)\s*db(?:\s+q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE
    )

    fun looksParametric(text: String): Boolean = LINE.containsMatchIn(text)

    fun parse(text: String): List<Filter> {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim().replace('−', '-') }
            .filter { it.isNotEmpty() }.toList()
        val filters = mutableListOf<Filter>()
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("preamp", true) || line.startsWith("#") -> Unit
                line.startsWith("filter", true) -> {
                    if (Regex("""filter\s*\d*\s*:\s*off""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return@forEachIndexed
                    val m = LINE.find(line) ?: throw IllegalArgumentException(
                        "Line ${i + 1} isn't a supported filter (PK, LSC or HSC with Fc, Gain and Q).")
                    val type = when (m.groupValues[1].lowercase()) {
                        "pk", "peq" -> Type.PEAK
                        "lsc", "ls" -> Type.LOW_SHELF
                        else -> Type.HIGH_SHELF
                    }
                    val hz = m.groupValues[2].toDouble()
                    val gain = m.groupValues[3].toDouble()
                    val q = m.groupValues[4].toDoubleOrNull() ?: 0.707
                    require(hz in 10.0..22_000.0) { "Line ${i + 1}: frequency out of range." }
                    require(gain in -30.0..30.0) { "Line ${i + 1}: gain out of range." }
                    require(q in 0.05..20.0) { "Line ${i + 1}: Q out of range." }
                    filters += Filter(type, hz, gain, q)
                }
                else -> throw IllegalArgumentException("Line ${i + 1} isn't part of a ParametricEQ file.")
            }
        }
        require(filters.isNotEmpty()) { "No filters found. Choose an AutoEQ ParametricEQ.txt file." }
        require(filters.size <= 64) { "Too many filters (64 at most)." }
        return filters
    }

    /** RBJ cookbook biquad magnitude in dB at [hz]. */
    fun responseDb(filter: Filter, hz: Double): Double {
        val a = 10.0.pow(filter.gainDb / 40.0)
        val w0 = 2 * PI * filter.hz / FS
        val alpha = sin(w0) / (2 * filter.q)
        val cw = cos(w0)
        val (b, den) = when (filter.type) {
            Type.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a) to
                doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            Type.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s)) to
                    doubleArrayOf((a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
            }
            Type.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s)) to
                    doubleArrayOf((a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
            }
        }
        val w = 2 * PI * hz / FS
        fun mag(c: DoubleArray): Double {
            val re = c[0] + c[1] * cos(-w) + c[2] * cos(-2 * w)
            val im = c[1] * sin(-w) + c[2] * sin(-2 * w)
            return hypot(re, im)
        }
        return 20 * log10(mag(b) / mag(den))
    }

    fun totalDb(filters: List<Filter>, hz: Double): Double = filters.sumOf { responseDb(it, hz) }

    /** Each band = mean response over its log span (half-way to its neighbours). */
    fun toBands(filters: List<Filter>): Result {
        val centres = EqProfileManager.BAND_CENTERS_HZ.map { it.toDouble() }
        var limited = false
        val gains = FloatArray(centres.size) { i ->
            val lo = if (i == 0) centres[0] / 2 else sqrt(centres[i - 1] * centres[i])
            val hi = if (i == centres.lastIndex) minOf(centres[i] * 1.6, 20_000.0) else sqrt(centres[i] * centres[i + 1])
            val steps = 24
            val mean = (0..steps).map { k -> totalDb(filters, exp(ln(lo) + (ln(hi) - ln(lo)) * k / steps)) }.average()
            if (mean < -12.0 || mean > 12.0) limited = true
            (Math.round(mean.coerceIn(-12.0, 12.0) * 10.0) / 10.0).toFloat()
        }
        return Result(gains, limited, filters.size)
    }

    fun convert(text: String): Result = toBands(parse(text))
}

/** A: FFT bytes from Visualizer → 32 log-spaced bars, 0..1. */
object SpectrumBars {
    const val BARS = 32
    private const val MIN_HZ = 30.0
    private const val MAX_HZ = 16_000.0
    private const val FLOOR_DB = -70.0

    /** [fft] is Visualizer.getFft output (re/im byte pairs); [samplingRateMilliHz] from the Visualizer. */
    fun fromFft(fft: ByteArray, samplingRateMilliHz: Int): FloatArray {
        val n = fft.size / 2
        if (n < 2 || samplingRateMilliHz <= 0) return FloatArray(BARS)
        val nyquist = samplingRateMilliHz / 2000.0
        val mags = DoubleArray(n) { k ->
            when (k) {
                0 -> abs(fft[0].toDouble())
                else -> hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
            }
        }
        return FloatArray(BARS) { b ->
            val f0 = MIN_HZ * (MAX_HZ / MIN_HZ).pow(b.toDouble() / BARS)
            val f1 = MIN_HZ * (MAX_HZ / MIN_HZ).pow((b + 1).toDouble() / BARS)
            val k0 = (f0 / nyquist * (n - 1)).toInt().coerceIn(1, n - 1)
            val k1 = (f1 / nyquist * (n - 1)).toInt().coerceIn(k0, n - 1)
            val peak = (k0..k1).maxOf { mags[it] }
            val db = 20 * log10((peak / 128.0).coerceAtLeast(1e-6))
            ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** Smooth fall and peak hold for display. */
    fun smooth(previous: FloatArray, next: FloatArray, fall: Float = 0.08f): FloatArray =
        FloatArray(BARS) { i -> maxOf(next.getOrElse(i) { 0f }, previous.getOrElse(i) { 0f } - fall) }
}
