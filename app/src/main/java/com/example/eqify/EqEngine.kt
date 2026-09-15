package com.example.eqify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the hardware [Equalizer] AudioEffect.
 *
 * EQify owns eight logical frequency bands. Android devices expose different
 * hardware band layouts, so gains are mapped to the closest reported center
 * frequency before being converted from dB to millibels.
 */
object EqEngine {

    private const val TAG = "EqEngine"
    private const val LIMITER_CHANNEL_COUNT = 2
    private const val LIMITER_LINK_GROUP = 0
    private const val LIMITER_ATTACK_MS = 3f
    private const val LIMITER_RELEASE_MS = 100f
    private const val LIMITER_RATIO = 20f
    private const val LIMITER_THRESHOLD_DB = -1f
    private const val LIMITER_POST_GAIN_DB = 0f
    private const val LIMITER_FRAME_DURATION_MS = 10f
    private const val UNUSED_STAGE_BAND_COUNT = 2

    var equalizer: Equalizer? = null
        private set

    private var dynamicsProcessor: DynamicsProcessing? = null
    private var currentSessionId = 0
    private var currentGains = FloatArray(8)
    private var limiterProtectionRequested = false

    private val _limiterDiagnosticStatus = MutableStateFlow(initialLimiterStatus())
    val limiterDiagnosticStatus = _limiterDiagnosticStatus.asStateFlow()

    // ── Session broadcast receiver ────────────────────────────────────

