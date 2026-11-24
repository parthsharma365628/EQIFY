package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitSweepTest {
    @Test
    fun sweepAlternatesSidesAndReturnsToCenterAfterThirtySeconds() {
        assertGains(0L, 0f, 0f)
        assertGains(7_500L, -6f, 0f)
        assertGains(15_000L, 0f, 0f)
        assertGains(22_500L, 0f, -6f)
        assertGains(30_000L, 0f, 0f)
        assertGains(37_500L, -6f, 0f)
    }

    @Test
    fun sweepNeverBoostsEitherChannel() {
        for (elapsed in 0L..OrbitSweep.PERIOD_MS step 25L) {
            val gains = OrbitSweep.gainsAt(elapsed)
            assertTrue(gains.leftDb in -6f..0f)
            assertTrue(gains.rightDb in -6f..0f)
        }
    }

    private fun assertGains(elapsed: Long, leftDb: Float, rightDb: Float) {
        val gains = OrbitSweep.gainsAt(elapsed)
        assertEquals(leftDb, gains.leftDb, 0.001f)
        assertEquals(rightDb, gains.rightDb, 0.001f)
    }
}
