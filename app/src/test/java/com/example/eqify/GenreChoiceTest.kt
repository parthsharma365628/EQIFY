package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreChoiceTest {

    @Test fun presetRoundTrips() {
        val value = GenreChoice.preset("  Late Night ")
        assertEquals("preset:Late Night", value)
        assertTrue(GenreChoice.isPreset(value))
        assertEquals("Late Night", GenreChoice.presetName(value))
    }

    @Test fun genresAreNotPresets() {
        assertFalse(GenreChoice.isPreset("Rock"))
        assertNull(GenreChoice.presetName("Rock"))
        assertFalse(GenreChoice.isPreset("preset:")) // empty name is not a preset
    }

    @Test fun labels() {
        assertEquals("Late Night (your preset)", GenreChoice.label("preset:Late Night"))
        assertEquals("Late Night", GenreChoice.shortLabel("preset:Late Night"))
        assertEquals("Rock", GenreChoice.label("Rock"))
        assertEquals("Rock", GenreChoice.shortLabel("Rock"))
    }

    @Test fun validCorrections() {
        assertTrue(GenreChoice.isValidCorrection("Rock"))
        assertTrue(GenreChoice.isValidCorrection("preset:Gym"))
        assertFalse(GenreChoice.isValidCorrection("Flat"))       // utility profile, not a genre
        assertFalse(GenreChoice.isValidCorrection("Bass Boost")) // utility profile, not a genre
        assertFalse(GenreChoice.isValidCorrection("Polka"))      // unknown genre
        assertFalse(GenreChoice.isValidCorrection("preset:"))
    }

    @Test fun builtInGenreNamesNeverLookLikePresets() {
        EqProfileManager.defaultProfiles.keys.forEach { assertFalse(it, GenreChoice.isPreset(it)) }
    }
}
