package com.example.eqify

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EqGainRampTest {
    @Test fun interpolationBoundsAndMidpoint() {
        val from = intArrayOf(-600, 100)
        val to = intArrayOf(600, -100)
        assertArrayEquals(from, EqGainRamp.interpolate(from, to, 0, 250))
        assertArrayEquals(intArrayOf(0, 0), EqGainRamp.interpolate(from, to, 125, 250))
        assertArrayEquals(to, EqGainRamp.interpolate(from, to, 250, 250))
        assertArrayEquals(to, EqGainRamp.interpolate(from, to, 1, 0))
        for (elapsed in 0L..250L) {
            val levels = EqGainRamp.interpolate(from, to, elapsed, 250)
            assertTrue(levels[0] in -600..600 && levels[1] in -100..100)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentBandCountsFail() {
        EqGainRamp.interpolate(intArrayOf(0), intArrayOf(0, 0), 50, 250)
    }

    @Test fun retargetUsesLastAppliedStepAndManualCancelStopsWrites() = runTest {
        val writes = mutableListOf<IntArray>()
        val controller = EqGainRampController(this, { testScheduler.currentTime }, { true }) { _, levels ->
            writes += levels.copyOf()
            true
        }
        controller.start(PreparedGainRamp(1, intArrayOf(0), intArrayOf(1000)))
        advanceTimeBy(100)
        runCurrent()
        val applied = writes.last().copyOf()
        assertTrue(applied[0] in 1..999)

        controller.start(PreparedGainRamp(1, applied, intArrayOf(-500)))
        advanceTimeBy(20)
        runCurrent()
        assertTrue(writes.last()[0] < applied[0])
        val countBeforeManual = writes.size
        controller.cancel()
        advanceUntilIdle()
        assertEquals(countBeforeManual, writes.size)
    }

    @Test fun staleAttachmentStopsRampAndFinalTargetIsExact() = runTest {
        val writes = mutableListOf<IntArray>()
        var activeEpoch = 7L
        val controller = EqGainRampController(this, { testScheduler.currentTime }, { true }) { epoch, levels ->
            if (epoch != activeEpoch) false else {
                writes += levels.copyOf()
                true
            }
        }
        controller.start(PreparedGainRamp(7, intArrayOf(0, 0), intArrayOf(101, -99)))
        advanceTimeBy(20)
        runCurrent()
        activeEpoch = 8
        advanceUntilIdle()
        assertEquals(1, writes.size)

        controller.start(PreparedGainRamp(8, intArrayOf(0, 0), intArrayOf(101, -99)))
        advanceUntilIdle()
        assertArrayEquals(intArrayOf(101, -99), writes.last())
        assertFalse(writes.last().contentEquals(intArrayOf(0, 0)))
    }
}
