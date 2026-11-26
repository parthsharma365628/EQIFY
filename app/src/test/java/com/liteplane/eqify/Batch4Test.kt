package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class LevelMatchTest {
    @Test fun lowersByMeanBoostOnly() {
        assertEquals(2f, LevelMatch.offset(floatArrayOf(4f, 4f, 2f, 2f, 0f, 0f, 2f, 2f)), 0f)
        assertEquals(0f, LevelMatch.offset(floatArrayOf(-3f, -3f, 0f, 0f, 0f, 0f, 0f, 0f)), 0f)
        assertEquals(LevelMatch.MAX_OFFSET_DB, LevelMatch.offset(FloatArray(8) { 12f }), 0f)
        val out = LevelMatch.apply(floatArrayOf(4f, 0f), 2f)
        assertEquals(listOf(2f, -2f), out.toList())
        assertTrue(LevelMatch.apply(floatArrayOf(1f), 0f).contentEquals(floatArrayOf(1f)))
    }
}

class StereoBalanceTest {
    @Test fun onlyTheFarSideIsLowered() {
        assertEquals(0f to 0f, StereoBalance.channelGains(0f))
        assertEquals(-12f to 0f, StereoBalance.channelGains(100f))       // right: left side down
        assertEquals(0f to -12f, StereoBalance.channelGains(-100f))
        assertEquals(-6f to 0f, StereoBalance.channelGains(50f))
    }

    @Test fun normaliseAndLabel() {
        assertEquals(0f, StereoBalance.normalise(Float.NaN), 0f)
        assertEquals(100f, StereoBalance.normalise(400f), 0f)
        assertEquals(25f, StereoBalance.normalise(24f), 0f)
        assertEquals("Centre", StereoBalance.label(0f))
        assertEquals("30% left", StereoBalance.label(-30f))
        assertEquals("15% right", StereoBalance.label(15f))
    }
}

class PresetFavoritesTest {
    @Test fun toggleMoveRenameRemove() {
        var l = PresetFavorites.toggle(emptyList(), "Rock")
        l = PresetFavorites.toggle(l, "Jazz")
        l = PresetFavorites.toggle(l, "Night")
        assertEquals(listOf("Rock", "Jazz", "Night"), l)
        assertEquals(listOf("Rock", "Night", "Jazz"), PresetFavorites.move(l, "Night", up = true))
        assertEquals(l, PresetFavorites.move(l, "Rock", up = true))       // already first
        assertEquals(listOf("Rock", "Jazz", "Late"), PresetFavorites.rename(l, "night", "Late"))
        assertEquals(listOf("Rock", "Night"), PresetFavorites.remove(l, "JAZZ"))
        assertEquals(listOf("Jazz", "Night"), PresetFavorites.toggle(l, "rock"))
    }

    @Test fun presentKeepsFavouriteOrderAndDropsMissing() {
        assertEquals(listOf("Jazz", "Rock"), PresetFavorites.present(listOf("jazz", "Gone", "Rock"), listOf("Rock", "Jazz")))
    }
}

class ChangeLogTest {
    @Test fun newestFirstNoRepeatsCapped() {
        ChangeLog.clear()
        ChangeLog.add("A", 1); ChangeLog.add("A", 2); ChangeLog.add("B", 3)
        assertEquals(listOf("B", "A"), ChangeLog.entries.value.map { it.text })
        repeat(40) { ChangeLog.add("x$it", it.toLong()) }
        assertEquals(ChangeLog.CAPACITY, ChangeLog.entries.value.size)
        assertEquals("x39", ChangeLog.entries.value.first().text)
        ChangeLog.clear()
    }
}

class DeviceVolumesTest {
    @Test fun roundTripAndIndex() {
        val m = mapOf("bluetooth:buds" to 0.4f, "wired" to 1f)
        assertEquals(m, DeviceVolumes.decode(DeviceVolumes.encode(m)))
        assertEquals(emptyMap<String, Float>(), DeviceVolumes.decode("garbage"))
        assertEquals(6, DeviceVolumes.toIndex(0.4f, 15))
        assertEquals(0, DeviceVolumes.toIndex(0.5f, 0))
    }
}

