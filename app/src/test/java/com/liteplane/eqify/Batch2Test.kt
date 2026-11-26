package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBassTest {
    @Test fun noChangeUpToThreshold() {
        assertEquals(1f, VolumeBass.scale(0f), 0f)
        assertEquals(1f, VolumeBass.scale(0.6f), 0f)
        assertEquals(9f, VolumeBass.apply(9f, 0.5f, enabled = true), 0f)
    }

    @Test fun halfAtFullVolume() {
        assertEquals(0.5f, VolumeBass.scale(1f), 1e-6f)
        assertEquals(4.5f, VolumeBass.apply(9f, 1f, enabled = true), 0f)
    }

    @Test fun linearInBetweenAndRoundedToHalfDb() {
        assertEquals(0.75f, VolumeBass.scale(0.8f), 1e-6f)
        assertEquals(4.5f, VolumeBass.apply(6f, 0.8f, enabled = true), 0f)   // 6 × 0.75
        val v = VolumeBass.apply(9f, 0.9f, enabled = true)                     // 9 × 0.625 = 5.625
        assertEquals(5.5f, v, 0f)
    }

    @Test fun offOrNoBassMeansUnchanged() {
        assertEquals(9f, VolumeBass.apply(9f, 1f, enabled = false), 0f)
        assertEquals(0f, VolumeBass.apply(0f, 1f, enabled = true), 0f)
        assertEquals(0f, VolumeBass.apply(Float.NaN, 1f, enabled = true), 0f)
    }

    @Test fun oddInputs() {
        assertEquals(1f, VolumeBass.scale(Float.NaN), 0f)
        assertEquals(0.5f, VolumeBass.scale(3f), 1e-6f)
        assertEquals(0f, VolumeBass.fraction(5, 0), 0f)
        assertEquals(1f, VolumeBass.fraction(20, 15), 0f)
        assertEquals(0.5f, VolumeBass.fraction(5, 10), 0f)
    }
}

class NotificationControlsTest {
    @Test fun cyclesThroughHomeSteps() {
        assertEquals(3f, NotificationControls.nextBass(0f), 0f)
        assertEquals(6f, NotificationControls.nextBass(3f), 0f)
        assertEquals(9f, NotificationControls.nextBass(6f), 0f)
        assertEquals(0f, NotificationControls.nextBass(9f), 0f)
    }

    @Test fun offStepValuesSnapFirst() {
        assertEquals(6f, NotificationControls.nextBass(4f), 0f)   // 4 → nearest 3 → next 6
        assertEquals(0f, NotificationControls.nextBass(12f), 0f)  // 12 → 9 → wraps
    }

    @Test fun labels() {
        assertEquals("Bass: Off", NotificationControls.bassLabel(0f))
        assertEquals("Bass: +6 dB", NotificationControls.bassLabel(6f))
    }
}

class BackupCodecTest {
    private val rock = EqProfileManager.defaultProfiles.keys.first { GenreCurveOverrides.isCustomisable(it) }
    private val curve = listOf(1f, 2f, 3f, 0f, -1f, -2f, 0.5f, 4f)
    private val sample = BackupData(
        settings = mapOf("bass_boost_level" to 6f, "auto_genre_detection" to false,
            "output_protection_mode" to "SAFE", "genre_strength" to 0.75f),
        customPresets = listOf("Night" to curve, "Gym" to curve.map { -it }),
        genreOverrides = mapOf(rock to curve),
        deviceProfiles = mapOf("bluetooth:buds" to DeviceProfile("Buds", AudioOutputKind.BLUETOOTH, 10L,
            DeviceSound.Preset("Night"), 3f, null)),
        deviceChoices = mapOf("bluetooth:buds" to DeviceChoice.NotHeadphones),
        favoriteHeadphones = listOf("Sony WH-1000XM5"),
        importedCorrections = listOf(CustomEqProfile("id-1", "My curve", "my.txt", 5L, curve))
    )

    private fun ok(text: String) = BackupCodec.parse(text) as BackupCodec.Result.Ok

    @Test fun roundTrip() {
        val r = ok(BackupCodec.build(sample, "0.3.0-beta", 123L))
        assertEquals(sample, r.data)
        assertEquals("0.3.0-beta", r.appVersion)
        assertEquals(123L, r.createdAt)
    }

    @Test fun unknownKeysAndGenresIgnored() {
        val text = BackupCodec.build(sample, "x", 1L)
            .replace("\"settings\":{", "\"futureThing\":[1,2],\"settings\":{\"lastfm_api_key\":\"secret\",\"new_setting\":true,")
            .replace("\"genreCurves\":{", "\"genreCurves\":{\"Hyperpop\":[0,0,0,0,0,0,0,0],")
        val r = ok(text)
        assertEquals(sample.settings, r.data.settings)
        assertEquals(sample.genreOverrides, r.data.genreOverrides)
    }

