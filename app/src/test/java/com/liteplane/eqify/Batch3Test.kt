package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VolumeCapTest {
    @Test fun clampsOnlyAboveTheCap() {
        assertEquals(10, VolumeCap.capIndex(15, 0.7f))          // 15 × 0.7 = 10.5 → 10
        assertEquals(10, VolumeCap.clampIndex(12, 15, 0.7f))
        assertNull(VolumeCap.clampIndex(10, 15, 0.7f))
        assertNull(VolumeCap.clampIndex(3, 15, 0.7f))
    }

    @Test fun normalisesLevel() {
        assertEquals(0.5f, VolumeCap.normalise(0.1f), 1e-6f)
        assertEquals(0.9f, VolumeCap.normalise(2f), 1e-6f)
        assertEquals(0.8f, VolumeCap.normalise(0.78f), 1e-6f)
        assertEquals(VolumeCap.DEFAULT, VolumeCap.normalise(null), 0f)
        assertEquals(VolumeCap.DEFAULT, VolumeCap.normalise(Float.NaN), 0f)
    }

    @Test fun neverZeroAndNoRange() {
        assertEquals(1, VolumeCap.capIndex(1, 0.5f))
        assertNull(VolumeCap.clampIndex(5, 0, 0.5f))
    }

    @Test fun limitForRestore() {
        assertEquals(10, VolumeCap.limit(14, 15, enabled = true, capFraction = 0.7f))
        assertEquals(14, VolumeCap.limit(14, 15, enabled = false, capFraction = 0.7f))
        assertEquals(6, VolumeCap.limit(6, 15, enabled = true, capFraction = 0.7f))
    }
}

class LoudnessCompensationTest {
    @Test fun zeroAtAndAboveHalfVolume() {
        for (f in listOf(0.5f, 0.6f, 0.9f, 1f)) assertEquals(LoudnessCompensation.ZERO, LoudnessCompensation.curve(f, true))
        assertEquals(LoudnessCompensation.ZERO, LoudnessCompensation.curve(0.1f, enabled = false))
    }

    @Test fun risesAsVolumeFallsAndStaysWithinCaps() {
        var last = 0f
        for (f in listOf(0.45f, 0.35f, 0.25f, 0.15f, 0.1f, 0f)) {
            val c = LoudnessCompensation.curve(f, true)
            assertTrue(c[0] >= last)
            last = c[0]
            assertTrue(c.all { it in 0f..LoudnessCompensation.MAX_BASS_DB })
            assertTrue(c[7] <= LoudnessCompensation.MAX_TREBLE_DB)
            assertTrue(c.all { abs(it * 2 - Math.round(it * 2)) < 1e-4 })   // 0.5 dB steps
        }
        assertEquals(LoudnessCompensation.MAX_BASS_DB, LoudnessCompensation.curve(0.1f, true)[0], 0f)
        assertEquals(0f, LoudnessCompensation.curve(0.1f, true)[4], 0f)       // mids untouched
    }

    /** Plan §1: loudness and volume-aware bass must never act at the same volume. */
    @Test fun neverOverlapsVolumeAwareBass() {
        assertTrue(LoudnessCompensation.START <= VolumeBass.THRESHOLD)
        for (i in 0..100) {
            val f = i / 100f
            val loud = LoudnessCompensation.amount(f) > 0f
            val eased = VolumeBass.apply(9f, f, enabled = true) < 9f
            assertTrue("both active at $f", !(loud && eased))
        }
    }

    @Test fun addClamps() {
        val out = LoudnessCompensation.add(FloatArray(8) { 10f }, LoudnessCompensation.curve(0f, true))
        assertTrue(out.all { it <= 12f })
    }
}

class VolumeFeatureFreezeTest {
    /** Plan §1: during the sleep fade, falling volume must not add loudness bass. */
    @Test fun frozenFeaturesIgnoreVolumeChanges() {
        EqState.setBassBoost(0f)
        EqState.setLoudnessCompensation(true)
        EqState.setMediaVolumeFraction(0.6f)
        assertEquals(LoudnessCompensation.ZERO, EqState.appliedLoudness.value)
        EqState.setVolumeFeaturesFrozen(true)
        EqState.setMediaVolumeFraction(0.05f)                  // the fade lowers the volume
        assertEquals(LoudnessCompensation.ZERO, EqState.appliedLoudness.value)
        EqState.setVolumeFeaturesFrozen(false)                 // fade over: follows the volume again
        assertTrue(LoudnessCompensation.peak(EqState.appliedLoudness.value) > 0f)
        EqState.setLoudnessCompensation(false)
        EqState.setMediaVolumeFraction(0f)
    }