class SafeListeningTest {
    @Test fun volumeCurveFollowsAndroidDefaults() {
        assertEquals(0f, SafeListening.volumeAttenuationDb(1f), 1e-4f)
        assertEquals(-17f, SafeListening.volumeAttenuationDb(0.6f), 1e-3f)
        assertEquals(-40f, SafeListening.volumeAttenuationDb(0.2f), 1e-3f)
        assertEquals(-96f, SafeListening.volumeAttenuationDb(0f), 0f)
        assertTrue(SafeListening.volumeAttenuationDb(0.4f) in -40f..-17f)
    }

    @Test fun whoAllowance() {
        val adult = SafeListening.Mode.ADULT
        // 80 dB for 40 hours = 100% (WHO-ITU H.870 mode 1).
        assertEquals(1.0, SafeListening.doseFor(80f, 40 * 3600.0, adult), 1e-9)
        // 3 dB louder halves the time.
        assertEquals(1.0, SafeListening.doseFor(83f, 20 * 3600.0, adult), 0.01)
        // Sensitive mode: 75 dB for 40 hours = 100%.
        assertEquals(1.0, SafeListening.doseFor(75f, 40 * 3600.0, SafeListening.Mode.SENSITIVE), 1e-9)
        assertEquals(0.0, SafeListening.doseFor(Float.NaN, 10.0, adult), 0.0)
    }

    @Test fun estimateIncludesEqBoostCapped() {
        assertEquals(100f, SafeListening.estimatedLevelDb(1f, FloatArray(8)), 1e-4f)
        assertEquals(103f, SafeListening.estimatedLevelDb(1f, FloatArray(8) { 3f }), 1e-4f)
        assertEquals(106f, SafeListening.estimatedLevelDb(1f, FloatArray(8) { 12f }), 1e-4f)
        assertEquals(100f, SafeListening.estimatedLevelDb(1f, FloatArray(8) { -5f }), 1e-4f)
    }

    @Test fun rollingSevenDays() {
        val today = LocalDate.of(2026, 11, 27)
        var days = emptyMap<LocalDate, Double>()
        days = SafeListening.add(days, today.minusDays(8), 0.5)
        days = SafeListening.add(days, today.minusDays(6), 0.3)
        days = SafeListening.add(days, today, 0.2)
        days = SafeListening.add(days, today, 0.1)
        assertEquals(0.6, SafeListening.weekly(days, today), 1e-9)
        assertEquals(2, days.size)
        fun r(m: Map<LocalDate, Double>) = m.mapValues { Math.round(it.value * 1e6) / 1e6 }
        assertEquals(r(days), r(SafeListening.decode(SafeListening.encode(days))))
    }

    @Test fun alertsOncePerHundredPercent() {
        assertNull(SafeListening.alertStep(0.95, 0))
        assertEquals(1, SafeListening.alertStep(1.02, 0))
        assertNull(SafeListening.alertStep(1.5, 1))
        assertEquals(2, SafeListening.alertStep(2.1, 1))
        assertEquals(87, SafeListening.percent(0.87))
    }
}

