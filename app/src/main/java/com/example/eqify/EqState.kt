package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class HeadphoneCorrectionStatus {
    object Idle : HeadphoneCorrectionStatus()
    data class Loading(val headphoneName: String) : HeadphoneCorrectionStatus()
    data class Downloaded(val headphoneName: String) : HeadphoneCorrectionStatus()
    data class Cached(val headphoneName: String) : HeadphoneCorrectionStatus()
    data class Unavailable(val headphoneName: String) : HeadphoneCorrectionStatus()
}

/**
 * Central state bus for EQify's EQ engine.
 *
 * Two separate data paths:
 *
 *  A) AUTO-GENRE PIPELINE (EqProcessingService.observeAutoEqPipeline):
 *     Observes baseToneGains + genre + headphone + bass → computes combined output.
 *     Slow path — may involve network calls for headphone correction.
 *
 *  B) MANUAL ADJUSTMENT PATH (EqProcessingService.observeManualAdjustments):
 *     Observes manualBandUpdate → applies IMMEDIATELY to hardware without any
 *     network calls, using the already-cached headphone correction from
 *     currentHeadphoneCorrection. This is what makes slider moves feel instant.
 *
 * WITHOUT path B, slider moves trigger path A which calls resolveHeadphoneCorrection
 * (network call). If the backend is down, collectLatest cancels the lambda before
 * applyGains is ever reached. That was why the EQ was silent.
 */
object EqState {

    // ── Output gains (after full pipeline: tone + HP + bass) ─────────
    // Written by EqProcessingService, read by EqScreen for display only.
    private val _activeBandGains = MutableStateFlow(FloatArray(8))
    val activeBandGains = _activeBandGains.asStateFlow()

    // Tone-only gains for the currently effective preset. Headphone correction
    // and bass boost are added only by EqProcessingService.
    private val _activeToneGains = MutableStateFlow(FloatArray(8))
    val activeToneGains = _activeToneGains.asStateFlow()

    private val _activePresetName = MutableStateFlow("Flat")
    val activePresetName = _activePresetName.asStateFlow()

    // ── Master toggle ─────────────────────────────────────────────────
    private val _isEqEnabled = MutableStateFlow(true)
    val isEqEnabled = _isEqEnabled.asStateFlow()

    // ── Service running flag ──────────────────────────────────────────
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    // ── Base tone gains (preset/slider values, WITHOUT headphone correction) ──
    // Observed by the AUTO-GENRE PIPELINE in EqProcessingService.
    // Changed when user picks a preset OR moves sliders.
    private val _baseToneGains = MutableStateFlow(FloatArray(8))
    val baseToneGains = _baseToneGains.asStateFlow()

    // Preset label that corresponds to _baseToneGains
    private val _tonePresetName = MutableStateFlow("Flat")
    val tonePresetName = _tonePresetName.asStateFlow()

    // ── MANUAL BAND UPDATE — THE KEY TO INSTANT SLIDER RESPONSE ──────
    // Emitted ONLY when the user physically moves a slider in EqScreen.
    // EqProcessingService.observeManualAdjustments() observes this and
    // calls EqEngine.applyGains() IMMEDIATELY without any network calls.
    // Non-null = new manual update in flight. Null = cleared after preset pick.
    private val _manualBandUpdate = MutableStateFlow<FloatArray?>(null)
    val manualBandUpdate = _manualBandUpdate.asStateFlow()

    private val _manualOverrideActive = MutableStateFlow(false)
    val manualOverrideActive = _manualOverrideActive.asStateFlow()

    // ── Headphone correction (cached from last API fetch) ─────────────
    // Written by EqProcessingService after fetching HP EQ from backend.
    // Read by observeManualAdjustments() to add HP correction WITHOUT a network call.
    private val _currentHeadphoneCorrection = MutableStateFlow(FloatArray(8))
    val currentHeadphoneCorrection = _currentHeadphoneCorrection.asStateFlow()

    private val _headphoneCorrectionStatus =
        MutableStateFlow<HeadphoneCorrectionStatus>(HeadphoneCorrectionStatus.Idle)
    val headphoneCorrectionStatus = _headphoneCorrectionStatus.asStateFlow()

