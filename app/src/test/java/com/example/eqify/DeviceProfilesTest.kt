package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {
    private val global = GlobalSound(autoGenre = true, bassDb = 3f, genreStrength = 0.8f)
    private val bt = AudioOutputKind.BLUETOOTH

    // ── Precedence: device setting, then default ─────────────────────

    @Test fun noProfileUsesDefaults() {
        val e = DeviceProfiles.resolve(global, null)
        assertTrue(e.autoGenre); assertNull(e.devicePreset)
        assertEquals(3f, e.bassDb, 0f); assertEquals(0.8f, e.genreStrength, 1e-6f)
        assertFalse(e.soundFromDevice || e.bassFromDevice || e.strengthFromDevice)
    }

    @Test fun emptyProfileInheritsEverything() {
        assertEquals(DeviceProfiles.resolve(global, null), DeviceProfiles.resolve(global, DeviceProfile(kind = bt)))
    }

    @Test fun devicePresetTurnsOffGenreAndWins() {
        val e = DeviceProfiles.resolve(global, DeviceProfile(kind = bt, sound = DeviceSound.Preset("Jazz")))
        assertFalse(e.autoGenre); assertEquals("Jazz", e.devicePreset); assertTrue(e.soundFromDevice)
        assertEquals(3f, e.bassDb, 0f) // bass still inherited
    }

    @Test fun followGenreTurnsGenreOnEvenWhenOffByDefault() {
        val e = DeviceProfiles.resolve(global.copy(autoGenre = false), DeviceProfile(kind = bt, sound = DeviceSound.FollowGenre))
        assertTrue(e.autoGenre); assertNull(e.devicePreset)
    }

    @Test fun ownBassAndStrengthOverrideTheDefault() {
        val e = DeviceProfiles.resolve(global, DeviceProfile(kind = bt, bassDb = 0f, genreStrength = 1.2f))
        assertEquals(0f, e.bassDb, 0f); assertTrue(e.bassFromDevice)
        assertEquals(1.2f, e.genreStrength, 1e-6f); assertTrue(e.strengthFromDevice)
        assertTrue(e.autoGenre); assertFalse(e.soundFromDevice)
    }

    @Test fun odd_values_are_normalised() {
        val e = DeviceProfiles.resolve(global.copy(bassDb = 4f), DeviceProfile(kind = bt, genreStrength = 7f))
        assertEquals(3f, e.bassDb, 0f)            // nearest bass step
        assertEquals(1.25f, e.genreStrength, 0f)  // clamped
    }

    // ── Storage ───────────────────────────────────────────────────────

    @Test fun roundTripsAllFields() {
        val map = mapOf(
            "bluetooth:sony wh 1000xm5" to DeviceProfile("Sony, \"main\" | cans", bt, 123L,
                DeviceSound.Preset("My=Rock|2"), 6f, 0.55f),
            "speaker" to DeviceProfile(null, AudioOutputKind.SPEAKER, 0L, DeviceSound.FollowGenre, null, null),
            "wired" to DeviceProfile("Wired headphones", AudioOutputKind.WIRED)
        )
        assertEquals(map, DeviceProfiles.decode(DeviceProfiles.encode(map)))
    }

    @Test fun malformedStorageIsSkipped() {
        assertTrue(DeviceProfiles.decode(null).isEmpty())
        assertTrue(DeviceProfiles.decode("nonsense").isEmpty())
        val good = DeviceProfiles.encode(mapOf("wired" to DeviceProfile(kind = AudioOutputKind.WIRED)))
        val mixed = good + "|bad|x=1,2|y=a,ALIEN,0,,,|=a,WIRED,0,,,"
        assertEquals(setOf("wired"), DeviceProfiles.decode(mixed).keys)
    }

    // ── Preset follow-through and summary ─────────────────────────────

    @Test fun presetRenameAndDeleteFollowThrough() {
        val map = mapOf("a" to DeviceProfile(kind = bt, sound = DeviceSound.Preset("Old"), bassDb = 3f),
            "b" to DeviceProfile(kind = bt, sound = DeviceSound.Preset("Other")))
        val renamed = DeviceProfiles.renamePreset(map, "Old", "New")
        assertEquals(DeviceSound.Preset("New"), renamed["a"]!!.sound)
        assertEquals(DeviceSound.Preset("Other"), renamed["b"]!!.sound)
        val removed = DeviceProfiles.removePreset(renamed, "New")
        assertNull(removed["a"]!!.sound); assertEquals(3f, removed["a"]!!.bassDb)
    }

    @Test fun summaries() {
        assertEquals("Same as default", DeviceProfiles.summary(null, null))
        assertEquals("Rock preset, bass +3, no correction", DeviceProfiles.summary(
            DeviceProfile(kind = bt, sound = DeviceSound.Preset("Rock"), bassDb = 3f), DeviceChoice.NotHeadphones))
        assertEquals("Follows genre, bass off, strength 60%", DeviceProfiles.summary(
            DeviceProfile(kind = bt, sound = DeviceSound.FollowGenre, bassDb = 0f, genreStrength = 0.6f), null))
    }

    @Test fun defaultLabels() {
        assertEquals("Phone speaker", DeviceProfiles.defaultLabel("speaker", AudioOutputKind.SPEAKER))
        assertEquals("Bluetooth device", DeviceProfiles.defaultLabel("bluetooth", bt))
        assertEquals("Galaxy Buds2 Pro", DeviceProfiles.defaultLabel("bluetooth:galaxy buds2 pro", bt))
    }
}
