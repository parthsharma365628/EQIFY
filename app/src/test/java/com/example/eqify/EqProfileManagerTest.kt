package com.example.eqify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqProfileManagerTest {

    @Test
    fun mapsLogicalGainsToActualDeviceFrequencies() {
        val mapped = EqProfileManager.mapToDeviceBands(
            intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000),
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            shortArrayOf(-1_500, 1_500)
        )

        assertArrayEquals(shortArrayOf(100, 300, 500, 600, 800), mapped)
    }

    @Test
    fun clampsMappedLevelsToDeviceRange() {
        val mapped = EqProfileManager.mapToDeviceBands(
            intArrayOf(60_000, 12_000_000),
            floatArrayOf(20f, 0f, 0f, 0f, 0f, 0f, 0f, -20f),
            shortArrayOf(-1_200, 1_200)
        )

        assertArrayEquals(shortArrayOf(1_200, -1_200), mapped)
    }

    @Test
    fun combinesToneHeadphoneAndBassExactlyOnce() {
        val result = EqProfileManager.combineGains(
            toneGains = FloatArray(8) { 1f },
            headphoneCorrection = FloatArray(8) { 2f },
            bassBoost = 5f
        )

        assertArrayEquals(
            floatArrayOf(8f, 6.5f, 5f, 3f, 3f, 3f, 3f, 3f),
            result,
            0.001f
        )
        assertTrue(result.all { it in -12f..12f })
    }

    @Test
    fun mapsRemoteGenresOnlyToSupportedProfiles() {
        assertEquals(
            "Hip-Hop",
            EqProfileManager.supportedProfileForGenres(listOf("hip hop", "rap"))
        )
        assertEquals(
            "Rock",
            EqProfileManager.supportedProfileForGenres(listOf("alternative"))
        )
        assertEquals(
            null,
            EqProfileManager.supportedProfileForGenres(listOf("unknown category"))
        )
    }

    @Test
    fun selectsMatchingItunesTrackInsteadOfFirstResult() {
        val genre = EqProfileManager.selectItunesGenre(
            results = listOf(
                ItunesTrack("song", "Hello", "Someone Else", "Rock"),
                ItunesTrack("song", "Hello", "Adele", "Pop")
            ),
            trackName = "Hello",
            artistName = "Adele"
        )

        assertEquals("Pop", genre)
    }
}