    // ── Bass boost ────────────────────────────────────────────────────
    private val _bassBoostLevel = MutableStateFlow(0f)
    val bassBoostLevel = _bassBoostLevel.asStateFlow()

    // ── Limit output gain (synced from repository by service) ─────────
    private val _isLimitOutputGain = MutableStateFlow(true)
    val isLimitOutputGain get() = _isLimitOutputGain.value

    // ── Setters ───────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean)           { _isEqEnabled.value = enabled }
    fun setServiceRunning(running: Boolean)    { _isServiceRunning.value = running }
    fun setBassBoost(level: Float)             { _bassBoostLevel.value = level }
    fun setLimitOutputGain(enabled: Boolean)   { _isLimitOutputGain.value = enabled }
    fun setBaseToneGains(gains: FloatArray)    { _baseToneGains.value = gains.copyOf() }
    fun setTonePresetName(name: String)        { _tonePresetName.value = name }

    fun updateHeadphoneCorrection(correction: FloatArray) {
        _currentHeadphoneCorrection.value = correction.copyOf()
    }

    fun updateHeadphoneCorrectionStatus(status: HeadphoneCorrectionStatus) {
        _headphoneCorrectionStatus.value = status
    }

    // ── Compound setters ──────────────────────────────────────────────

    /**
     * User selected a preset from the picker OR app restored last preset on startup.
     * Updates base tone state so the AUTO-GENRE PIPELINE picks it up.
     * Clears manualBandUpdate so the manual path doesn't interfere.
     */
    fun restorePreset(presetName: String, toneGains: FloatArray) {
        _tonePresetName.value     = presetName
        _baseToneGains.value      = toneGains.copyOf()
        _activePresetName.value   = presetName
        _activeToneGains.value    = toneGains.copyOf()
        _activeBandGains.value    = toneGains.copyOf()
        _manualBandUpdate.value   = null
        _manualOverrideActive.value = false
    }

    fun applyManualPreset(presetName: String, toneGains: FloatArray) {
        _tonePresetName.value       = presetName
        _baseToneGains.value        = toneGains.copyOf()
        _activePresetName.value     = presetName
        _activeToneGains.value      = toneGains.copyOf()
        _manualOverrideActive.value = true
        _manualBandUpdate.value     = toneGains.copyOf()
    }

    /**
     * User physically moved a slider in EqScreen.
     *
     * CRITICAL: does NOT set _baseToneGains. Setting _baseToneGains would
     * trigger observeAutoEqPipeline in EqProcessingService via the combine,
     * which — when autoGenre is active — would immediately re-apply the genre
     * EQ and overwrite the slider change. That was why the EQ appeared to do
     * nothing: every manual change was silently overwritten ~1ms later.
     *
     * Only emits manualBandUpdate → observeManualAdjustments() applies to
     * hardware IMMEDIATELY with no network calls. The gains passed in already
     * include HP correction (they come from activeBandGains in EqScreen).
     */
    fun applyManualAdjustment(toneGains: FloatArray, presetName: String = "Custom") {
        _tonePresetName.value       = presetName
        _baseToneGains.value        = toneGains.copyOf()
        _activePresetName.value     = presetName
        _activeToneGains.value      = toneGains.copyOf()
        _manualOverrideActive.value = true
        _manualBandUpdate.value     = toneGains.copyOf()
    }

    fun clearManualOverride() {
        _manualOverrideActive.value = false
        _manualBandUpdate.value = null
    }

    /**
     * Called by EqProcessingService after computing the full pipeline output.
     * Updates display-only state (what the UI shows).
     */
    fun applyComputedOutput(presetName: String, toneGains: FloatArray, combinedGains: FloatArray) {
        _activePresetName.value = presetName
        _activeToneGains.value  = toneGains.copyOf()
        _activeBandGains.value  = combinedGains.copyOf()
    }

    fun updateOutputGains(combinedGains: FloatArray) {
        _activeBandGains.value = combinedGains.copyOf()
    }
}
