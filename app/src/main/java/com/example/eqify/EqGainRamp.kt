package com.example.eqify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Device-band levels are millibels; this never alters EQ composition or protection. */
internal object EqGainRamp {
    const val DURATION_MS = 250L
    const val STEP_MS = 20L

    fun interpolate(from: IntArray, to: IntArray, elapsedMs: Long, durationMs: Long): IntArray {
        require(from.size == to.size) { "Band counts differ" }
        if (durationMs <= 0L || elapsedMs >= durationMs) return to.copyOf()
        if (elapsedMs <= 0L) return from.copyOf()
        val fraction = elapsedMs.toDouble() / durationMs
        return IntArray(from.size) { i ->
            (from[i] + (to[i] - from[i]) * fraction).roundToInt()
                .coerceIn(minOf(from[i], to[i]), maxOf(from[i], to[i]))
        }
    }
}

internal data class PreparedGainRamp(val attachmentEpoch: Long, val from: IntArray, val to: IntArray)

/** One service-lifetime job. Caller and engine writes must be serialized on the main thread. */
internal class EqGainRampController(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val canWrite: () -> Boolean,
    private val write: (Long, IntArray) -> Boolean
) {
    private var job: Job? = null
    private var generation = 0L

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun start(target: PreparedGainRamp) {
        cancel()
        if (target.from.contentEquals(target.to)) return
        val request = generation
        val startedAt = clockMs()
        job = scope.launch {
            while (true) {
                val elapsed = (clockMs() - startedAt).coerceAtLeast(0L)
                if (elapsed >= EqGainRamp.DURATION_MS) {
                    if (request == generation && canWrite()) write(target.attachmentEpoch, target.to)
                    break
                }
                delay(minOf(EqGainRamp.STEP_MS, EqGainRamp.DURATION_MS - elapsed))
                if (request != generation || !canWrite()) break
                val currentElapsed = (clockMs() - startedAt).coerceAtLeast(0L)
                val levels = EqGainRamp.interpolate(target.from, target.to,
                    currentElapsed, EqGainRamp.DURATION_MS)
                if (!write(target.attachmentEpoch, levels) || currentElapsed >= EqGainRamp.DURATION_MS) break
            }
        }
    }
}