    @Test fun bassChangesStillApplyWhileFrozen() {
        EqState.setVolumeAwareBass(true)
        EqState.setMediaVolumeFraction(1f)
        EqState.setVolumeFeaturesFrozen(true)
        EqState.setBassBoost(6f)
        assertEquals(3f, EqState.appliedBassBoost.value, 0f)   // eased with the held volume
        EqState.setVolumeFeaturesFrozen(false)
        EqState.setVolumeAwareBass(false)
        EqState.setBassBoost(0f)
    }
}

class SleepTimerTest {
    @Test fun fadeStepsDecreaseToZero() {
        val steps = SleepTimer.fadeSteps(10, 30_000L)
        assertEquals(10, steps.size)
        assertEquals((9 downTo 0).toList(), steps.map { it.second })
        assertEquals(3_000L, steps.first().first)
        assertEquals(listOf(0L to 0), SleepTimer.fadeSteps(0, 30_000L))
        assertTrue(SleepTimer.fadeSteps(1_000, 3_000L).all { it.first >= 50L })
    }

    @Test fun minutesLeftRoundsUp() {
        assertEquals(12, SleepTimer.minutesLeft(12 * 60_000L, 0L))
        assertEquals(12, SleepTimer.minutesLeft(11 * 60_000L + 1, 0L))
        assertEquals(0, SleepTimer.minutesLeft(0L, 5L))
    }

    @Test fun labelsAndRequests() {
        assertNull(SleepTimer.label(SleepTimer.State.Off, 0L))
        assertEquals("Sleep in 30 min", SleepTimer.label(SleepTimer.State.Counting(30 * 60_000L, 30), 0L))
        assertTrue(SleepTimer.isValidRequest(0) && SleepTimer.isValidRequest(SleepTimer.END_OF_TRACK) &&
            SleepTimer.isValidRequest(45))
        assertTrue(!SleepTimer.isValidRequest(-5) && !SleepTimer.isValidRequest(999))
    }
}

class AutomationCommandTest {
    private fun parse(action: String, vararg extras: Pair<String, Any?>) =
        AutomationCommand.parse(action, mapOf(*extras))

    @Test fun acceptsAllowlistedCommands() {
        assertEquals(AutomationCommand.SetEq(false), parse(AutomationCommand.SET_EQ, "enabled" to false))
        assertEquals(AutomationCommand.SetEq(true), parse(AutomationCommand.SET_EQ, "enabled" to "true"))
        assertEquals(AutomationCommand.SetBass(6f), parse(AutomationCommand.SET_BASS, "level" to "6"))
        assertEquals(AutomationCommand.SetBass(3f), parse(AutomationCommand.SET_BASS, "level" to 4))
        assertEquals(AutomationCommand.SetPreset("Rock"), parse(AutomationCommand.SET_PRESET, "name" to " Rock "))
        assertEquals(AutomationCommand.SetAutoGenre(true), parse(AutomationCommand.SET_AUTO_GENRE, "enabled" to 1))
        assertEquals(AutomationCommand.Sleep(30), parse(AutomationCommand.SLEEP_TIMER, "minutes" to 30L))
        assertEquals(AutomationCommand.Sleep(0), parse(AutomationCommand.SLEEP_TIMER, "minutes" to "0"))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(parse("com.liteplane.eqify.action.DELETE_ALL"))
        assertNull(parse("android.intent.action.MAIN"))
        assertNull(parse(AutomationCommand.SET_EQ))                                  // missing extra
        assertNull(parse(AutomationCommand.SET_EQ, "enabled" to "maybe"))
        assertNull(parse(AutomationCommand.SET_BASS, "level" to 40))
        assertNull(parse(AutomationCommand.SET_BASS, "level" to Double.NaN))
        assertNull(parse(AutomationCommand.SET_BASS, "level" to "loud"))
        assertNull(parse(AutomationCommand.SET_PRESET, "name" to ""))
        assertNull(parse(AutomationCommand.SET_PRESET, "name" to "x".repeat(101)))
        assertNull(parse(AutomationCommand.SET_PRESET, "name" to 5))
        assertNull(parse(AutomationCommand.SLEEP_TIMER, "minutes" to 9_999))
        assertNull(AutomationCommand.parse(null, emptyMap()))
    }
}

