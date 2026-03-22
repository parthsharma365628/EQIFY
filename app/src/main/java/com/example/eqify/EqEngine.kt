package com.example.eqify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.log2

/**
 * Singleton that owns the hardware [Equalizer] AudioEffect.
 *
 * Session strategy (priority order):
 *  1. Real app session — received via ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
 *     broadcast from Spotify / YouTube Music. This is the correct approach and
 *     works even when Spotify bypasses session 0.
 *  2. Session 0 fallback — used only if (1) fails. Works on many stock Android
 *     builds but is unreliable on MIUI, OneUI with Dolby Atmos, etc.
 *
 * Band mapping:
 *  We maintain 8 logical bands (60Hz … 12kHz). The device Equalizer may have
 *  a different number of bands at different center frequencies. [applyGains]
 *  uses log-scale (octave) frequency matching via [EqProfileManager.mapToDeviceBands]
 *  to correctly assign our gains to whichever bands the device reports.
 *
 * Fixes vs the original version:
 *  - `getBandLevelRange()` → `bandLevelRange` (property, not method — the old
 *    call compiled but called a non-existent method via reflection and returned
 *    null, causing a NullPointerException at coerceIn on some devices).
 *  - Band mapping replaced from direct index assignment to frequency-based
 *    log-scale matching so the correct gain reaches the correct frequency band
 *    regardless of how many bands the device hardware exposes.
 *  - `for (i in 0 until n)` replaced with `repeat(n)` to silence the IDE
 *    warning about potentially empty ranges.
 */
object EqEngine {

    private const val TAG = "EqEngine"

    /** High priority so our correction sits late in the global mix (closer to output). */
    private const val EFFECT_PRIORITY = 10_000

    private var equalizer: Equalizer? = null
    private var currentSessionId = 0
    private var currentGains = FloatArray(8)

    // Cached device band layout — populated once when the Equalizer is created
    private var deviceBandCentersMilliHz = IntArray(0)
    private var deviceLevelRange = shortArrayOf(-1500, 1500)

    // ── Session broadcast receiver ────────────────────────────────────

    val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val sessionId   = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                    val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                    Log.d(TAG, "Session opened: $sessionId from $packageName")
                    if (sessionId != 0) attachToSession(sessionId)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                    if (sessionId == currentSessionId) {
                        Log.d(TAG, "Session closed: $sessionId")
                        releaseEqualizer()
                        // Re-attach to session 0 so EQ keeps a live effect until the next session opens.
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
            val eq = Equalizer(EFFECT_PRIORITY, sessionId).apply { enabled = true }
            cacheDeviceBandLayout(eq)
            equalizer = eq
            Log.d(TAG, "Attached to session $sessionId — " +
                    "${eq.numberOfBands} bands, centers: ${deviceBandCentersMilliHz.toList()}")
            applyCurrentGains()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach to session $sessionId: ${e.message}")
            trySessionZeroFallback()
        }
    }

    fun trySessionZeroFallback() {
        try {
            val eq = Equalizer(EFFECT_PRIORITY, 0).apply { enabled = true }
            cacheDeviceBandLayout(eq)
            equalizer = eq
            Log.d(TAG, "Fallback: attached to session 0")
            applyCurrentGains()
        } catch (e: Exception) {
            Log.e(TAG, "Session 0 fallback failed: ${e.message}")
        }
    }

    // ── Called by EqProcessingService when it starts ──────────────────
    // Attempts session 0 immediately so EQ works before the first
    // ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcast arrives.

    fun initSessionZero() {
        if (equalizer == null) trySessionZeroFallback()
    }

    // ── Gain application ──────────────────────────────────────────────

    /**
     * Apply [gains] (8 bands, dB) to the hardware Equalizer.
     * Uses log-scale (octave) frequency matching to map our logical 8 bands
     * onto whatever band layout this device's Equalizer reports.
     */
    fun applyGains(gains: FloatArray) {
        currentGains = gains.copyOf()
        if (equalizer == null) initSessionZero()
        applyCurrentGains()
    }

    private fun applyCurrentGains() {
        val eq = equalizer ?: return
        if (deviceBandCentersMilliHz.isEmpty()) return
        try {
            val levels = EqProfileManager.mapToDeviceBands(
                deviceBandCentersMilliHz,
                currentGains,
                deviceLevelRange
            )
            repeat(eq.numberOfBands.toInt()) { band ->
                eq.setBandLevel(band.toShort(), levels[band])
            }
            Log.d(TAG, "setBandLevel → ${levels.toList()} mB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply gains: ${e.message}")
        }
    }

    fun applyFlat() {
        currentGains = FloatArray(8)
        if (equalizer == null) initSessionZero()
        val eq = equalizer ?: return
        try {
            repeat(eq.numberOfBands.toInt()) { band ->
                eq.setBandLevel(band.toShort(), 0)
            }
            Log.d(TAG, "Flat EQ applied")
        } catch (e: Exception) {
            Log.e(TAG, "Flat EQ failed: ${e.message}")
        }
    }

    fun releaseEqualizer() {
        try { equalizer?.release() } catch (e: Exception) {
            Log.e(TAG, "Error releasing equalizer: ${e.message}")
        }
        equalizer = null
        deviceBandCentersMilliHz = IntArray(0)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun cacheDeviceBandLayout(eq: Equalizer) {
        val n = eq.numberOfBands.toInt()
        deviceBandCentersMilliHz = IntArray(n) { eq.getCenterFreq(it.toShort()) }
        deviceLevelRange = eq.bandLevelRange   // property, not method
    }
}