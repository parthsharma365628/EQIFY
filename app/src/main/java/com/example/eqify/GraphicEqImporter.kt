package com.example.eqify

import kotlin.math.ln
import kotlin.math.round

/** Converts a standalone Equalizer APO GraphicEQ export to EQify's eight bands. */
object GraphicEqImporter {
    data class Result(val gains: FloatArray, val limited: Boolean)

    fun convert(text: String): Result {
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size == 1 && lines[0].startsWith("GraphicEQ:", ignoreCase = true)) {
            "Choose a standalone GraphicEQ file (one GraphicEQ line, no other directives)."
        }
        val body = lines[0].substringAfter(':')
        val tokens = body.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        require(tokens.size >= 2) { "GraphicEQ needs at least two frequency/gain pairs." }
        return convertPoints(tokens)
    }

    /** Accepts the two-column, ten-band table copied from autoeq.app. */
    fun convertPastedTable(text: String): Result {
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val data = if (lines.firstOrNull()?.matches(
                Regex("frequency\\s*\\(hz\\)\\s+gain\\s*\\(db\\)", RegexOption.IGNORE_CASE)
            ) == true) lines.drop(1) else lines
        require(data.size == 10) { "Paste all 10 frequency/gain rows (one pair per line)." }
        return convertPoints(data)
    }

    private fun convertPoints(tokens: List<String>): Result {
        val points = tokens.mapIndexed { index, token ->
            val parts = token.split(Regex("\\s+"))
            require(parts.size == 2) { "Invalid row ${index + 1}: expected frequency and gain." }
            val hz = parts[0].toDoubleOrNull()
            val db = parts[1].replace('−', '-').toDoubleOrNull()
            require(hz != null && hz.isFinite() && hz > 0.0 && hz <= 100_000.0) {
                "Invalid frequency in row ${index + 1}."
            }
            require(db != null && db.isFinite() && db in -100.0..100.0) {
                "Invalid gain in row ${index + 1}."
            }
            hz to db
        }.sortedBy { it.first }
        require(points.zipWithNext().all { (a, b) -> a.first < b.first }) {
            "GraphicEQ contains duplicate frequencies."
        }
        var limited = false
        val gains = FloatArray(EqProfileManager.BAND_CENTERS_HZ.size) { i ->
            val target = EqProfileManager.BAND_CENTERS_HZ[i].toDouble()
            val gain = when {
                target <= points.first().first -> points.first().second
                target >= points.last().first -> points.last().second
                else -> {
                    val upper = points.indexOfFirst { it.first >= target }
                    val (f0, g0) = points[upper - 1]
                    val (f1, g1) = points[upper]
                    g0 + (g1 - g0) * ln(target / f0) / ln(f1 / f0)
                }
            }
            if (gain < -12.0 || gain > 12.0) limited = true
            (round(gain.coerceIn(-12.0, 12.0) * 10.0) / 10.0).toFloat()
        }
        return Result(gains, limited)
    }
}
