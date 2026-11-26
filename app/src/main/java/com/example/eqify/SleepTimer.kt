package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sleep timer (FEATURES_BATCH3_PLAN.md §C). The service runs it; this object holds
 * what the UI, notification and widget show. Not persisted: a reboot or process
 * death ends it.
 */
object SleepTimer {
    const val FADE_MS = 30_000L
    /** Quick fade when stopping at the end of a track. */
    const val SHORT_FADE_MS = 3_000L
    const val END_OF_TRACK = -1
    val CHOICES = listOf(15, 30, 45, 60)

    sealed interface State {
        data object Off : State
        /** [endsAtElapsed] is on the SystemClock.elapsedRealtime clock. */
        data class Counting(val endsAtElapsed: Long, val minutes: Int) : State
        data object UntilTrackEnds : State
        data object Fading : State
        /** The player ignored the pause request, so the volume was left at 0. */
        data object PlayerKeptPlaying : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state = _state.asStateFlow()
    internal fun publish(state: State) { _state.value = state }

    /** Minutes rounded up, for "Sleep in 12 min". */
    fun minutesLeft(endsAtElapsed: Long, nowElapsed: Long): Int =
        (((endsAtElapsed - nowElapsed).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    fun label(state: State, nowElapsed: Long): String? = when (state) {
        State.Off -> null
        is State.Counting -> "Sleep in ${minutesLeft(state.endsAtElapsed, nowElapsed)} min"
        State.UntilTrackEnds -> "Sleep after this track"
        State.Fading -> "Fading out for sleep"
        State.PlayerKeptPlaying -> "Music app didn't pause; volume left at 0"
    }

    /** Valid request: one of [CHOICES], [END_OF_TRACK], or 0 to cancel. */
    fun isValidRequest(minutes: Int): Boolean = minutes == 0 || minutes == END_OF_TRACK || minutes in 1..240

    /**
     * Volume steps for a fade from [startIndex] to 0 over [durationMs]: one entry per
     * step, strictly decreasing, ending at 0. Each pair is (delay before it, index).
     */
    fun fadeSteps(startIndex: Int, durationMs: Long): List<Pair<Long, Int>> {
        if (startIndex <= 0) return listOf(0L to 0)
        val gap = (durationMs / startIndex).coerceAtLeast(50L)
        return (startIndex - 1 downTo 0).map { gap to it }
    }
}

/**
 * Automation (FEATURES_BATCH3_PLAN.md §E): the only commands other apps may send.
 * Pure parsing of untrusted input; anything not on this list is ignored.
 */
sealed interface AutomationCommand {
    data class SetEq(val enabled: Boolean) : AutomationCommand
    data class SetBass(val levelDb: Float) : AutomationCommand
    data class SetPreset(val name: String) : AutomationCommand
    data class SetAutoGenre(val enabled: Boolean) : AutomationCommand
    data class Sleep(val minutes: Int) : AutomationCommand

    companion object {
        const val PREFIX = "com.liteplane.eqify.action."
        const val SET_EQ = PREFIX + "SET_EQ"
        const val SET_BASS = PREFIX + "SET_BASS"
        const val SET_PRESET = PREFIX + "SET_PRESET"
        const val SET_AUTO_GENRE = PREFIX + "SET_AUTO_GENRE"
        const val SLEEP_TIMER = PREFIX + "SLEEP_TIMER"
        val ACTIONS = listOf(SET_EQ, SET_BASS, SET_PRESET, SET_AUTO_GENRE, SLEEP_TIMER)

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_NAME = "name"
        const val EXTRA_MINUTES = "minutes"

        /**
         * [extras] holds the raw extra values (Boolean, Int, Long, Float, Double or
         * String are accepted; numbers may arrive as strings from Tasker).
         */
        fun parse(action: String?, extras: Map<String, Any?>): AutomationCommand? = when (action) {
            SET_EQ -> bool(extras[EXTRA_ENABLED])?.let(::SetEq)
            SET_AUTO_GENRE -> bool(extras[EXTRA_ENABLED])?.let(::SetAutoGenre)
            SET_BASS -> number(extras[EXTRA_LEVEL])?.toFloat()
                ?.takeIf { it.isFinite() && it in 0f..12f }
                ?.let { SetBass(DeviceProfiles.normaliseBass(it)) }
            SET_PRESET -> (extras[EXTRA_NAME] as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 100 }?.let(::SetPreset)
            SLEEP_TIMER -> number(extras[EXTRA_MINUTES])?.toInt()
                ?.takeIf { SleepTimer.isValidRequest(it) }?.let(::Sleep)
            else -> null
        }

        private fun bool(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) { "true", "1", "on" -> true; "false", "0", "off" -> false; else -> null }
            is Number -> when (v.toInt()) { 1 -> true; 0 -> false; else -> null }
            else -> null
        }

        private fun number(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }
}
