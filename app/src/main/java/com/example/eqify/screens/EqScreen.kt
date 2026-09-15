package com.example.eqify

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                ?: EqProfileManager.defaultProfiles[name]?.copyOf()
                ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()

            _activePreset.value = name
            _bands.value = rawGains.toList()
            _hasUnsavedChanges.value = false
            EqState.applyManualPreset(name, rawGains)
            repository.setLastPreset(name)
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

        if (isDefaultPreset(_activePreset.value)) {
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
        val reserved = trimmedName.equals("Custom", ignoreCase = true) ||
            EqProfileManager.defaultProfiles.keys.any { it.equals(trimmedName, ignoreCase = true) }
        val duplicate = customPresetNames.value.any { it.equals(trimmedName, ignoreCase = true) }
        if (trimmedName.isBlank()) {
            _newProfileError.value = "Enter a profile name."
            return
        }
        if (reserved || duplicate) {
            _newProfileError.value = "Choose a unique profile name."
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
        if (name == "Custom") { _showNewProfileDialog.value = true; return }
        if (isDefaultPreset(name)) return
        val gains = _bands.value.toFloatArray()
        customGainsCache[name] = gains
        _hasUnsavedChanges.value = false
        viewModelScope.launch {
            repository.saveCustomPreset(name, gains)
            repository.setLastPreset(name)
        }
        EqState.applyManualPreset(name, gains)
    }

    // ── Delete ────────────────────────────────────────────────────────

    fun requestDelete(name: String) { _showDeleteConfirm.value = name }

    fun confirmDelete(name: String) {
        customGainsCache.remove(name)
        viewModelScope.launch { repository.deleteCustomPreset(name) }
        if (_activePreset.value == name) selectPreset("Flat")
        _showDeleteConfirm.value = null
    }

    fun cancelDelete() { _showDeleteConfirm.value = null }

    suspend fun renameProfile(oldName: String, newName: String): String? {
        return try {
            repository.renameCustomPreset(oldName, newName)
            val name = newName.trim()
            customGainsCache.remove(oldName)?.let { customGainsCache[name] = it }
            if (_activePreset.value == oldName) _activePreset.value = name
            EqState.renamePreset(oldName, name)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Unable to rename profile."
        }
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
            ?: EqProfileManager.defaultProfiles[name]?.copyOf()
            ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
}

// ── EqScreen ──────────────────────────────────────────────────────────

@Composable
fun EqScreen(vm: EqScreenViewModel = viewModel()) {
    val bands             by vm.bands.collectAsState()
    val activePreset      by vm.activePreset.collectAsState()
    val showPicker        by vm.showPresetPicker.collectAsState()
    val showNewProfile    by vm.showNewProfileDialog.collectAsState()
    val newProfileError   by vm.newProfileError.collectAsState()
    val showDeleteConfirm by vm.showDeleteConfirm.collectAsState()
    val allPresets        by vm.allPresetNames.collectAsState()
    val customPresets     by vm.customPresetNames.collectAsState()
    val hasUnsavedChanges by vm.hasUnsavedChanges.collectAsState()
    val isBypassed        by EqState.isBypassed.collectAsState()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    renameTarget?.let { oldName ->
        AlertDialog(
            onDismissRequest = { if (!renaming) renameTarget = null },
            title = { Text("Rename profile") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it; renameError = null },
                    singleLine = true,
                    enabled = !renaming,
                    label = { Text("Profile name") },
                    isError = renameError != null,
                    supportingText = { renameError?.let { Text(it) } }
                )
            },
            confirmButton = {
                TextButton(enabled = !renaming, onClick = {
                    renaming = true
                    scope.launch {
                        renameError = vm.renameProfile(oldName, renameDraft)
                        renaming = false
                        if (renameError == null) renameTarget = null
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(enabled = !renaming, onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showPicker) PresetPickerDialog(
        allPresets = allPresets, customPresets = customPresets, activePreset = activePreset,
        onSelect = { vm.selectPreset(it) }, onDelete = { vm.requestDelete(it) },
        onRename = { renameTarget = it; renameDraft = it; renameError = null; vm.closePresetPicker() },
        onNewProfile = { vm.openNewProfile() }, onDismiss = { vm.closePresetPicker() }
    )

    if (showNewProfile) NewProfileDialog(
        errorMessage = newProfileError,
        onConfirm = { vm.createNewProfile(it) },
        onDismiss = { vm.closeNewProfile() }
    )

    showDeleteConfirm?.let { name ->
        DeleteConfirmDialog(presetName = name, onConfirm = { vm.confirmDelete(name) }, onDismiss = { vm.cancelDelete() })
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Equalizer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasUnsavedChanges) {
                    TextButton(onClick = { vm.saveToCurrentPreset() }) {
                        Text("Save", color = AccentPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { vm.resetCurrentPreset() }) {
                    Text("Reset", color = TextSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { EqState.setBypassed(!isBypassed) }) {
                    Text(
                        if (isBypassed) "Original" else "A/B",
                        color = if (isBypassed) TextPrimary else TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Preset selector row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (activePreset == "Custom") AccentPurple.copy(alpha = 0.08f) else AccentPurple.copy(alpha = 0.18f),
                modifier = Modifier.clickable { vm.openPresetPicker() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(activePreset, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (activePreset == "Custom") TextSecondary else AccentPurpleLight)
                    Text("▾", fontSize = 10.sp, color = if (activePreset == "Custom") TextSecondary else AccentPurpleLight)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp), color = Surface2Dark,
                modifier = Modifier.clickable { vm.openNewProfile() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("+", fontSize = 14.sp, color = AccentPurpleLight, fontWeight = FontWeight.Bold)
                    Text("New profile", fontSize = 11.sp, color = AccentPurpleLight)
                }
            }
        }

        // Sliders
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface2Dark)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EqProfileManager.BAND_LABELS.forEachIndexed { index, label ->
                    VerticalEqSlider(
                        modifier = Modifier.weight(1f),
                        gainDb    = bands.getOrElse(index) { 0f },
                        frequency = label,
                        onChange  = { vm.updateBand(index, it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────

@Composable
fun PresetPickerDialog(
    allPresets: List<String>, customPresets: List<String>, activePreset: String,
    onSelect: (String) -> Unit, onDelete: (String) -> Unit, onRename: (String) -> Unit,
    onNewProfile: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2Dark,
        title = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("EQ Presets", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                TextButton(onClick = onDismiss) { Text("✕", color = TextSecondary, fontSize = 14.sp) }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("DEFAULT", fontSize = 9.sp, color = TextSecondary, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                allPresets.filter { candidate ->
                    customPresets.none { it.equals(candidate, ignoreCase = true) }
                }.forEach { name ->
                    PresetRow(name, name == activePreset, false, { onSelect(name) }, {})
                }
                if (customPresets.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = AccentPurple.copy(alpha = 0.1f))
                    Spacer(Modifier.height(8.dp))
                    Text("MY PROFILES", fontSize = 9.sp, color = TextSecondary, letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    customPresets.forEach { name ->
                        PresetRow(name, name == activePreset, true, { onSelect(name) }, { onDelete(name) }, { onRename(name) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AccentPurple.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp), color = AccentPurple.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().clickable { onNewProfile() }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("+", fontSize = 18.sp, color = AccentPurpleLight, fontWeight = FontWeight.Bold)
                        Column {
                            Text("Create new profile", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentPurpleLight)
                            Text("Saves current EQ settings", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PresetRow(
    name: String, isActive: Boolean, isCustom: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (isActive) {
                    Surface(shape = RoundedCornerShape(50), color = AccentPurple) {
                        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(8.dp).background(Color.White, RoundedCornerShape(50)))
                        }
                    }
                } else {
                    Box(Modifier.size(18.dp).background(TextSecondary.copy(alpha = 0.2f), RoundedCornerShape(50)))
                }
            }
            Text(name, fontSize = 14.sp,
                color = if (isActive) TextPrimary else TextSecondary,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (isCustom) {
            TextButton(onClick = onRename, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("Rename", fontSize = 12.sp, color = AccentPurpleLight)
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("Delete", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
fun NewProfileDialog(
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2Dark,
        title = { Text("New Profile", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Text("Give this EQ setting a name. Your current slider positions will be saved.",
                    color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("e.g. Late Night, Gym, Study…", color = TextSecondary, fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPurple, unfocusedBorderColor = AccentPurple.copy(alpha = 0.3f),
                        cursorColor = AccentPurple, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
                errorMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create", color = AccentPurpleLight, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

@Composable
fun DeleteConfirmDialog(presetName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2Dark,
        title = { Text("Delete Profile", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text  = { Text("Delete \"$presetName\"? This cannot be undone.", color = TextSecondary, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

// ── Vertical EQ Slider ────────────────────────────────────────────────

@Composable
fun VerticalEqSlider(gainDb: Float, frequency: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentGain by rememberUpdatedState(gainDb)
    val quantize: (Float) -> Float = {
        ((it * 2).roundToInt() / 2f).coerceIn(-12f, 12f)
    }

    var showPreciseInput by remember { mutableStateOf(false) }
    var draft by remember(gainDb, showPreciseInput) { mutableStateOf("%.1f".format(gainDb)) }
    if (showPreciseInput) {
        AlertDialog(
            onDismissRequest = { showPreciseInput = false },
            title = { Text("$frequency band") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("Gain (-12 to +12 dB)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { onChange((gainDb - .5f).coerceAtLeast(-12f)); showPreciseInput = false }) { Text("-0.5") }
                        TextButton(onClick = { onChange(0f); showPreciseInput = false }) { Text("Zero") }
                        TextButton(onClick = { onChange((gainDb + .5f).coerceAtMost(12f)); showPreciseInput = false }) { Text("+0.5") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft.replace(',', '.').toFloatOrNull()?.let { onChange(quantize(it)) }
                    showPreciseInput = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showPreciseInput = false }) { Text("Cancel") } }
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxHeight()) {
        Text(
            "%+.1f".format(gainDb),
            fontSize = 10.sp,
            color = AccentPurpleLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { showPreciseInput = true }
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
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
                            val usableHeight = (size.height - 20.dp.toPx()).coerceAtLeast(1f)
                            dragGain = (dragGain - dragAmount * 24f / usableHeight).coerceIn(-12f, 12f)
                            currentOnChange(quantize(dragGain))
                        }
                    )
                }
        ) {
            val centerX = size.width / 2f
            val inset = 10.dp.toPx()
            val top = inset
            val bottom = size.height - inset
            val fraction = ((12f - gainDb) / 24f).coerceIn(0f, 1f)
            val thumbY = top + (bottom - top) * fraction

            drawLine(
                color = Color.White.copy(alpha = 0.12f),
                start = androidx.compose.ui.geometry.Offset(centerX, top),
                end = androidx.compose.ui.geometry.Offset(centerX, bottom),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = AccentPurple,
                start = androidx.compose.ui.geometry.Offset(centerX, thumbY),
                end = androidx.compose.ui.geometry.Offset(centerX, bottom),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = AccentPurpleLight,
                radius = 9.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(centerX, thumbY)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(frequency, fontSize = 9.sp, color = TextSecondary)
    }
}
