package com.liteplane.eqify

import com.liteplane.eqify.ui.components.dbToY
import com.liteplane.eqify.ui.components.formatValue
import com.liteplane.eqify.ui.components.toneCurvePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentMathTest {

    @Test fun zeroDbIsVerticalCentre() = assertEquals(50f, dbToY(0f, 100f), 0.001f)

    @Test fun rangeMapsToTopAndBottom() {
        assertEquals(0f, dbToY(12f, 100f), 0.001f)
        assertEquals(100f, dbToY(-12f, 100f), 0.001f)
        assertEquals(25f, dbToY(6f, 100f), 0.001f)
    }

    @Test fun valuesBeyondRangeAreClamped() {
        assertEquals(0f, dbToY(20f, 100f), 0.001f)
        assertEquals(100f, dbToY(-30f, 100f), 0.001f)
    }

    @Test fun pointsSitAtSliderColumnCentres() {
        val pts = toneCurvePoints(List(8) { 0f }, width = 800f, height = 100f)
        assertEquals(8, pts.size)
        assertEquals(50f, pts.first().x, 0.001f)   // first column centre
        assertEquals(750f, pts.last().x, 0.001f)   // last column centre
        assertTrue(pts.all { it.y == 50f })
        assertTrue(toneCurvePoints(emptyList(), 100f, 100f).isEmpty())
    }

    @Test fun boostIsAboveCutIsBelow() {
        val pts = toneCurvePoints(listOf(6f, -6f), width = 200f, height = 100f)
        assertTrue(pts[0].y < 50f)
        assertTrue(pts[1].y > 50f)
    }

    @Test fun formatsSignedTabularValues() {
        val thin = " "
        assertEquals("+3.5${thin}dB", formatValue(3.5f, "dB", signed = true))
        assertEquals("-12.0${thin}dB", formatValue(-12f, "dB", signed = true))
        assertEquals("+0.0${thin}dB", formatValue(-0.01f, "dB", signed = true)) // never "-0.0"
        assertEquals("42$thin%", formatValue(42f, "%", decimals = 0))
        assertEquals("1.5", formatValue(1.5f, ""))
    }

    @Test fun formattingIgnoresDeviceLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // would use a decimal comma
            assertEquals("+3.5 dB", formatValue(3.5f, "dB", signed = true))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