class ToneGeneratorTest {
    @Test fun channelTestsPlayOnOneSideOnly() {
        val left = ToneGenerator.generate(TestTone.LEFT)
        val right = ToneGenerator.generate(TestTone.RIGHT)
        assertTrue((left.indices step 2).any { left[it] != 0.toShort() })
        assertTrue((1 until left.size step 2).all { left[it] == 0.toShort() })
        assertTrue((right.indices step 2).all { right[it] == 0.toShort() })
        assertTrue((1 until right.size step 2).any { right[it] != 0.toShort() })
    }

    @Test fun soundsFadeInAndOutAndStayQuiet() {
        for (tone in TestTone.entries) {
            val s = ToneGenerator.generate(tone)
            assertEquals(0, s[0].toInt())
            assertEquals(0, s[1].toInt())
            assertTrue(abs(s[s.size - 1].toInt()) < 200 && abs(s[s.size - 2].toInt()) < 200)
            assertTrue(s.all { abs(it.toInt()) <= Short.MAX_VALUE * 0.19 })
        }
    }

    @Test fun sweepCoversTheRange() {
        assertEquals(20.0, ToneGenerator.sweepHz(0.0), 1e-6)
        assertEquals(20_000.0, ToneGenerator.sweepHz(ToneGenerator.SWEEP_SECONDS), 1e-3)
        assertTrue(ToneGenerator.sweepHz(5.0) in 600.0..700.0)                // geometric middle ≈ 632 Hz
        assertEquals(ToneGenerator.SAMPLE_RATE * 10 * 2, ToneGenerator.generate(TestTone.SWEEP).size)
    }
}

class WidgetTextTest {
    private fun snap(enabled: Boolean, status: EqProcessingStatus, profile: String = "Rock") =
        EqProcessingSnapshot(enabled, status, profile)

    @Test fun activeShowsProfileAndPause() {
        val t = WidgetText.from(snap(true, EqProcessingStatus.ACTIVE), true, 6f, null)
        assertEquals("Rock active", t.status)
        assertEquals("Pause", t.toggleLabel)
        assertEquals("Bass +6 dB", t.bassLabel)
        assertEquals(false, t.nextEnabled)
    }

    @Test fun pausedOrStoppedOffersResume() {
        val paused = WidgetText.from(snap(false, EqProcessingStatus.ACTIVE), true, 0f, null)
        assertEquals("EQ paused", paused.status); assertEquals("Resume", paused.toggleLabel)
        assertEquals(true, paused.nextEnabled); assertEquals("Bass Off", paused.bassLabel)
        val stopped = WidgetText.from(snap(true, EqProcessingStatus.ACTIVE), false, 0f, null)
        assertEquals("Resume", stopped.toggleLabel); assertEquals(true, stopped.nextEnabled)
    }

    @Test fun sleepLabelIsAppended() {
        val t = WidgetText.from(snap(true, EqProcessingStatus.ACTIVE), true, 0f, "Sleep in 5 min")
        assertEquals("Rock active · Sleep in 5 min", t.status)
    }
}

class BackupBatch3Test {
    @Test fun newSettingsRoundTrip() {
        val data = BackupData(settings = mapOf(
            "loudness_compensation" to true, "volume_cap_enabled" to true, "volume_cap_level" to 0.6f))
        val r = BackupCodec.parse(BackupCodec.build(data, "0.4.0-beta", 1L)) as BackupCodec.Result.Ok
        assertEquals(data.settings, r.data.settings)
    }

    @Test fun capLevelIsNormalisedAndAutomationNeverRestored() {
        val text = "{\"format\":\"eqify-backup\",\"version\":1,\"settings\":{\"volume_cap_level\":3," +
            "\"automation_enabled\":true}}"
        val s = (BackupCodec.parse(text) as BackupCodec.Result.Ok).data.settings
        assertEquals(VolumeCap.MAX, s["volume_cap_level"])
        assertTrue("automation_enabled" !in s)
    }
}
