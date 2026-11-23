package com.example.eqify

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.theme.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.annotation.DrawableRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import com.example.eqify.ui.components.*

/** Matches the Home screen's Bass Booster buttons. */
private val BASS_LEVELS = listOf(0f, 3f, 6f, 9f)

/** What the share sheet will encode. Toggles only drop optional parts. */
data class ShareDraft(
    val name: String,
    val tone: List<Float>,
    val correction: SharedCorrection?,
    val bass: Float?,
    val isBuiltIn: Boolean = false,
    /** Sharing an imported correction by itself: flat tone, correction always included. */
    val correctionOnly: Boolean = false,
    val includeCorrection: Boolean = true,
    val includeBass: Boolean = true
) {
    val profile: SharedEqProfile
        get() = SharedEqProfile(
            name, tone,
            correction.takeIf { includeCorrection || correctionOnly },
            bass.takeIf { includeBass }
        )
    val code: String get() = EqProfileCodec.encode(profile)
    /** QR content: a phone camera can open it in EQify; the in-app scanner reads it too. */
    val link: String get() = EqProfileCodec.importLink(code)
    val suggestedFileName: String
        get() = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "profile" } + ".eqify.json"
}

/** A decoded profile awaiting the user's confirmation. */
data class ImportPreview(
    val profile: SharedEqProfile,
    /** Catalog spelling when the profile names a dataset headphone that exists locally. */
    val datasetMatch: String?,
    val autoGenreOn: Boolean
)

class EqScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val defaultPresetNames = EqProfileManager.defaultProfiles.keys.toList()

    val customPresetNames: StateFlow<List<String>> = repository.customPresetNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allPresetNames: StateFlow<List<String>> = customPresetNames
        .map { custom -> defaultPresetNames + custom }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultPresetNames)

    // Read from EqState singleton so navigation never resets to a wrong value
    private val _activePreset = MutableStateFlow(EqState.activePresetName.value)
    val activePreset: StateFlow<String> = _activePreset.asStateFlow()

    // Sliders edit tone-only values. Headphone correction and bass boost remain
    // separate and are combined exactly once in EqProcessingService.
    private val _bands = MutableStateFlow(EqState.activeToneGains.value.toList())
    val bands: StateFlow<List<Float>> = _bands.asStateFlow()

    private val customGainsCache = mutableMapOf<String, FloatArray>()

    /** User versions of built-in curves (FEATURE_CUSTOM_GENRE_CURVES.md). */
    val genreOverrides: StateFlow<Map<String, List<Float>>> = repository.genreOverrides
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Strength applied on top of automatic genre EQ, or null when it changes nothing. */
    val genreStrengthNote: StateFlow<Float?> = combine(
        DeviceSoundCoordinator.state, EqState.manualOverrideActive
    ) { device, manual ->
        val e = device.effective
        e.genreStrength.takeIf { e.autoGenre && e.devicePreset == null && !manual && it != GenreStrength.DEFAULT }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Per-device sound (FEATURES_NEXT_PLAN.md §4) ───────────────────

    /** "Set by <device>" while the connected device's preset is playing. */
    val deviceSourceLabel: StateFlow<String?> = combine(
        DeviceSoundCoordinator.state, EqState.manualOverrideActive
    ) { device, manual -> device.label.takeIf { device.effective.devicePreset != null && !manual } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** (device label, preset): offer to make a manual pick this device's preset. */
    private val _deviceAlwaysPrompt = MutableStateFlow<Pair<String, String>?>(null)
    val deviceAlwaysPrompt: StateFlow<Pair<String, String>?> = _deviceAlwaysPrompt.asStateFlow()

    fun useForDeviceAlways() {
        val (_, preset) = _deviceAlwaysPrompt.value ?: return
        _deviceAlwaysPrompt.value = null
        val device = DeviceSoundCoordinator.state.value
        val key = device.deviceKey ?: return
        val kind = device.kind ?: return
        viewModelScope.launch {
            repository.updateDeviceProfile(key, kind) { it.copy(sound = DeviceSound.Preset(preset)) }
            EqState.clearManualOverride() // the device setting now governs
        }
    }

    fun dismissDeviceAlwaysPrompt() { _deviceAlwaysPrompt.value = null }

    private val _showPresetPicker    = MutableStateFlow(false)
    val showPresetPicker: StateFlow<Boolean> = _showPresetPicker.asStateFlow()

    private val _showNewProfileDialog = MutableStateFlow(false)
    val showNewProfileDialog: StateFlow<Boolean> = _showNewProfileDialog.asStateFlow()

    private val _newProfileError = MutableStateFlow<String?>(null)
    val newProfileError: StateFlow<String?> = _newProfileError.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _showDeleteConfirm   = MutableStateFlow<String?>(null)
    val showDeleteConfirm: StateFlow<String?> = _showDeleteConfirm.asStateFlow()

    private var presetSelectionJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = repository.loadAllCustomPresets()
            customGainsCache.putAll(saved)
        }
        // Sync preset label with auto-EQ changes (e.g. genre detection updating preset)
        viewModelScope.launch {
            EqState.activePresetName.collect { name ->
                if (_activePreset.value != name) {
                    _activePreset.value = name
                    _hasUnsavedChanges.value = false
                }
            }
        }
        // Sync slider positions from the COMBINED output (activeBandGains).
        // This fires when:
        //   - Genre changes and auto pipeline sets a new EQ curve
        //   - Headphone changes and correction curve updates
        //   - Bass boost / settings change
        // It does NOT fire on manual slider drags (since applyManualAdjustment
        // no longer sets baseToneGains, it only sets activeBandGains — which
        // does trigger this, but _bands is already at the same value so no-op).
        viewModelScope.launch {
            EqState.activeToneGains.collect { gains ->
                if (EqState.manualOverrideActive.value &&
                    !gains.contentEquals(EqState.baseToneGains.value)) return@collect
                val current = _bands.value.toFloatArray()
                if (!current.contentEquals(gains)) _bands.value = gains.toList()
            }
        }
    }

    // ── Preset selection ──────────────────────────────────────────────

    fun selectPreset(name: String) {
        _showPresetPicker.value = false
        presetSelectionJob?.cancel()
        presetSelectionJob = viewModelScope.launch {
            val rawGains = customGainsCache[name]?.copyOf()
                ?: repository.loadCustomPresetGains(name)?.also {
                    customGainsCache[name] = it.copyOf()
                }
                ?: EqProfileManager.effectiveGains(name, repository.genreOverrides.first())
                ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()

            _activePreset.value = name
            _bands.value = rawGains.toList()
            _hasUnsavedChanges.value = false
            EqState.applyManualPreset(name, rawGains)
            repository.setLastPreset(name)
            val device = DeviceSoundCoordinator.state.value
            val devicePreset = device.effective.devicePreset
            _deviceAlwaysPrompt.value = if (devicePreset != null && devicePreset != name && device.label != null)
                device.label to name else null
        }
    }

    // ── Band update (user drags a slider) ─────────────────────────────
    //
    // gains here are COMBINED (tone + HP correction + bass boost) because
    // _bands mirrors activeBandGains. EqProcessingService.observeManualAdjustments
    // applies these directly to hardware WITHOUT adding HP correction again.

    fun updateBand(index: Int, gain: Float) {
        if (!gain.isFinite()) return
        presetSelectionJob?.cancel()
        val latest = if (EqState.manualOverrideActive.value)
            EqState.baseToneGains.value.toList() else EqState.activeToneGains.value.toList()
        val updated = latest.toMutableList().apply { set(index, gain.coerceIn(-12f, 12f)) }
        _bands.value = updated
        val gains = updated.toFloatArray()

        if (isDefaultPreset(_activePreset.value) && !GenreCurveOverrides.isCustomisable(_activePreset.value)) {
            _activePreset.value = "Custom"
        }

        _hasUnsavedChanges.value = true
        EqState.applyManualAdjustment(gains, _activePreset.value)
    }

    // ── Reset ─────────────────────────────────────────────────────────

    fun resetCurrentPreset() {
        val name     = _activePreset.value
        val rawGains = resolveGains(name)
        _bands.value = rawGains.toList()
        _hasUnsavedChanges.value = false
        EqState.applyManualPreset(name, rawGains)
    }

    // ── Create new profile ────────────────────────────────────────────

    fun createNewProfile(name: String) {
        val trimmedName = name.trim()
        PresetNameRules.validateNew(trimmedName, customPresetNames.value)?.let {
            _newProfileError.value = it
            return
        }
        val gains = _bands.value.toFloatArray()
        customGainsCache[trimmedName] = gains
        viewModelScope.launch {
            repository.saveCustomPreset(trimmedName, gains)
            repository.setLastPreset(trimmedName)
        }
        _activePreset.value        = trimmedName
        _hasUnsavedChanges.value   = false
        _newProfileError.value     = null
        _showNewProfileDialog.value = false
        _showPresetPicker.value    = false
        EqState.applyManualPreset(trimmedName, gains)
    }

    // ── Save to active preset ─────────────────────────────────────────

    fun saveToCurrentPreset() {
        val name = _activePreset.value
        if (name == "Custom" || (isDefaultPreset(name) && !GenreCurveOverrides.isCustomisable(name))) {
            _showNewProfileDialog.value = true; return
        }
        val gains = _bands.value.toFloatArray()
        if (isDefaultPreset(name)) {
            // Customise the built-in for every song of this genre. Equal to the default = no override.
            _hasUnsavedChanges.value = false
            viewModelScope.launch {
                repository.setGenreOverride(name, gains.toList())
                repository.setLastPreset(name)
            }
            EqState.applyManualPreset(name, gains)
            return
        }
        customGainsCache[name] = gains
        _hasUnsavedChanges.value = false
        viewModelScope.launch {
            repository.saveCustomPreset(name, gains)
            repository.setLastPreset(name)
        }
        EqState.applyManualPreset(name, gains)
    }

    // ── Reset a customised built-in ───────────────────────────────────

    fun isCustomised(name: String) = genreOverrides.value.containsKey(name)

    fun resetToDefault(name: String) {
        if (!GenreCurveOverrides.isCustomisable(name)) return
        viewModelScope.launch { repository.setGenreOverride(name, null) }
        // Automatic mode follows the overrides flow; a manually chosen preset is re-applied here.
        if (_activePreset.value == name && EqState.manualOverrideActive.value) {
            val gains = EqProfileManager.defaultProfiles[name]!!.copyOf()
            presetSelectionJob?.cancel()
            _bands.value = gains.toList()
            _hasUnsavedChanges.value = false
            EqState.applyManualPreset(name, gains)
        }
    }

    // ── Delete ────────────────────────────────────────────────────────

    fun requestDelete(name: String) { _showDeleteConfirm.value = name }

    fun confirmDelete(name: String) {
        customGainsCache.remove(name)
        viewModelScope.launch {
            repository.deleteCustomPreset(name)
            repository.removePresetFromDeviceProfiles(name)
            // Songs corrected to this preset go back to automatic detection.
            runCatching { (getApplication<Application>() as EqifyApplication).genreCoordinator.deletePreset(name) }
        }
        if (_activePreset.value == name) selectPreset("Flat")
        _showDeleteConfirm.value = null
    }

    fun cancelDelete() { _showDeleteConfirm.value = null }

    suspend fun renameProfile(oldName: String, newName: String): String? {
        return try {
            repository.renameCustomPreset(oldName, newName)
            repository.renamePresetInDeviceProfiles(oldName, newName.trim())
            // Songs corrected to this preset follow the new name.
            runCatching {
                (getApplication<Application>() as EqifyApplication).genreCoordinator.renamePreset(oldName, newName.trim())
            }
            val name = newName.trim()
            customGainsCache.remove(oldName)?.let { customGainsCache[name] = it }
            if (_activePreset.value == oldName) _activePreset.value = name
            EqState.renamePreset(oldName, name)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Couldn't rename the preset."
        }
    }

    // ── Sharing: export ───────────────────────────────────────────────
    //
    // Export reads tone gains (never the combined output), plus the active
    // headphone correction only when its gains are actually loaded.

    private val customEqProfiles = (application as EqifyApplication).customEqProfiles

    private val _shareDraft = MutableStateFlow<ShareDraft?>(null)
    val shareDraft: StateFlow<ShareDraft?> = _shareDraft.asStateFlow()

    private val _shareMessage = MutableStateFlow<String?>(null)
    val shareMessage: StateFlow<String?> = _shareMessage.asStateFlow()

    /** Opens the share sheet for [presetName], or for the current sliders when null. */
    fun openShare(presetName: String? = null) {
        _showPresetPicker.value = false
        viewModelScope.launch {
            val tone = if (presetName == null) _bands.value.toFloatArray() else
                customGainsCache[presetName]?.copyOf()
                    ?: repository.loadCustomPresetGains(presetName)
                    ?: EqProfileManager.effectiveGains(presetName, repository.genreOverrides.first())
            if (tone == null || tone.size != 8 || tone.any { !it.isFinite() || it !in -12f..12f }) {
                _shareMessage.value = "This preset can't be shared because its saved values are invalid."
                return@launch
            }
            val name = presetName ?: _activePreset.value.takeUnless { it == "Custom" } ?: "My EQ"
            _shareDraft.value = ShareDraft(
                name = name,
                tone = tone.toList(),
                correction = correctionForShare(),
                bass = EqState.bassBoostLevel.value.takeIf { it > 0f && it <= EqProfileCodec.MAX_BASS_DB },
                isBuiltIn = presetName != null && isDefaultPreset(presetName) && !isCustomised(presetName)
            )
        }
    }

    private suspend fun correctionForShare(): SharedCorrection? {
        val shared = when (val selection = repository.headphoneCorrectionSelection.first()) {
            is HeadphoneCorrectionSelection.Dataset -> {
                val ready = when (val status = EqState.headphoneCorrectionStatus.value) {
                    is HeadphoneCorrectionStatus.Downloaded -> status.headphoneName.equals(selection.name, true)
                    is HeadphoneCorrectionStatus.Cached -> status.headphoneName.equals(selection.name, true)
                    else -> false
                }
                if (!ready) null else SharedCorrection(SharedCorrection.Kind.DATASET, selection.name,
                    EqState.currentHeadphoneCorrection.value.toList())
            }
            is HeadphoneCorrectionSelection.Custom -> customEqProfiles.find(selection.profileId)?.let {
                SharedCorrection(SharedCorrection.Kind.CUSTOM, it.displayName, it.gains)
            }
            HeadphoneCorrectionSelection.None -> null
        }
        return shared?.takeIf { c -> c.gains.size == 8 && c.gains.all { it.isFinite() && it in -12f..12f } }
    }

    fun setShareIncludeCorrection(include: Boolean) {
        _shareDraft.update { it?.copy(includeCorrection = include) }
    }

    fun setShareIncludeBass(include: Boolean) {
        _shareDraft.update { it?.copy(includeBass = include) }
    }

    fun closeShare() { _shareDraft.value = null }

    fun onCodeCopied() {
        _shareMessage.value = "Profile code copied. Paste it anywhere to share."
        _shareDraft.value = null
    }

    fun exportShareToFile(uri: Uri) {
        val draft = _shareDraft.value ?: return
        viewModelScope.launch {
            _shareMessage.value = try {
                withContext(Dispatchers.IO) {
                    ProfileShareIo.writeJsonFile(getApplication(), uri, draft.profile)
                }
                "Saved \"${draft.name}\" to a file."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the file: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun dismissShareMessage() { _shareMessage.value = null }

    // ── Sharing: import ───────────────────────────────────────────────
    //
    // Decode → preview → explicit confirm. Nothing is written before confirm.
    // Applying goes through selectPreset / EqState and the tagged correction
    // selection, never EqEngine.

    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _importBusy = MutableStateFlow(false)
    val importBusy: StateFlow<Boolean> = _importBusy.asStateFlow()

    private var importJob: Job? = null

    fun openImport() {
        _showPresetPicker.value = false
        _importError.value = null
        _showImportDialog.value = true
    }

    fun closeImport() {
        importJob?.cancel()
        _importBusy.value = false
        _showImportDialog.value = false
        _importPreview.value = null
        _importError.value = null
    }

    fun importFromText(text: String) = startPreview { text }

    /** Reported by the QR scanner (unavailable, failed); shown in the import dialog. */
    fun showImportError(message: String) {
        _importError.value = message
    }

    fun importFromFile(uri: Uri) = startPreview {
        withContext(Dispatchers.IO) {
            BoundedTextReader.read(getApplication(), uri, maxBytes = 64 * 1024, limitLabel = "64 KB").text
        }
    }

    private fun startPreview(readText: suspend () -> String) {
        importJob?.cancel()
        _importError.value = null
        _importBusy.value = true
        importJob = viewModelScope.launch {
            try {
                val text = readText()
                val profile = withContext(Dispatchers.Default) { EqProfileCodec.decode(text) }
                val match = profile.correction
                    ?.takeIf { it.kind == SharedCorrection.Kind.DATASET }
                    ?.let { findCatalogName(it.name) }
                _importPreview.value = ImportPreview(
                    profile = profile,
                    datasetMatch = match,
                    autoGenreOn = repository.autoGenreDetection.first()
                )
                _showImportDialog.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importError.value = e.message ?: "This profile could not be read."
            } finally {
                _importBusy.value = false
            }
        }
    }

    /** Cached-catalog lookup; offline or slow → null, and embedded gains are used instead. */
    private suspend fun findCatalogName(name: String): String? = try {
        withTimeoutOrNull(5_000) { HeadphoneDataRepository.findExactName(getApplication(), name) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun confirmImport(saveTone: Boolean, useCorrection: Boolean, applyBass: Boolean, useNow: Boolean) {
        val preview = _importPreview.value ?: return
        if (_importBusy.value) return
        _importBusy.value = true
        importJob = viewModelScope.launch {
            val done = mutableListOf<String>()
            try {
                val profile = preview.profile
                val tone = profile.tone.toFloatArray()

                val savedName = if (saveTone) saveImportedTone(profile.name, tone).also {
                    done += "Saved \"$it\" to My presets."
                } else null

                val correction = profile.correction
                if (useCorrection && correction != null) {
                    val match = preview.datasetMatch
                    if (correction.kind == SharedCorrection.Kind.DATASET && match != null) {
                        if (useNow) {
                            repository.setSelectedHeadphone(match)
                            NowPlayingState.updateSelectedHeadphone(match)
                            done += "Using $match correction."
                        } else {
                            done += "$match is in your headphone catalog; select it on the Headphones screen to use it."
                        }
                    } else {
                        val id = withContext(Dispatchers.IO) { saveImportedCorrection(correction) }
                        if (useNow) repository.selectCustomCorrection(id)
                        done += "Headphone correction saved to Imported corrections" +
                            if (useNow) " and selected." else "."
                    }
                }

                val bass = profile.bass
                if (applyBass && bass != null) {
                    val level = snapBassLevel(bass)
                    DeviceSoundCoordinator.setBass(level)
                    done += if (level == 0f) "Bass boost turned off." else "Bass boost set to +${level.toInt()} dB."
                }

                if (useNow) {
                    if (savedName != null) selectPreset(savedName) else applyUnsavedTone(tone)
                }
                _shareMessage.value = done.joinToString(" ").ifEmpty { "Nothing was imported." }
                _importPreview.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _shareMessage.value = (done + "Import stopped: ${e.message ?: "unknown error"}").joinToString(" ")
                _importPreview.value = null
            } finally {
                _importBusy.value = false
            }
        }
    }

    /** Saves as a new preset with a free name; never overwrites an existing one. */
    private suspend fun saveImportedTone(baseName: String, gains: FloatArray): String {
        var lastError: Exception? = null
        repeat(3) {
            val name = PresetNameRules.uniqueName(baseName, repository.customPresetNames.first())
            try {
                repository.saveNewCustomPreset(name, gains)
                customGainsCache[name] = gains.copyOf()
                return name
            } catch (e: IllegalArgumentException) {
                lastError = e // a concurrent save took the name; pick another
            }
        }
        throw lastError ?: IllegalStateException("Could not save the preset.")
    }

    /** Reuses an identical imported correction instead of duplicating it. Returns its id. */
    private fun saveImportedCorrection(correction: SharedCorrection): String {
        customEqProfiles.profiles.value.firstOrNull { existing ->
            existing.gains.map { EqProfileCodec.round01(it) } == correction.gains
        }?.let { return it.id }
        return customEqProfiles.importProfile(correction.name, "Shared profile",
            correction.gains.toFloatArray()).id
    }

    private fun snapBassLevel(level: Float): Float =
        BASS_LEVELS.minByOrNull { abs(it - level) } ?: 0f

    /** "Use now" without saving: same effect as editing the sliders by hand. */
    private fun applyUnsavedTone(gains: FloatArray) {
        presetSelectionJob?.cancel()
        _activePreset.value = "Custom"
        _bands.value = gains.toList()
        _hasUnsavedChanges.value = true
        EqState.applyManualAdjustment(gains.copyOf(), "Custom")
    }

    // ── Dialog helpers ────────────────────────────────────────────────

    fun openPresetPicker()  { _showPresetPicker.value = true  }
    fun closePresetPicker() { _showPresetPicker.value = false }
    fun openNewProfile() {
        _newProfileError.value = null
        _showNewProfileDialog.value = true
    }
    fun closeNewProfile() {
        _newProfileError.value = null
        _showNewProfileDialog.value = false
    }

    fun isDefaultPreset(name: String) = EqProfileManager.defaultProfiles.containsKey(name)

    private fun resolveGains(name: String): FloatArray =
        customGainsCache[name]?.copyOf()
            ?: EqProfileManager.effectiveGains(name, genreOverrides.value)
            ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()

    // Must stay the LAST member: viewModelScope uses Main.immediate, so this can
    // run synchronously and needs every import/share property above initialized.
    // Profile text from an eqify:// link or "Share to EQify": open the import
    // dialog (so errors are visible) and decode into the preview. Never auto-apply.
    init {
        viewModelScope.launch {
            PendingProfileImport.text.filterNotNull().collect {
                val text = PendingProfileImport.consume() ?: return@collect
                openImport()
                importFromText(text)
            }
        }
    }
}

// ── EqScreen ──────────────────────────────────────────────────────────
//
// UI only (UI_REDESIGN.md §6 "EQ"). All state and actions come from
// EqScreenViewModel / EqState exactly as before; nothing here touches EqEngine.

@Composable
fun EqScreen(vm: EqScreenViewModel = viewModel()) {
    val c = EqifyTheme.colors
    val bands             by vm.bands.collectAsState()
    val activePreset      by vm.activePreset.collectAsState()
    val showPicker        by vm.showPresetPicker.collectAsState()
    val showNewProfile    by vm.showNewProfileDialog.collectAsState()
    val newProfileError   by vm.newProfileError.collectAsState()
    val showDeleteConfirm by vm.showDeleteConfirm.collectAsState()
    val allPresets        by vm.allPresetNames.collectAsState()
    val customPresets     by vm.customPresetNames.collectAsState()
    val hasUnsavedChanges by vm.hasUnsavedChanges.collectAsState()
    val genreOverrides    by vm.genreOverrides.collectAsState()
    val genreStrengthNote by vm.genreStrengthNote.collectAsState()
    val deviceSourceLabel by vm.deviceSourceLabel.collectAsState()
    val deviceAlwaysPrompt by vm.deviceAlwaysPrompt.collectAsState()
    var showLayers by remember { mutableStateOf(false) }
    if (showLayers) SoundLayersSheet(genreStrengthNote, onDismiss = { showLayers = false })
    val isBypassed        by EqState.isBypassed.collectAsState()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var preciseBand by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    renameTarget?.let { oldName ->
        AlertDialog(
            onDismissRequest = { if (!renaming) renameTarget = null },
            title = { Text("Rename preset", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it; renameError = null },
                    singleLine = true,
                    enabled = !renaming,
                    label = { Text("Preset name") },
                    isError = renameError != null,
                    supportingText = { renameError?.let { Text(it) } }
                )
            },
            confirmButton = {
                TextAction("Rename", enabled = !renaming, onClick = {
                    renaming = true
                    scope.launch {
                        renameError = vm.renameProfile(oldName, renameDraft)
                        renaming = false
                        if (renameError == null) renameTarget = null
                    }
                })
            },
            dismissButton = { TextAction("Cancel", enabled = !renaming, onClick = { renameTarget = null }) }
        )
    }

    if (showPicker) PresetPickerDialog(
        allPresets = allPresets, customPresets = customPresets, activePreset = activePreset,
        onSelect = { vm.selectPreset(it) }, onDelete = { vm.requestDelete(it) },
        onRename = { renameTarget = it; renameDraft = it; renameError = null; vm.closePresetPicker() },
        onNewProfile = { vm.openNewProfile() }, onDismiss = { vm.closePresetPicker() },
        onShare = { vm.openShare(it) }, onImport = { vm.openImport() },
        customised = genreOverrides.keys, onResetToDefault = { vm.resetToDefault(it) }
    )

    // ── Profile sharing ───────────────────────────────────────────────
    val shareDraft by vm.shareDraft.collectAsState()
    val shareMessage by vm.shareMessage.collectAsState()
    val showImportDialog by vm.showImportDialog.collectAsState()
    val importError by vm.importError.collectAsState()
    val importPreview by vm.importPreview.collectAsState()
    val importBusy by vm.importBusy.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportShareToFile(uri) }
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importFromFile(uri) }

    shareDraft?.let { draft ->
        ShareProfileDialog(
            draft = draft,
            onIncludeCorrection = vm::setShareIncludeCorrection,
            onIncludeBass = vm::setShareIncludeBass,
            onCopy = {
                clipboard.setText(AnnotatedString(draft.code))
                vm.onCodeCopied()
            },
            onShareText = {
                context.startActivity(ProfileShareIo.shareTextChooser(draft.name, draft.code))
                vm.closeShare()
            },
            onSaveFile = { saveFileLauncher.launch(draft.suggestedFileName) },
            onDismiss = vm::closeShare
        )
    }

    if (showImportDialog) {
        ImportProfileDialog(
            error = importError,
            busy = importBusy,
            onPasteFromClipboard = { clipboard.getText()?.text.orEmpty() },
            onImportText = vm::importFromText,
            onOpenFile = {
                openFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            onScanQr = {
                ProfileQrScanner.scan(context, onResult = vm::importFromText, onError = vm::showImportError)
            },
            onDismiss = vm::closeImport
        )
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            busy = importBusy,
            onConfirm = vm::confirmImport,
            onDismiss = vm::closeImport
        )
    }

    if (showNewProfile) NewProfileDialog(
        errorMessage = newProfileError,
        onConfirm = { vm.createNewProfile(it) },
        onDismiss = { vm.closeNewProfile() }
    )

    showDeleteConfirm?.let { name ->
        DeleteConfirmDialog(presetName = name, onConfirm = { vm.confirmDelete(name) }, onDismiss = { vm.cancelDelete() })
    }

    preciseBand?.let { index ->
        PreciseGainDialog(
            band = EqProfileManager.BAND_LABELS[index],
            gainDb = bands.getOrElse(index) { 0f },
            onChange = { vm.updateBand(index, it) },
            onDismiss = { preciseBand = null }
        )
    }

    Column(
        Modifier.fillMaxSize().background(c.background).padding(horizontal = EqSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.m)
    ) {
        // Header: title, Save (only with unsaved changes), overflow menu
        Row(Modifier.fillMaxWidth().padding(top = EqSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary,
                modifier = Modifier.weight(1f))
            if (hasUnsavedChanges) TonalButton("Save", onClick = { vm.saveToCurrentPreset() }, icon = EqIcons.Save)
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconAction(EqIcons.More, "More options", onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    containerColor = c.surfaceHighest) {
                    MenuItem("Save as new preset", EqIcons.Add) { menuOpen = false; vm.openNewProfile() }
                    MenuItem("Reset to saved", EqIcons.Reset) { menuOpen = false; vm.resetCurrentPreset() }
                    if (activePreset in genreOverrides) {
                        MenuItem("Reset $activePreset to default", EqIcons.Reset) {
                            menuOpen = false; vm.resetToDefault(activePreset)
                        }
                    }
                    MenuItem("Share", EqIcons.Share) { menuOpen = false; vm.openShare() }
                    MenuItem("Show sound layers", EqIcons.GraphicEq) { menuOpen = false; showLayers = true }
                    MenuItem("Import shared profile", EqIcons.Download) { menuOpen = false; vm.openImport() }
                }
            }
        }

        // Preset chip + EQ ⇄ Original comparison
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
            PillChip(
                text = activePreset,
                selected = activePreset != "Custom",
                trailingIcon = EqIcons.Expand,
                onClick = { vm.openPresetPicker() },
                modifier = Modifier.weight(1f, fill = false)
                    .semantics { contentDescription = "Preset: $activePreset. Choose preset" }
            )
            SegmentedToggle(
                options = listOf(false, true),
                selected = isBypassed,
                onSelect = { EqState.setBypassed(it) },
                label = { if (it) "Original" else "EQ" },
                modifier = Modifier.width(184.dp)
            )
        }

        deviceSourceLabel?.let {
            Text("Preset set by $it. Change it in Headphones > My devices.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        deviceAlwaysPrompt?.let { (device, preset) ->
            StatusBanner("Use $preset for $device from now on?", StatusKind.NEUTRAL,
                actionLabel = "Always", onAction = vm::useForDeviceAlways,
                onDismiss = vm::dismissDeviceAlwaysPrompt)
        }
        genreStrengthNote?.let { strength ->
            Text("Automatic EQ plays this curve at ${Math.round(strength * 100)}% genre strength. " +
                "Moving a slider switches to manual, at full strength.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }

        shareMessage?.let { message ->
            StatusBanner(message, StatusKind.NEUTRAL, onDismiss = { vm.dismissShareMessage() })
        }

        // Sliders on top of the live tone curve
        EqCard(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = EqSpacing.m, horizontal = EqSpacing.s)) {
            EqBandsPanel(
                bands = bands,
                dimmed = isBypassed,
                onChange = { index, gain -> vm.updateBand(index, gain) },
                onReadoutClick = { preciseBand = it },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (isBypassed) {
            Text("Comparing with the original sound. EQ is temporarily off.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(EqSpacing.s))
    }
}

/** Tone, correction, bass and the total actually applied (FEATURES_NEXT_PLAN.md §3). */
@Composable
private fun SoundLayersSheet(strength: Float?, onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    val tone by EqState.activeToneGains.collectAsState()
    val correction by EqState.currentHeadphoneCorrection.collectAsState()
    val bass by EqState.bassBoostLevel.collectAsState()
    val total by EqState.activeBandGains.collectAsState()
    val bypassed by EqState.isBypassed.collectAsState()
    val enabled by EqState.isEqEnabled.collectAsState()
    // The tone you hear: automatic genre EQ is scaled by genre strength.
    val heardTone = if (strength != null) GenreStrength.apply(tone, strength).toList() else tone.toList()
    val layers = SoundLayers.build(heardTone, correction.toList(), bass, total.toList())
    EqSheet(title = "Sound layers", subtitle = "What EQify adds up, band by band.", onDismiss = onDismiss) {
        LayeredCurve(layers = layers, height = 180.dp,
            description = "Total applied: ${SoundLayers.describe(total.toList())}")
        val notes = buildList {
            if (!enabled) add("EQ is off, so nothing is applied.")
            else if (bypassed) add("Comparing with the original sound: nothing is applied right now.")
            if (SoundLayers.isFlat(correction.toList())) add("No headphone correction is active.")
            if (strength != null) add("Tone includes genre strength (${Math.round(strength * 100)}%).")
            add("Total is the sum after output protection, which can lower boosts to prevent clipping.")
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        Text("Total: ${SoundLayers.describe(total.toList())}", style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary)
    }
}

@Composable
private fun MenuItem(text: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = EqifyTheme.colors.textPrimary) },
        leadingIcon = { EqIcon(icon, null, size = 20.dp) },
        onClick = onClick
    )
}

// ── Bands panel ───────────────────────────────────────────────────────
//
// Three aligned rows share the same eight weight(1f) columns (readouts,
// tracks, labels), so the curve behind the tracks lines up with every thumb
// at any font size.

/** Vertical inset of the thumb travel inside a track; the curve uses the same inset. */
private val TrackInset = 12.dp

@Composable
private fun EqBandsPanel(
    bands: List<Float>,
    dimmed: Boolean,
    onChange: (Int, Float) -> Unit,
    onReadoutClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = EqifyTheme.colors
    val labels = EqProfileManager.BAND_LABELS
    // Eight columns are narrow; at large system font sizes drop the decimal so
    // "+12.0" still fits (exact values remain in the tap-to-edit dialog).
    // The condensed data width fits more, so decimals are only dropped above 1.5×.
    val largeFont = LocalDensity.current.fontScale > 1.5f
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            labels.indices.forEach { i ->
                val gain = bands.getOrElse(i) { 0f }
                Box(
                    Modifier.weight(1f).heightIn(min = EqSpacing.touchTarget).clip(EqifyShapes.small)
                        .clickable(onClickLabel = "Set ${labels[i]} gain precisely") { onReadoutClick(i) },
                    contentAlignment = Alignment.Center
                ) {
                    ValueReadout(gain, "", signed = true, decimals = if (largeFont) 0 else 1,
                        color = if (gain == 0f) c.textMuted else c.textPrimary, textAlign = TextAlign.Center)
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = EqSpacing.xs)) {
            ToneCurve(
                gains = bands,
                height = null,
                contentDescription = null, // the band tracks carry the accessible values
                modifier = Modifier.fillMaxSize().padding(vertical = TrackInset)
                    .graphicsLayer { alpha = if (dimmed) 0.35f else 0.9f }
            )
            Row(Modifier.fillMaxSize()) {
                labels.indices.forEach { i ->
                    EqBandTrack(
                        gainDb = bands.getOrElse(i) { 0f },
                        band = labels[i],
                        dimmed = dimmed,
                        onChange = { onChange(i, it) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(label.removeSuffix("Hz"), style = MaterialTheme.typography.labelMedium.condensed(),
                    color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        Text("Hz", style = MaterialTheme.typography.labelSmall, color = c.textFaint,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

/**
 * One band's track. Bipolar: the fill grows from the 0 dB centre line toward
 * the thumb, so cuts read as cuts. Drag/step behaviour and the accessibility
 * set-progress action are unchanged from the previous slider (0.5 dB steps).
 */
@Composable
private fun EqBandTrack(
    gainDb: Float,
    band: String,
    dimmed: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentGain by rememberUpdatedState(gainDb)
    val quantize: (Float) -> Float = { ((it * 2).roundToInt() / 2f).coerceIn(-12f, 12f) }
    // Theme values must be read here: the draw lambda is not composable.
    val c = EqifyTheme.colors
    val trackColor = c.surfaceHighest
    val fillColor = if (dimmed) c.textFaint else c.accent
    val thumbColor = if (dimmed) c.textFaint else c.accent
    val ringColor = c.surfaceRaised
    val zeroTick = c.outlineStrong
    Canvas(
        modifier
            .semantics {
                contentDescription = "$band band"
                stateDescription = formatValue(gainDb, "dB", signed = true)
                progressBarRangeInfo = ProgressBarRangeInfo(gainDb, -12f..12f, 47)
                setProgress { requested ->
                    currentOnChange(quantize(requested))
                    true
                }
            }
            .pointerInput(Unit) {
                var dragGain = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragGain = currentGain },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val usableHeight = (size.height - 2 * TrackInset.toPx()).coerceAtLeast(1f)
                        dragGain = (dragGain - dragAmount * 24f / usableHeight).coerceIn(-12f, 12f)
                        currentOnChange(quantize(dragGain))
                    }
                )
            }
    ) {
        val x = size.width / 2f
        val inset = TrackInset.toPx()
        val top = inset
        val bottom = size.height - inset
        val mid = (top + bottom) / 2f
        val thumbY = top + (bottom - top) * ((12f - gainDb) / 24f).coerceIn(0f, 1f)
        val w = 4.dp.toPx()
        drawLine(trackColor, Offset(x, top), Offset(x, bottom), w, StrokeCap.Round)
        drawLine(zeroTick, Offset(x - 6.dp.toPx(), mid), Offset(x + 6.dp.toPx(), mid), 1.5.dp.toPx(), StrokeCap.Round)
        if (kotlin.math.abs(thumbY - mid) > 0.5f) drawLine(fillColor, Offset(x, mid), Offset(x, thumbY), w, StrokeCap.Round)
        drawCircle(ringColor, 11.dp.toPx(), Offset(x, thumbY))
        drawCircle(thumbColor, 8.dp.toPx(), Offset(x, thumbY))
    }
}

/** Tap a readout to type an exact value or nudge by 0.5 dB. Same behaviour as before. */
@Composable
private fun PreciseGainDialog(band: String, gainDb: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val quantize: (Float) -> Float = { ((it * 2).roundToInt() / 2f).coerceIn(-12f, 12f) }
    var draft by remember(gainDb) { mutableStateOf(String.format(java.util.Locale.ROOT, "%.1f", gainDb)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$band band", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Gain (−12 to +12 dB)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextAction("−0.5", { onChange((gainDb - .5f).coerceAtLeast(-12f)); onDismiss() })
                    TextAction("0", { onChange(0f); onDismiss() })
                    TextAction("+0.5", { onChange((gainDb + .5f).coerceAtMost(12f)); onDismiss() })
                }
            }
        },
        confirmButton = {
            TextAction("Apply", {
                draft.replace(',', '.').replace('−', '-').toFloatOrNull()?.let { onChange(quantize(it)) }
                onDismiss()
            })
        },
        dismissButton = { TextAction("Cancel", onDismiss) }
    )
}

// ── Preset picker ─────────────────────────────────────────────────────

@Composable
fun PresetPickerDialog(
    allPresets: List<String>, customPresets: List<String>, activePreset: String,
    onSelect: (String) -> Unit, onDelete: (String) -> Unit, onRename: (String) -> Unit,
    onNewProfile: () -> Unit, onDismiss: () -> Unit,
    onShare: (String) -> Unit = {}, onImport: () -> Unit = {},
    customised: Set<String> = emptySet(), onResetToDefault: (String) -> Unit = {}
) {
    EqSheet(
        title = "EQ presets",
        subtitle = "Choose a tone. Headphone correction and bass are added on top.",
        onDismiss = onDismiss,
        actions = {
            TextAction("Import", onImport, icon = EqIcons.Download)
            TonalButton("New preset", onNewProfile, icon = EqIcons.Add)
        }
    ) {
        val builtIn = allPresets.filter { candidate -> customPresets.none { it.equals(candidate, ignoreCase = true) } }
        if (customPresets.isNotEmpty()) {
            SectionHeader("My presets")
            EqCard(contentPadding = PaddingValues(0.dp)) {
                customPresets.forEachIndexed { i, name ->
                    if (i > 0) HorizontalDivider(color = EqifyTheme.colors.outline)
                    PresetRow(name, name == activePreset, isCustom = true, onSelect = { onSelect(name) },
                        onDelete = { onDelete(name) }, onRename = { onRename(name) }, onShare = { onShare(name) })
                }
            }
        }
        SectionHeader("Built-in")
        EqCard(contentPadding = PaddingValues(0.dp)) {
            builtIn.forEachIndexed { i, name ->
                if (i > 0) HorizontalDivider(color = EqifyTheme.colors.outline)
                val isCustomised = name in customised
                PresetRow(name, name == activePreset, isCustom = false, onSelect = { onSelect(name) },
                    onDelete = {}, onShare = { onShare(name) },
                    subtitle = if (isCustomised) "Customised" else null,
                    onResetToDefault = if (isCustomised) ({ onResetToDefault(name) }) else null)
            }
        }
    }
}

@Composable
private fun PresetRow(
    name: String, isActive: Boolean, isCustom: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    subtitle: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val c = EqifyTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    SettingRow(
        title = name,
        subtitle = subtitle,
        icon = if (isActive) EqIcons.CheckCircle else null,
        onClick = onSelect,
        modifier = Modifier.semantics { selected = isActive },
        trailing = {
            Box {
                if (isCustom) {
                    IconAction(EqIcons.More, "Options for $name", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surfaceHighest) {
                        if (onShare != null) MenuItem("Share", EqIcons.Share) { menuOpen = false; onShare() }
                        MenuItem("Rename", EqIcons.Edit) { menuOpen = false; onRename() }
                        MenuItem("Delete", EqIcons.Delete) { menuOpen = false; onDelete() }
                    }
                } else if (onResetToDefault != null) {
                    IconAction(EqIcons.More, "Options for $name", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surfaceHighest) {
                        if (onShare != null) MenuItem("Share", EqIcons.Share) { menuOpen = false; onShare() }
                        MenuItem("Reset to default", EqIcons.Reset) { menuOpen = false; onResetToDefault() }
                    }
                } else if (onShare != null) {
                    IconAction(EqIcons.Share, "Share $name", onShare)
                }
            }
        }
    )
}

// ── Short dialogs ─────────────────────────────────────────────────────

@Composable
fun NewProfileDialog(
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New preset", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
                Text("Give this EQ setting a name. Your current slider positions will be saved.",
                    style = MaterialTheme.typography.bodyMedium, color = EqifyTheme.colors.textMuted)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("e.g. Late Night, Gym, Study…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } }
                )
            }
        },
        confirmButton = { TextAction("Create", { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) },
        dismissButton = { TextAction("Cancel", onDismiss) }
    )
}

@Composable
fun DeleteConfirmDialog(presetName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Delete preset?",
        message = "Delete \"$presetName\"? This cannot be undone.",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

// ── Profile sharing sheets ────────────────────────────────────────────

/** Also used by HeadphonesScreen (correction-only shares); keep this signature stable. */
@Composable
fun ShareProfileDialog(
    draft: ShareDraft,
    onIncludeCorrection: (Boolean) -> Unit,
    onIncludeBass: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShareText: () -> Unit,
    onSaveFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    var showQr by remember { mutableStateOf(false) }
    EqSheet(
        title = "Share \"${draft.name}\"",
        subtitle = "Only EQ values and names, with no listening history or device details.",
        onDismiss = onDismiss,
        actions = {
            TextAction("Save file", onSaveFile)
            TonalButton("Copy", onCopy, icon = EqIcons.Copy)
            PrimaryButton("Share", onShareText, icon = EqIcons.Share)
        }
    ) {
        if (draft.isBuiltIn) StatusBanner("This is a built-in preset; everyone with EQify already has it.", StatusKind.NEUTRAL)
        if (draft.correctionOnly) {
            Text("Headphone correction only, with a flat tone. The receiver can save it to their imported corrections.",
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            draft.correction?.let { ToneCurve(it.gains, height = 88.dp) }
        } else {
            ToneCurve(draft.tone, height = 88.dp)
        }
        if (!draft.correctionOnly) draft.correction?.let { corr ->
            CheckRow(
                checked = draft.includeCorrection,
                onChange = onIncludeCorrection,
                label = "Include headphone correction",
                detail = corr.name + if (corr.kind == SharedCorrection.Kind.CUSTOM) " (imported)" else ""
            )
        } ?: Text("No headphone correction is loaded, so only the tone is shared.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        draft.bass?.let { bass ->
            CheckRow(
                checked = draft.includeBass,
                onChange = onIncludeBass,
                label = "Include bass boost",
                detail = "+${bass.toInt()} dB (the receiver chooses whether to apply it)"
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(if (showQr) "QR code" else "Profile code", modifier = Modifier.weight(1f))
            TextAction(if (showQr) "Show code" else "Show QR", { showQr = !showQr },
                icon = if (showQr) EqIcons.Copy else EqIcons.QrCode)
        }
        if (showQr) {
            ProfileQrImage(draft.link)
            Text("On another phone, use Scan QR in Import shared profile, or scan with the camera app to open it in EQify.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        } else {
            Surface(shape = EqifyShapes.small, color = c.surfaceHighest, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(draft.code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = c.textPrimary, modifier = Modifier.padding(EqSpacing.m))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportProfileDialog(
    error: String?,
    busy: Boolean,
    onPasteFromClipboard: () -> String,
    onImportText: (String) -> Unit,
    onOpenFile: () -> Unit,
    onScanQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    var text by remember { mutableStateOf("") }
    EqSheet(
        title = "Import shared profile",
        subtitle = "You'll see a preview before anything is saved.",
        onDismiss = onDismiss,
        dismissible = !busy,
        actions = {
            PrimaryButton("Preview", { onImportText(text) }, enabled = !busy && text.isNotBlank())
        }
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EqSpacing.s),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            PillChip("Paste", leadingIcon = EqIcons.Paste,
                onClick = { if (!busy) text = onPasteFromClipboard().take(EqProfileCodec.MAX_INPUT_CHARS) })
            PillChip("Scan QR", leadingIcon = EqIcons.QrScan, onClick = { if (!busy) onScanQr() })
            PillChip("Open file", leadingIcon = EqIcons.FileOpen, onClick = { if (!busy) onOpenFile() })
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(EqProfileCodec.MAX_INPUT_CHARS) },
            enabled = !busy,
            label = { Text("EQify code") },
            placeholder = { Text("EQIFY1:…") },
            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
            isError = error != null
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceHighest)
        error?.let { StatusBanner(it, StatusKind.ERROR) }
    }
}

@Composable
fun ImportPreviewDialog(
    preview: ImportPreview,
    busy: Boolean,
    onConfirm: (saveTone: Boolean, useCorrection: Boolean, applyBass: Boolean, useNow: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    val profile = preview.profile
    // A correction-only share has a flat tone; saving it as a preset would just add clutter.
    val flatTone = profile.tone.all { it == 0f }
    var saveTone by remember(preview) { mutableStateOf(!(flatTone && profile.correction != null)) }
    var useCorrection by remember(preview) { mutableStateOf(profile.correction != null) }
    var applyBass by remember(preview) { mutableStateOf(false) }
    val nothingSelected = !saveTone && !(useCorrection && profile.correction != null) &&
        !(applyBass && profile.bass != null)

    EqSheet(
        title = "Import \"${profile.name}\"",
        onDismiss = onDismiss,
        dismissible = !busy,
        actions = {
            TonalButton("Save", { onConfirm(saveTone, useCorrection, applyBass, false) }, enabled = !busy && !nothingSelected)
            PrimaryButton("Save & use", { onConfirm(saveTone, useCorrection, applyBass, true) }, enabled = !busy)
        }
    ) {
        ToneCurve(profile.tone, height = 96.dp)
        CheckRow(saveTone, { saveTone = it }, "Save tone as a preset",
            "Added to My presets. An existing preset is never replaced.", enabled = !busy)
        profile.correction?.let { corr ->
            val detail = when {
                corr.kind == SharedCorrection.Kind.DATASET && preview.datasetMatch != null ->
                    "${preview.datasetMatch} (found in your headphone catalog)"
                corr.kind == SharedCorrection.Kind.DATASET ->
                    "${corr.name}: not found in your catalog, so it will be saved as an imported correction"
                else -> "${corr.name}: saved to your imported corrections"
            }
            CheckRow(useCorrection, { useCorrection = it }, "Use headphone correction", detail, enabled = !busy)
        }
        profile.bass?.let { bass ->
            CheckRow(applyBass, { applyBass = it }, "Also set bass boost",
                "Shared level +${bass.toInt()} dB; changes your global Bass Booster setting.", enabled = !busy)
        }
        if (preview.autoGenreOn) {
            StatusBanner("Auto-Genre is on, so a tone you use now will be replaced by the genre preset on the next track.",
                StatusKind.WARNING)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceHighest)
    }
}

@Composable
private fun CheckRow(
    checked: Boolean, onChange: (Boolean) -> Unit, label: String, detail: String, enabled: Boolean = true
) {
    val c = EqifyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(EqifyShapes.medium)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = EqSpacing.touchTarget).padding(vertical = EqSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.padding(horizontal = EqSpacing.m))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) c.textPrimary else c.textFaint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

/**
 * Draws the QR modules directly (no Bitmap). Always black on white with the
 * spec's quiet zone, regardless of the theme, so scanners can read it.
 */
@Composable
private fun ProfileQrImage(code: String) {
    val matrix = remember(code) { runCatching { ProfileQrCode.matrix(code) }.getOrNull() }
    if (matrix == null) {
        StatusBanner("This code is too long to show as a QR code. Copy or share it as text instead.", StatusKind.ERROR)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(EqifyShapes.medium)
                .semantics { contentDescription = "QR code for this EQ profile" }
        ) {
            val modules = matrix.width + 2 * ProfileQrCode.QUIET_ZONE_MODULES
            val cell = size.minDimension / modules
            drawRect(Color.White)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (!matrix.get(x, y)) continue
                    drawRect(
                        Color.Black,
                        topLeft = Offset(
                            (x + ProfileQrCode.QUIET_ZONE_MODULES) * cell,
                            (y + ProfileQrCode.QUIET_ZONE_MODULES) * cell
                        ),
                        // Slight overlap avoids hairline gaps between modules.
                        size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }
    }
}


// ── Previews (phase 5): the eight narrow band columns at normal and large font sizes ──

@Composable
private fun BandsPreviewContent() = EQifyTheme {
    Box(Modifier.background(EqifyTheme.colors.background).padding(EqSpacing.l)) {
        EqCard(modifier = Modifier.height(360.dp), contentPadding = PaddingValues(vertical = EqSpacing.m, horizontal = EqSpacing.s)) {
            EqBandsPanel(
                bands = listOf(6f, 4f, -1.5f, -12f, 0f, 12f, 3.5f, -6f),
                dimmed = false,
                onChange = { _, _ -> },
                onReadoutClick = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "EQ bands · font 1×", widthDp = 360)
@Composable
private fun BandsFont100Preview() = BandsPreviewContent()

@androidx.compose.ui.tooling.preview.Preview(name = "EQ bands · font 1.5×", widthDp = 360, fontScale = 1.5f)
@Composable
private fun BandsFont150Preview() = BandsPreviewContent()

@androidx.compose.ui.tooling.preview.Preview(name = "EQ bands · font 2×", widthDp = 360, fontScale = 2f)
@Composable
private fun BandsFont200Preview() = BandsPreviewContent()
