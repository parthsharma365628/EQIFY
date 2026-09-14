package com.example.eqify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Owns the hardware [Equalizer] AudioEffect.
 *
 * EQify owns eight logical frequency bands. Android devices expose different
 * hardware band layouts, so gains are mapped to the closest reported center
 * frequency before being converted from dB to millibels.
 */
object EqEngine {

    private const val TAG = "EqEngine"

    var equalizer: Equalizer? = null
        private set

    private var currentSessionId = 0
    private var currentGains = FloatArray(8)

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
            Log.d(TAG, "Session 0 attached — ${equalizer?.numberOfBands} bands")
            applyCurrentGains()
        } catch (e: Exception) {
            Log.e(TAG, "Session 0 fallback failed: ${e.message}")
        }
    }

    fun initSessionZero() {
        if (equalizer == null) trySessionZeroFallback()
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
            try { equalizer?.release() } catch (_: Exception) {}
            equalizer = null
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
            try { equalizer?.release() } catch (_: Exception) {}
            equalizer = null
        }
    }

    fun releaseEqualizer() {
        try { equalizer?.release() } catch (e: Exception) {
            Log.w(TAG, "Error releasing equalizer: ${e.message}")
        }
        equalizer = null
    }
}
