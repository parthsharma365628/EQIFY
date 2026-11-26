package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundLayersTest {

    @Test fun describesGroupedCutsAndBoostsLargestFirst() {
        val g = listOf(2.2f, 0.4f, 0f, 0f, -0.5f, -3.6f, -4.2f, 0.3f)
        assertEquals("Cuts 3–6 kHz by up to 4 dB and boosts 60 Hz by up to 2 dB.", SoundLayers.describe(g))
    }

    @Test fun mixedUnitRangesKeepBothUnits() {
        val g = listOf(0f, 0f, 0f, 3f, 3f, 0f, 0f, 0f)
        assertEquals("Boosts 600 Hz–1 kHz by up to 3 dB.", SoundLayers.describe(g))
    }

    @Test fun signChangeSplitsRuns() {
        val g = listOf(4f, -4f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals("Boosts 60 Hz by up to 4 dB and cuts 170 Hz by up to 4 dB.", SoundLayers.describe(g))
    }

    @Test fun atMostThreePhrases() {
        val g = listOf(5f, 0f, -4f, 0f, 3f, 0f, -2f, 0f)
        assertEquals("Boosts 60 Hz by up to 5 dB, cuts 310 Hz by up to 4 dB and boosts 1 kHz by up to 3 dB.",
            SoundLayers.describe(g))
    }

    @Test fun flatAndNearlyFlat() {
        assertEquals("Flat: changes nothing.", SoundLayers.describe(List(8) { 0f }))
        assertEquals("Nearly flat: every change is under 2 dB.", SoundLayers.describe(List(8) { 1f }))
        assertEquals("No curve to describe.", SoundLayers.describe(List(7) { 1f }))
    }

    @Test fun buildSkipsLayersThatChangeNothingButKeepsTotal() {
        val tone = List(8) { 1f }
        val flat = List(8) { 0f }
        val layers = SoundLayers.build(tone, flat, 0f, tone)
        assertEquals(listOf(SoundLayer.Kind.TONE, SoundLayer.Kind.TOTAL), layers.map { it.kind })
        val all = SoundLayers.build(tone, tone, 3f, flat)
        assertEquals(SoundLayer.Kind.entries.toList(), all.map { it.kind })
    }

    @Test fun bassCurveMatchesTheGainChain() {
        assertEquals(listOf(6f, 6f * 0.7f, 6f * 0.4f, 0f, 0f, 0f, 0f, 0f), SoundLayers.bassCurve(6f))
    }
}