    @Test fun badGainsRejectNothingApplies() {
        val text = BackupCodec.build(sample.copy(customPresets = listOf("Loud" to curve.map { it + 20f })), "x", 1L)
        val r = BackupCodec.parse(text)
        assertTrue(r is BackupCodec.Result.Error)
    }

    @Test fun wrongBandCountRejected() {
        val text = BackupCodec.build(sample.copy(customPresets = listOf("Short" to curve.take(5))), "x", 1L)
        assertTrue(BackupCodec.parse(text) is BackupCodec.Result.Error)
    }

    @Test fun truncatedOrForeignFiles() {
        val full = BackupCodec.build(sample, "x", 1L)
        assertTrue(BackupCodec.parse(full.take(full.length / 2)) is BackupCodec.Result.Error)
        assertTrue(BackupCodec.parse("{\"format\":\"other\",\"version\":1}") is BackupCodec.Result.Error)
        assertTrue(BackupCodec.parse("not json") is BackupCodec.Result.Error)
        assertTrue(BackupCodec.parse("[1,2]") is BackupCodec.Result.Error)
    }

    @Test fun newerVersionAsksForUpdate() {
        val r = BackupCodec.parse("{\"format\":\"eqify-backup\",\"version\":2}") as BackupCodec.Result.Error
        assertTrue(r.message.contains("newer"))
    }

    @Test fun settingsAreNormalised() {
        val text = "{\"format\":\"eqify-backup\",\"version\":1,\"settings\":{\"bass_boost_level\":7.2," +
            "\"output_protection_mode\":\"LOUD\",\"headphone_correction_source\":\"hack\",\"genre_strength\":9}}"
        val s = ok(text).data.settings
        assertEquals(6f, s["bass_boost_level"])
        assertEquals(GenreStrength.normalise(9f), s["genre_strength"])
        assertTrue("output_protection_mode" !in s && "headphone_correction_source" !in s)
    }

    @Test fun wrongSettingTypeRejected() {
        val text = "{\"format\":\"eqify-backup\",\"version\":1,\"settings\":{\"force_mono\":\"yes\"}}"
        assertTrue(BackupCodec.parse(text) is BackupCodec.Result.Error)
    }

    @Test fun reservedNamesRenamedDuplicatesRejected() {
        // A name that became a built-in is kept under a new name (FEATURES_BATCH4_PLAN.md §5).
        val builtIn = EqProfileManager.defaultProfiles.keys.first()
        val r = BackupCodec.parse(BackupCodec.build(BackupData(customPresets = listOf(builtIn to curve)), "x", 1L))
            as BackupCodec.Result.Ok
        assertEquals("$builtIn (Custom)", r.data.customPresets.single().first)
        assertTrue(BackupCodec.parse(BackupCodec.build(BackupData(customPresets = listOf("A" to curve, "a" to curve)), "x", 1L))
            is BackupCodec.Result.Error)
    }

    @Test fun mergeKeepsCurrentAndAddsMissing() {
        val current = BackupData(
            settings = mapOf("bass_boost_level" to 0f),
            customPresets = listOf("Night" to curve),
            deviceProfiles = mapOf("wired" to DeviceProfile(null, AudioOutputKind.WIRED, 1L, null, null, null)),
            importedCorrections = listOf(CustomEqProfile("id-1", "Mine", "m.txt", 1L, curve))
        )
        val m = BackupCodec.merge(current, sample)
        assertEquals(current.settings, m.settings)
        // Same name and gains: kept once. Different preset: added.
        assertEquals(listOf("Night", "Gym"), m.customPresets.map { it.first })
        assertEquals(setOf("wired", "bluetooth:buds"), m.deviceProfiles.keys)
        assertEquals(listOf("Mine"), m.importedCorrections.map { it.displayName })
        assertEquals(sample.genreOverrides, m.genreOverrides)
    }

    @Test fun mergeNameClashGetsSuffix() {
        val current = BackupData(customPresets = listOf("Night" to curve.map { 0f }))
        val m = BackupCodec.merge(current, BackupData(customPresets = listOf("Night" to curve)))
        assertEquals(listOf("Night", "Night (2)"), m.customPresets.map { it.first })
    }

    @Test fun summaryAndEmpty() {
        assertEquals("2 presets, 1 genre curve, 1 device, 1 imported correction, settings", sample.summary())
        assertTrue(BackupData().isEmpty)
        assertEquals("nothing", BackupData().summary())
    }
}