class ParametricEqImporterTest {
    private val autoEq = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 3000 Hz Gain -4.0 dB Q 2.00
        Filter 3: OFF PK Fc 500 Hz Gain 9.0 dB Q 1.00
        Filter 4: ON HSC Fc 10000 Hz Gain 3.0 dB Q 0.70
    """.trimIndent()

    @Test fun parsesAutoEqFile() {
        assertTrue(ParametricEqImporter.looksParametric(autoEq))
        val filters = ParametricEqImporter.parse(autoEq)
        assertEquals(3, filters.size)                              // OFF filter skipped, preamp ignored
        assertEquals(ParametricEqImporter.Type.LOW_SHELF, filters[0].type)
    }

    @Test fun biquadResponseIsCorrect() {
        val pk = ParametricEqImporter.Filter(ParametricEqImporter.Type.PEAK, 1000.0, 6.0, 1.0)
        assertEquals(6.0, ParametricEqImporter.responseDb(pk, 1000.0), 0.05)
        assertTrue(abs(ParametricEqImporter.responseDb(pk, 50.0)) < 0.2)
        val ls = ParametricEqImporter.Filter(ParametricEqImporter.Type.LOW_SHELF, 105.0, 5.5, 0.7)
        assertEquals(5.5, ParametricEqImporter.responseDb(ls, 20.0), 0.3)
        assertTrue(abs(ParametricEqImporter.responseDb(ls, 5000.0)) < 0.1)
    }

    @Test fun bandsFollowTheCurve() {
        val r = ParametricEqImporter.convert(autoEq)
        assertTrue(r.gains[0] > 3f)                                 // low shelf lifts 60 Hz
        assertTrue(r.gains[5] < -1f)                                // the 3 kHz dip shows
        assertTrue(r.gains[7] > 1.5f)                               // high shelf lifts 12 kHz
        assertTrue(r.gains.all { it in -12f..12f })
        assertEquals(false, r.limited)
    }

    @Test fun rejectsOtherFiles() {
        assertTrue(!ParametricEqImporter.looksParametric("GraphicEQ: 20 0; 40 1"))
        assertThrows { ParametricEqImporter.parse("Filter 1: ON XX Fc 100 Hz Gain 1 dB Q 1") }
        assertThrows { ParametricEqImporter.parse("hello") }
        assertThrows { ParametricEqImporter.parse("Filter 1: ON PK Fc 100000 Hz Gain 1 dB Q 1") }
    }

    @Test fun limitedFlagWhenOverTwelve() {
        val r = ParametricEqImporter.convert("Filter 1: ON LSC Fc 200 Hz Gain 20 dB Q 0.7")
        assertTrue(r.limited)
        assertEquals(12f, r.gains[0], 0f)
    }

    private fun assertThrows(block: () -> Unit) {
        try { block(); throw AssertionError("expected an exception") } catch (e: IllegalArgumentException) { }
    }
}

class SpectrumBarsTest {
    @Test fun silenceIsFlatAndToneShowsUp() {
        assertTrue(SpectrumBars.fromFft(ByteArray(1024), 44_100_000).all { it == 0f })
        val fft = ByteArray(1024)
        // A bin near 1 kHz: k = 1000 / 22050 * 511 ≈ 23.
        fft[46] = 100; fft[47] = 0
        val bars = SpectrumBars.fromFft(fft, 44_100_000)
        val peak = bars.indices.maxByOrNull { bars[it] }!!
        assertTrue(peak in 14..20)
        assertTrue(bars.all { it in 0f..1f })
    }

    @Test fun smoothFallsGradually() {
        val prev = FloatArray(SpectrumBars.BARS) { 1f }
        val next = SpectrumBars.smooth(prev, FloatArray(SpectrumBars.BARS))
        assertTrue(next.all { abs(it - 0.92f) < 1e-5 })
    }
}

class Batch4PresetsAndBackupTest {
    @Test fun dialogueIsAPresetNotAGenre() {
        assertTrue("Dialogue" in EqProfileManager.defaultProfiles)
        assertTrue("Dialogue" !in EqProfileManager.correctionGenres)
        assertTrue(PresetNameRules.isReserved("dialogue"))
    }

    @Test fun oldBackupWithADialoguePresetStillRestores() {
        val text = "{\"format\":\"eqify-backup\",\"version\":1,\"customPresets\":[{\"name\":\"Dialogue\",\"gains\":[0,0,0,0,0,0,0,0]}]}"
        val r = BackupCodec.parse(text) as BackupCodec.Result.Ok
        assertEquals("Dialogue (Custom)", r.data.customPresets.single().first)
    }

    @Test fun newSettingsAndFavouritesRoundTrip() {
        val data = BackupData(
            settings = mapOf("balance" to -20f, "haptics" to false, "safe_listening_enabled" to true,
                "safe_listening_mode" to "SENSITIVE", "device_volume_memory" to true),
            favoritePresets = listOf("Rock", "Night")
        )
        val r = BackupCodec.parse(BackupCodec.build(data, "0.5.0-beta", 1L)) as BackupCodec.Result.Ok
        assertEquals(data.settings, r.data.settings)
        assertEquals(data.favoritePresets, r.data.favoritePresets)
    }

    @Test fun badModeDropped() {
        val text = "{\"format\":\"eqify-backup\",\"version\":1,\"settings\":{\"safe_listening_mode\":\"LOUD\"}}"
        val s = (BackupCodec.parse(text) as BackupCodec.Result.Ok).data.settings
        assertTrue("safe_listening_mode" !in s)
    }
}
