package com.example.eqify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreStrengthTest {
    private val rock = EqProfileManager.defaultProfiles["Rock"]!!

    @Test fun fullStrengthLeavesTheCurveUnchanged() {
        assertArrayEquals(rock, GenreStrength.apply(rock, 1f), 0f)
    }

    @Test fun zeroIsFlatAndHalfIsHalf() {
        assertArrayEquals(FloatArray(8), GenreStrength.apply(rock, 0f), 0f)
        assertArrayEquals(FloatArray(8) { rock[it] * 0.5f }, GenreStrength.apply(rock, 0.5f), 1e-4f)
    }

    @Test fun strongBoostsButStaysWithinRange() {
        val loud = FloatArray(8) { 11f }
        GenreStrength.apply(loud, 1.25f).forEach { assertEquals(12f, it, 0f) }
        assertArrayEquals(FloatArray(8) { rock[it] * 1.25f }, GenreStrength.apply(rock, 1.25f), 1e-4f)
    }

    @Test fun normalisesStoredValues() {
        assertEquals(1f, GenreStrength.normalise(null), 0f)
        assertEquals(1f, GenreStrength.normalise(Float.NaN), 0f)
        assertEquals(1.25f, GenreStrength.normalise(9f), 0f)
        assertEquals(0f, GenreStrength.normalise(-1f), 0f)
        assertEquals(0.75f, GenreStrength.normalise(0.76f), 1e-6f)
    }

    @Test fun doesNotChangeTheInput() {
        val copy = rock.copyOf()
        GenreStrength.apply(rock, 0.3f)
        assertArrayEquals(copy, rock, 0f)
    }

    @Test fun labels() {
        assertEquals("Subtle, 40%", GenreStrength.label(0.4f))
        assertEquals("Balanced, 100%", GenreStrength.label(1f))
        assertEquals("Strong, 125%", GenreStrength.label(1.25f))
    }
}
