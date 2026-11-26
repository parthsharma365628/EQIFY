package com.liteplane.eqify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreCurveOverridesTest {
    private val myRock = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
    private val flat = EqProfileManager.defaultProfiles["Flat"]!!

    // ── Codec and rules ───────────────────────────────────────────────

    @Test fun roundTrips() {
        val map = mapOf("Rock" to myRock, "Bass Boost" to listOf(9f, 6f, 3f, 0f, 0f, 0f, 0f, -0.5f))
        assertEquals(map, GenreCurveOverrides.decode(GenreCurveOverrides.encode(map)))
    }

    @Test fun flatAndUnknownNamesAreNeverStored() {
        assertFalse(GenreCurveOverrides.isCustomisable("Flat"))
        assertFalse(GenreCurveOverrides.isCustomisable("Polka"))
        assertTrue(GenreCurveOverrides.isCustomisable("Rock"))
        assertTrue(GenreCurveOverrides.isCustomisable("Bass Boost"))
        val encoded = GenreCurveOverrides.encode(mapOf("Flat" to myRock, "Polka" to myRock, "Rock" to myRock))
        assertEquals(mapOf("Rock" to myRock), GenreCurveOverrides.decode(encoded))
    }

    @Test fun invalidCurvesAreRejected() {
        assertFalse(GenreCurveOverrides.isValidCurve(List(7) { 0f }))
        assertFalse(GenreCurveOverrides.isValidCurve(List(8) { 13f }))
        assertFalse(GenreCurveOverrides.isValidCurve(listOf(Float.NaN) + List(7) { 0f }))
        assertTrue(GenreCurveOverrides.isValidCurve(myRock))
    }

    @Test fun malformedStorageIsIgnored() {
        assertTrue(GenreCurveOverrides.decode(null).isEmpty())
        assertTrue(GenreCurveOverrides.decode("garbage").isEmpty())
        assertEquals(mapOf("Rock" to myRock),
            GenreCurveOverrides.decode("v1:Rock=1,2,3,4,5,6,7,8|Pop=1,2,x,4,5,6,7,8|Jazz=1,2|=1|Flat=1,1,1,1,1,1,1,1"))
    }

    @Test fun detectsDefaultCurves() {
        assertTrue(GenreCurveOverrides.isDefault("Rock", EqProfileManager.defaultProfiles["Rock"]!!.toList()))
        assertFalse(GenreCurveOverrides.isDefault("Rock", myRock))
    }

    // ── Resolution ────────────────────────────────────────────────────

    private val overrides = mapOf("Rock" to myRock)

    @Test fun builtInsUseTheOverrideWhenPresent() {
        assertArrayEquals(myRock.toFloatArray(), EqProfileManager.effectiveGains("Rock", overrides), 0f)
        assertArrayEquals(EqProfileManager.defaultProfiles["Pop"], EqProfileManager.effectiveGains("Pop", overrides), 0f)
    }

    @Test fun genreResolutionUsesOverridesForDirectCaseAndKeywordMatches() {
        assertArrayEquals(myRock.toFloatArray(), EqProfileManager.resolveGenreGains("Rock", overrides), 0f)
        assertArrayEquals(myRock.toFloatArray(), EqProfileManager.resolveGenreGains("rock", overrides), 0f)
        // "Alternative" maps to Rock by keyword.
        assertArrayEquals(myRock.toFloatArray(), EqProfileManager.resolveGenreGains("Alternative", overrides), 0f)
    }

    @Test fun flatAndUnknownGenresAreNeverOverridden() {
        val sneaky = mapOf("Flat" to myRock)
        assertArrayEquals(flat, EqProfileManager.effectiveGains("Flat", sneaky), 0f)
        assertArrayEquals(flat, EqProfileManager.resolveGenreGains("", sneaky), 0f)
        assertArrayEquals(flat, EqProfileManager.resolveGenreGains("Polka", sneaky), 0f)
    }

    @Test fun withoutOverridesBehaviourIsUnchanged() {
        assertArrayEquals(EqProfileManager.defaultProfiles["Rock"], EqProfileManager.resolveGenreGains("Rock"), 0f)
    }

    @Test fun invalidOverrideFallsBackToTheBuiltIn() {
        val bad = mapOf("Rock" to List(8) { 20f })
        assertArrayEquals(EqProfileManager.defaultProfiles["Rock"], EqProfileManager.effectiveGains("Rock", bad), 0f)
    }
}
