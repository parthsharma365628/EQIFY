package com.example.eqify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateAndPersistenceTest {

    @Test
    fun manualOverrideEndsWhenTrackChanges() {
        val gains = FloatArray(8) { 2f }
        EqState.restorePreset("Flat", FloatArray(8))
        EqState.applyManualAdjustment(gains)

        assertTrue(EqState.manualOverrideActive.value)
        assertArrayEquals(gains, EqState.baseToneGains.value, 0f)

        NowPlayingState.updateSong("A new track", "An artist")

        assertFalse(EqState.manualOverrideActive.value)
    }

    @Test
    fun customPresetNameCodecRoundTripsAndReadsLegacyData() {
        val names = listOf("Late Night, Quiet", "Gym | Cardio")
        assertEquals(names, CustomPresetNameCodec.decode(CustomPresetNameCodec.encode(names)))
        assertEquals(listOf("Legacy One", "Legacy Two"), CustomPresetNameCodec.decode("Legacy One,Legacy Two"))
    }

    @Test
    fun customPresetMigrationRenamesReservedAndDuplicateNames() {
        val migrated = CustomPresetNameCodec.migrateReservedNames(
            names = listOf("Afrobeats", "Road Trip", "road trip"),
            reservedNames = listOf("Flat", "Afrobeats")
        )

        assertEquals(
            listOf(
                "Afrobeats" to "Afrobeats (Custom)",
                "Road Trip" to "Road Trip",
                "road trip" to "road trip (Custom)"
            ),
            migrated
        )
    }
}