    val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val sessionId   = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                    val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                    Log.d(TAG, "Session opened: $sessionId from $packageName")
                    if (sessionId != 0) attachToSession(sessionId)
                    else trySessionZeroFallback()
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                    if (sessionId == currentSessionId) {
                        Log.d(TAG, "Session closed: $sessionId — re-attaching to session 0")
                        releaseEqualizer()
                        trySessionZeroFallback()
                    }
                }
            }
        }
    }

    fun getSessionIntentFilter() = IntentFilter().apply {
        addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
    }

    // ── Session management ────────────────────────────────────────────

    private fun attachToSession(sessionId: Int) {
        releaseEqualizer()
        try {
            currentSessionId = sessionId
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            attachLimiterToSession(sessionId)
            Log.d(TAG, "Attached to session $sessionId — ${equalizer?.numberOfBands} bands")
            applyCurrentGains()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach to session $sessionId: ${e.message}")
            trySessionZeroFallback()
        }
    }

    fun trySessionZeroFallback() {
        releaseEqualizer()
        try {
            currentSessionId = 0
            equalizer = Equalizer(0, 0).apply { enabled = true }
            attachLimiterToSession(0)
            Log.d(TAG, "Session 0 attached — ${equalizer?.numberOfBands} bands")
            applyCurrentGains()
        } catch (e: Exception) {
            Log.e(TAG, "Session 0 fallback failed: ${e.message}")
        }
    }

    fun initSessionZero() {
        if (equalizer == null) trySessionZeroFallback()
    }

    // DynamicsProcessing limiter prototype

    /**
     * Enables the prototype when output protection is requested. If the effect
     * is unavailable, callers keep using EQify's existing safe gain scaling.
     */
    fun setLimiterProtectionEnabled(enabled: Boolean) {
        limiterProtectionRequested = enabled

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = LimiterDiagnosticState.UNSUPPORTED,
                    available = false,
                    attached = false,
                    enabled = false,
                    detail = "DynamicsProcessing requires Android 9 (API 28); Safe fallback active"
                )
            )
            return
        }

        val processor = dynamicsProcessor ?: return
        try {
            processor.enabled = enabled
            val effectEnabled = processor.enabled
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = if (effectEnabled) {
                        LimiterDiagnosticState.ENABLED
                    } else {
                        LimiterDiagnosticState.ATTACHED
                    },
                    available = true,
                    attached = true,
                    enabled = effectEnabled,
                    sessionId = currentSessionId,
                    detail = if (effectEnabled) {
                        "Linked-stereo limiter enabled"
                    } else {
                        "Limiter attached but disabled"
                    }
                )
            )
        } catch (e: Exception) {
            releaseLimiter()
            publishLimiterFailure(currentSessionId, "Could not change enabled state", e)
        }
    }

    fun isLimiterProtectionActive(): Boolean =
        _limiterDiagnosticStatus.value.enabled && dynamicsProcessor?.enabled == true

    private fun attachLimiterToSession(sessionId: Int) {
        releaseLimiter()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = LimiterDiagnosticState.UNSUPPORTED,
                    available = false,
                    attached = false,
                    enabled = false,
                    sessionId = sessionId,
                    detail = "DynamicsProcessing requires Android 9 (API 28); Safe fallback active"
                )
            )
            return
        }

        val advertised = try {
            val effects = AudioEffect.queryEffects()
            effects == null || effects.any {
                it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
            }
        } catch (e: Exception) {
            Log.w(TAG, "Limiter capability query failed; attempting attachment: ${e.message}")
            true
        }

        if (!advertised) {
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = LimiterDiagnosticState.UNSUPPORTED,
                    available = false,
                    attached = false,
                    enabled = false,
                    sessionId = sessionId,
                    detail = "DynamicsProcessing is not advertised by this device; Safe fallback active"
                )
            )
            return
        }

        publishLimiterStatus(
            LimiterDiagnosticStatus(
                state = LimiterDiagnosticState.AVAILABLE,
                available = true,
                attached = false,
                enabled = false,
                sessionId = sessionId,
                detail = "DynamicsProcessing is available; attaching limiter"
            )
        )

        var phase = "creating limiter parameters"
        try {
            val limiter = DynamicsProcessing.Limiter(
                true,
                true,
                LIMITER_LINK_GROUP,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                LIMITER_POST_GAIN_DB
            )
            phase = "building DynamicsProcessing configuration"
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                LIMITER_CHANNEL_COUNT,
                false,
                UNUSED_STAGE_BAND_COUNT,
                false,
                UNUSED_STAGE_BAND_COUNT,
                false,
                UNUSED_STAGE_BAND_COUNT,
                true
            )
                .setPreferredFrameDuration(LIMITER_FRAME_DURATION_MS)
                .setLimiterAllChannelsTo(limiter)
                .build()

            phase = "creating DynamicsProcessing effect"
            val processor = DynamicsProcessing(0, sessionId, config)
            phase = "checking DynamicsProcessing control"
            if (!processor.hasControl()) {
                processor.release()
                throw IllegalStateException("DynamicsProcessing attached without effect control")
            }
            phase = "validating limiter channel count"
            if (processor.channelCount != LIMITER_CHANNEL_COUNT) {
                processor.release()
                throw IllegalStateException(
                    "Expected $LIMITER_CHANNEL_COUNT limiter channels, got ${processor.channelCount}"
                )
            }
            dynamicsProcessor = processor
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = LimiterDiagnosticState.ATTACHED,
                    available = true,
                    attached = true,
                    enabled = false,
                    sessionId = sessionId,
                    detail = "Limiter attached to EQ audio session with ${processor.channelCount} channels"
                )
            )

            phase = "enabling DynamicsProcessing effect"
            processor.enabled = limiterProtectionRequested
            val effectEnabled = processor.enabled
            if (limiterProtectionRequested && !effectEnabled) {
                throw IllegalStateException("DynamicsProcessing refused the enable request")
            }
            logLimiterReadback(processor)
            publishLimiterStatus(
                LimiterDiagnosticStatus(
                    state = if (effectEnabled) {
                        LimiterDiagnosticState.ENABLED
                    } else {
                        LimiterDiagnosticState.ATTACHED
                    },
                    available = true,
                    attached = true,
                    enabled = effectEnabled,
                    sessionId = sessionId,
                    detail = if (effectEnabled) {
                        "Limiter enabled: threshold=${LIMITER_THRESHOLD_DB}dB " +
                            "ratio=${LIMITER_RATIO}:1 attack=${LIMITER_ATTACK_MS}ms " +
                            "release=${LIMITER_RELEASE_MS}ms linkGroup=$LIMITER_LINK_GROUP " +
                            "postGain=${LIMITER_POST_GAIN_DB}dB"
                    } else {
                        "Limiter attached; output protection is currently off"
                    }
                )
            )
        } catch (e: Exception) {
            releaseLimiter()
            publishLimiterFailure(sessionId, "$phase failed", e)
        }
    }

    private fun logLimiterReadback(processor: DynamicsProcessing) {
        try {
            for (channel in 0 until processor.channelCount) {
                val limiter = processor.getLimiterByChannelIndex(channel)
                Log.d(
                    TAG,
                    "Limiter readback: channel=$channel inUse=${limiter.isInUse} " +
                        "stageEnabled=${limiter.isEnabled} linkGroup=${limiter.linkGroup} " +
                        "threshold=${limiter.threshold}dB ratio=${limiter.ratio}:1 " +
                        "attack=${limiter.attackTime}ms release=${limiter.releaseTime}ms " +
                        "postGain=${limiter.postGain}dB"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Limiter readback failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun publishLimiterFailure(sessionId: Int, prefix: String, error: Exception) {
        publishLimiterStatus(
            LimiterDiagnosticStatus(
                state = LimiterDiagnosticState.FAILED,
                available = true,
                attached = false,
                enabled = false,
                sessionId = sessionId,
                detail = "$prefix: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}; Safe fallback active"
            )
        )
    }

    private fun publishLimiterStatus(status: LimiterDiagnosticStatus) {
        _limiterDiagnosticStatus.value = status
        val message = "Limiter prototype: state=${status.state} " +
            "available=${status.available} attached=${status.attached} " +
            "enabled=${status.enabled} session=${status.sessionId} detail=${status.detail}"
        if (status.state == LimiterDiagnosticState.FAILED ||
            status.state == LimiterDiagnosticState.UNSUPPORTED
        ) {
            Log.w(TAG, message)
        } else {
            Log.d(TAG, message)
        }
    }

    private fun initialLimiterStatus(): LimiterDiagnosticStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LimiterDiagnosticStatus(
                state = LimiterDiagnosticState.AVAILABLE,
                available = true,
                attached = false,
                enabled = false,
                detail = "Android 9+ detected; waiting for an EQ audio session"
            )
        } else {
            LimiterDiagnosticStatus(
                state = LimiterDiagnosticState.UNSUPPORTED,
                available = false,
                attached = false,
                enabled = false,
                detail = "DynamicsProcessing requires Android 9 (API 28); Safe fallback active"
            )
        }

    private fun releaseLimiter() {
        try {
            dynamicsProcessor?.enabled = false
            dynamicsProcessor?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing limiter: ${e.message}")
        }
        dynamicsProcessor = null
    }

    // ── Gain application ──────────────────────────────────────────────

    fun applyGains(gains: FloatArray) {
        currentGains = gains.copyOf()
        if (equalizer == null) trySessionZeroFallback()
        applyCurrentGains()
    }

    private fun applyCurrentGains() {
        val eq = equalizer ?: run {
            Log.w(TAG, "applyCurrentGains: no equalizer attached")
            return
        }
        try {
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val centers = IntArray(numBands) { band ->
                eq.getCenterFreq(band.toShort())
            }
            val mappedLevels = EqProfileManager.mapToDeviceBands(
                centers,
                currentGains,
                range
            )

            Log.d(TAG, "Applying to $numBands device bands")

            for (i in 0 until numBands) {
                eq.setBandLevel(i.toShort(), mappedLevels[i])
            }

            Log.d(TAG, "setBandLevel done")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply gains: ${e.message}")
            // Session went stale — clear so next call retries
            releaseEqualizer()
        }
    }

    fun applyFlat() {
        currentGains = FloatArray(8)
        if (equalizer == null) trySessionZeroFallback()
        val eq = equalizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            for (i in 0 until numBands) {
                eq.setBandLevel(i.toShort(), 0)
            }
            Log.d(TAG, "Flat EQ applied")
        } catch (e: Exception) {
            Log.e(TAG, "Flat EQ failed: ${e.message}")
            releaseEqualizer()
        }
    }

    fun releaseEqualizer() {
        releaseLimiter()
        try { equalizer?.release() } catch (e: Exception) {
            Log.w(TAG, "Error releasing equalizer: ${e.message}")
        }
        equalizer = null
    }
}
