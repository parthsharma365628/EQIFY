package com.liteplane.eqify

import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/** In-memory controls for the debug experiment. The service owns playback and cleanup. */
internal object OrbitPrototype {
    private const val IDLE_CHECK_MS = 250L
    private const val STEP_MS = 25L

    private val _requested = MutableStateFlow(false)
    val requested = _requested.asStateFlow()
    private val _status = MutableStateFlow(Status.OFF)
    val status = _status.asStateFlow()

    enum class Status(val message: String) {
        OFF("A repeating 30-second left–right sweep for headphones."),
        PAUSED("Turn on EQ and leave Original to hear Orbit."),
        MONO("Turn off Force mono to hear Orbit."),
        HEADPHONES("Connect headphones to hear Orbit."),
        LIMITER("Select Balanced output protection on a compatible device."),
        WAITING("Play music to hear Orbit."),
        RUNNING("Sending a repeating 30-second sweep to the audio effect."),
        FAILED("The audio effect stopped accepting changes. Toggle Orbit to retry.")
    }

    /** Set while a left/right channel test plays, so the sweep doesn't move it. */
    @Volatile var holdForTest = false

    fun request(enabled: Boolean) {
        if (BuildConfig.DEBUG) _requested.value = enabled
    }

    // Runs on the service's main dispatcher to serialize effect writes and session attachment.
    // The timed waits suspend. Music activity is polled separately from gain writes.
    suspend fun observe(audio: AudioManager, repository: UserPreferencesRepository) {
        val blocked = combine(
            EqState.isEqEnabled, EqState.isBypassed, repository.forceMono,
            HeadphoneDetector.output, repository.deviceChoices
        ) { enabled, bypassed, mono, output, choices ->
            when {
                !enabled || bypassed -> Status.PAUSED
                mono -> Status.MONO
                output == null || output.kind == AudioOutputKind.SPEAKER ||
                    choices[output.key] == DeviceChoice.NotHeadphones -> Status.HEADPHONES
                else -> null
            } to output?.key
        }
        combine(requested, blocked, EqEngine.limiterDiagnosticStatus) { on, (reason, device), limiter ->
            val status = when {
                !on -> Status.OFF
                reason != null -> reason
                limiter.state != LimiterDiagnosticState.ENABLED -> Status.LIMITER
                else -> null
            }
            status to device
        }.distinctUntilChanged().collectLatest { (reason, _) ->
            if (reason != null) {
                _status.value = reason
            } else {
                try {
                    runSweep(audio)
                } finally {
                    EqEngine.resetOrbitGains()
                }
            }
        }
    }

    private suspend fun runSweep(audio: AudioManager) {
        var lastEpoch: Long? = null
        var startedAt = 0L
        var nextMusicCheck = 0L
        var musicActive = false
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.uptimeMillis()
            if (now >= nextMusicCheck) {
                musicActive = audio.isMusicActive
                nextMusicCheck = now + IDLE_CHECK_MS
            }
            val epoch = EqEngine.orbitAttachmentEpoch()
            if (!musicActive || epoch == null || holdForTest) {
                EqEngine.resetOrbitGains()
                lastEpoch = null
                _status.value = if (epoch == null) Status.LIMITER else Status.WAITING
                if (holdForTest) lastEpoch = null
                delay(IDLE_CHECK_MS)
                continue
            }
            if (epoch != lastEpoch) {
                lastEpoch = epoch
                startedAt = now
            }
            if (!EqEngine.writeOrbitGains(epoch, OrbitSweep.gainsAt(now - startedAt))) {
                _status.value = Status.FAILED
                return
            }
            _status.value = Status.RUNNING
            delay(STEP_MS)
        }
    }

    fun stop() {
        _requested.value = false
        _status.value = Status.OFF
        EqEngine.resetOrbitGains()
    }
}

/** Stereo balance sweep. Each channel can only be attenuated, up to 6 dB. */
internal object OrbitSweep {
    const val PERIOD_MS = 30_000L
    private const val DEPTH_DB = 6f

    data class Gains(val leftDb: Float, val rightDb: Float)

    fun gainsAt(elapsedMs: Long): Gains {
        val phase = (elapsedMs.coerceAtLeast(0L) % PERIOD_MS) / PERIOD_MS.toDouble()
        val balance = sin(2 * PI * phase).toFloat()
        return Gains(-DEPTH_DB * balance.coerceAtLeast(0f), DEPTH_DB * balance.coerceAtMost(0f))
    }
}
