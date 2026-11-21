package com.example.eqify

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.eqify.ui.theme.Contrast
import com.example.eqify.ui.theme.EqifyColors
import com.example.eqify.ui.theme.EqifyPalettes
import com.example.eqify.ui.theme.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quality gate (UI_REDESIGN.md §4.6): every shipped palette must stay readable. */
class ThemeContrastTest {

    private fun ratio(a: Color, b: Color) = Contrast.ratio(a.toArgb(), b.toArgb())

    private fun check(failures: MutableList<String>, theme: String, pair: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        if (r < min) failures += "$theme: $pair = ${"%.2f".format(r)} (needs $min)"
    }

    private fun audit(c: EqifyColors, name: String): List<String> {
        val f = mutableListOf<String>()
        for ((surfaceName, s) in listOf("background" to c.background, "surface" to c.surface,
            "surfaceRaised" to c.surfaceRaised, "surfaceHighest" to c.surfaceHighest)) {
            check(f, name, "textPrimary/$surfaceName", c.textPrimary, s, Contrast.TEXT_AA)
            check(f, name, "textMuted/$surfaceName", c.textMuted, s, Contrast.TEXT_AA)
            check(f, name, "accent/$surfaceName", c.accent, s, Contrast.TEXT_AA)
            check(f, name, "error/$surfaceName", c.error, s, Contrast.TEXT_AA)
            check(f, name, "warning/$surfaceName", c.warning, s, Contrast.TEXT_AA)
            check(f, name, "success/$surfaceName", c.success, s, Contrast.TEXT_AA)
            check(f, name, "textFaint/$surfaceName (hints only)", c.textFaint, s, Contrast.LARGE_OR_UI_AA)
            check(f, name, "outlineStrong/$surfaceName (UI)", c.outlineStrong, s, Contrast.LARGE_OR_UI_AA)
            c.chart.forEachIndexed { i, tint -> check(f, name, "chart[$i]/$surfaceName (UI)", tint, s, Contrast.LARGE_OR_UI_AA) }
        }
        check(f, name, "onAccent/accent", c.onAccent, c.accent, Contrast.TEXT_AA)
        check(f, name, "onAccentContainer/accentContainer", c.onAccentContainer, c.accentContainer, Contrast.TEXT_AA)
        check(f, name, "accent/accentContainer", c.accent, c.accentContainer, Contrast.LARGE_OR_UI_AA)
        check(f, name, "onError/error", c.onError, c.error, Contrast.TEXT_AA)
        check(f, name, "onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer, Contrast.TEXT_AA)
        check(f, name, "error/errorContainer", c.error, c.errorContainer, Contrast.TEXT_AA)
        return f
    }

    @Test fun everyPaletteMeetsWcagAa() {
        val failures = EqifyPalettes.all.flatMap { (name, c) -> audit(c, name) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun shipsEveryAccentInDarkPureBlackAndLight() {
        val names = EqifyPalettes.all.map { it.first }
        assertEquals(ThemeAccent.entries.size * 3, names.size)
        ThemeAccent.entries.forEach { a ->
            assertTrue("${a.label} dark" in names && "${a.label} pure black" in names && "${a.label} light" in names)
        }
        assertTrue(EqifyPalettes.all.filter { it.first.endsWith("light") }.none { it.second.isDark })
        assertTrue(EqifyPalettes.all.filter { !it.first.endsWith("light") }.all { it.second.isDark })
    }

    @Test fun pureBlackUsesTrueBlackBackground() {
        ThemeAccent.entries.forEach {
            assertEquals(Color.Black.toArgb(), EqifyPalettes.dark(it, pureBlack = true).background.toArgb())
        }
    }

    @Test fun statusColoursStayDistinctFromTheAccent() {
        // Meaning must survive every theme: warning/success/error never blend into the accent.
        val failures = EqifyPalettes.all.flatMap { (name, c) ->
            listOf("success" to c.success, "warning" to c.warning, "error" to c.error)
                .filter { (_, s) -> colourDistance(s, c.accent) < 0.12 }
                .map { (role, _) -> "$name: $role too close to accent" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Simple RGB distance in 0..√3; enough to catch near-identical hues. */
    private fun colourDistance(a: Color, b: Color): Double {
        val dr = (a.red - b.red).toDouble(); val dg = (a.green - b.green).toDouble(); val db = (a.blue - b.blue).toDouble()
        return Math.sqrt(dr * dr + dg * dg + db * db)
    }

    @Test fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF6FA393.toInt(), 0xFF6FA393.toInt()), 0.0001)
        // #767676 on white is the classic 4.54:1 AA threshold example.
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test fun chartHasAtLeastFourDistinctTints() {
        EqifyPalettes.all.forEach { (name, c) ->
            assertTrue(name, c.chart.size >= 4 && c.chart.distinct().size == c.chart.size)
        }
    }
}
