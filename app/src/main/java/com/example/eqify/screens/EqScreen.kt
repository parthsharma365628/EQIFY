package com.example.eqify

import android.app.Application
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class EqScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val defaultPresetNames = EqProfileManager.defaultProfiles.keys.sorted()

    val customPresetNames: StateFlow<List<String>> = repository.customPresetNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allPresetNames: StateFlow<List<String>> = customPresetNames
        .map { custom -> (defaultPresetNames + custom.sorted()).distinct() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultPresetNames)

    private val _activePreset = MutableStateFlow(EqState.tonePresetName.value)
    val activePreset: StateFlow<String> = _activePreset.asStateFlow()

    private val _bands = MutableStateFlow(EqState.baseToneGains.value.toList())
    val bands: StateFlow<List<Float>> = _bands.asStateFlow()

    private val customGainsCache = mutableMapOf<String, FloatArray>()

    private val _showPresetPicker = MutableStateFlow(false)
    val showPresetPicker: StateFlow<Boolean> = _showPresetPicker.asStateFlow()

    private val _showNewProfileDialog = MutableStateFlow(false)
    val showNewProfileDialog: StateFlow<Boolean> = _showNewProfileDialog.asStateFlow()

    private val _showDeleteConfirm = MutableStateFlow<String?>(null)
    val showDeleteConfirm: StateFlow<String?> = _showDeleteConfirm.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repository.loadAllCustomPresets()
            customGainsCache.putAll(saved)
        }
        viewModelScope.launch {
            EqState.baseToneGains.collect { arr ->
                _bands.value = arr.toList()
            }
        }
        viewModelScope.launch {
            EqState.tonePresetName.collect { name ->
                _activePreset.value = name
            }
        }
    }

    private fun resolvePresetGains(name: String): FloatArray {
        customGainsCache[name]?.let { return it.copyOf() }
        EqProfileManager.defaultProfiles[name]?.let { return it.copyOf() }
        EqProfileManager.defaultProfiles.entries.find { it.key.equals(name, ignoreCase = true) }
            ?.value?.let { return it.copyOf() }
        return EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
    }

    fun selectPreset(name: String) {
        _showPresetPicker.value = false
        val gains = resolvePresetGains(name)
        EqState.setBaseToneGains(gains)
        EqState.setTonePresetName(name)
        viewModelScope.launch {
            repository.setLastPreset(name)
        }
    }

    fun updateBand(index: Int, gain: Float) {
        val current = EqState.baseToneGains.value.copyOf()
        current[index] = gain
        EqState.setBaseToneGains(current)
        val presetLabel = _activePreset.value
        if (!isDefaultPreset(presetLabel) && presetLabel != "Custom") {
            customGainsCache[presetLabel] = current
            viewModelScope.launch {
                repository.saveCustomPreset(presetLabel, current)
            }
        } else {
            EqState.setTonePresetName("Custom")
        }
    }

    fun resetCurrentPreset() {
        val name = _activePreset.value
        val gains = resolvePresetGains(name)
        EqState.setBaseToneGains(gains)
        EqState.setTonePresetName(name)
    }

    fun createNewProfile(name: String) {
        if (name.isBlank()) return
        val gains = EqState.baseToneGains.value.copyOf()
        customGainsCache[name] = gains
        viewModelScope.launch {
            repository.saveCustomPreset(name, gains)
            repository.setLastPreset(name)
        }
        _showNewProfileDialog.value = false
        _showPresetPicker.value = false
        EqState.setBaseToneGains(gains)
        EqState.setTonePresetName(name)
    }

    fun saveToCurrentPreset() {
        val name = _activePreset.value
        if (name == "Custom") {
            _showNewProfileDialog.value = true
            return
        }
        val gains = EqState.baseToneGains.value.copyOf()
        customGainsCache[name] = gains
        viewModelScope.launch {
            repository.saveCustomPreset(name, gains)
        }
    }

    fun requestDelete(name: String) { _showDeleteConfirm.value = name }

    fun confirmDelete(name: String) {
        customGainsCache.remove(name)
        viewModelScope.launch { repository.deleteCustomPreset(name) }
        if (_activePreset.value == name) selectPreset("Flat")
        _showDeleteConfirm.value = null
    }

    fun cancelDelete() { _showDeleteConfirm.value = null }

    fun openPresetPicker()  { _showPresetPicker.value = true  }
    fun closePresetPicker() { _showPresetPicker.value = false }
    fun openNewProfile()    { _showNewProfileDialog.value = true  }
    fun closeNewProfile()   { _showNewProfileDialog.value = false }

    fun isDefaultPreset(name: String): Boolean =
        EqProfileManager.defaultProfiles.containsKey(name)
}

@Composable
fun EqScreen(vm: EqScreenViewModel = viewModel()) {
    val bands             by vm.bands.collectAsState()
    val activePreset      by vm.activePreset.collectAsState()
    val showPicker        by vm.showPresetPicker.collectAsState()
    val showNewProfile    by vm.showNewProfileDialog.collectAsState()
    val showDeleteConfirm by vm.showDeleteConfirm.collectAsState()
    val allPresets        by vm.allPresetNames.collectAsState()
    val customPresets     by vm.customPresetNames.collectAsState()

    if (showPicker) {
        PresetPickerDialog(
            allPresets    = allPresets,
            customPresets = customPresets,
            activePreset  = activePreset,
            onSelect      = { vm.selectPreset(it) },
            onDelete      = { vm.requestDelete(it) },
            onNewProfile  = { vm.openNewProfile() },
            onDismiss     = { vm.closePresetPicker() }
        )
    }

    if (showNewProfile) {
        NewProfileDialog(
            onConfirm = { vm.createNewProfile(it) },
            onDismiss = { vm.closeNewProfile() }
        )
    }

    showDeleteConfirm?.let { name ->
        DeleteConfirmDialog(
            presetName = name,
            onConfirm  = { vm.confirmDelete(name) },
            onDismiss  = { vm.cancelDelete() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text       = "Equalizer",
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                if (activePreset == "Custom") {
                    TextButton(onClick = { vm.saveToCurrentPreset() }) {
                        Text("Save", color = AccentPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { vm.resetCurrentPreset() }) {
                    Text("Reset", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Surface(
                shape    = RoundedCornerShape(20.dp),
                color    = if (activePreset == "Custom") AccentPurple.copy(alpha = 0.08f)
                else AccentPurple.copy(alpha = 0.18f),
                modifier = Modifier.clickable { vm.openPresetPicker() }
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text       = activePreset,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (activePreset == "Custom") TextSecondary else AccentPurpleLight
                    )
                    Text(
                        text     = "▾",
                        fontSize = 10.sp,
                        color    = if (activePreset == "Custom") TextSecondary else AccentPurpleLight
                    )
                }
            }

            Surface(
                shape    = RoundedCornerShape(20.dp),
                color    = Surface2Dark,
                modifier = Modifier.clickable { vm.openNewProfile() }
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("+", fontSize = 14.sp, color = AccentPurpleLight, fontWeight = FontWeight.Bold)
                    Text("New profile", fontSize = 11.sp, color = AccentPurpleLight)
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .weight(1f),
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface2Dark)
        ) {
            Row(
                modifier              = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EqProfileManager.BAND_LABELS.forEachIndexed { index, label ->
                    VerticalEqSlider(
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

@Composable
fun PresetPickerDialog(
    allPresets:    List<String>,
    customPresets: List<String>,
    activePreset:  String,
    onSelect:      (String) -> Unit,
    onDelete:      (String) -> Unit,
    onNewProfile:  () -> Unit,
    onDismiss:     () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2Dark,
        title = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("EQ Presets", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                TextButton(onClick = onDismiss) { Text("✕", color = TextSecondary, fontSize = 14.sp) }
            }
        },
        text = {
            Column {
                Text(
                    text = "DEFAULT", fontSize = 9.sp, color = TextSecondary,
                    letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 4.dp)
                )
                val defaultPresets = allPresets.filter { !customPresets.contains(it) }
                defaultPresets.forEach { name ->
                    PresetRow(
                        name = name, isActive = name == activePreset,
                        isCustom = false, onSelect = { onSelect(name) }, onDelete = {}
                    )
                }
                if (customPresets.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = AccentPurple.copy(alpha = 0.1f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "MY PROFILES", fontSize = 9.sp, color = TextSecondary,
                        letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 4.dp)
                    )
                    customPresets.forEach { name ->
                        PresetRow(
                            name = name, isActive = name == activePreset,
                            isCustom = true, onSelect = { onSelect(name) }, onDelete = { onDelete(name) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AccentPurple.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = AccentPurple.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().clickable { onNewProfile() }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
    onSelect: () -> Unit, onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier              = Modifier.weight(1f)
        ) {
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
            Text(
                text       = name,
                fontSize   = 14.sp,
                color      = if (isActive) TextPrimary else TextSecondary,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        if (isCustom) {
            TextButton(
                onClick        = onDelete,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text("🗑", fontSize = 14.sp, color = Color(0xFFFF5252))
            }
        }
    }
}

@Composable
fun NewProfileDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2Dark,
        title = { Text("New Profile", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "Give this EQ setting a name. Your current slider positions will be saved.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    placeholder   = { Text("e.g. Late Night, Gym, Study…", color = TextSecondary, fontSize = 12.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentPurple,
                        unfocusedBorderColor = AccentPurple.copy(alpha = 0.3f),
                        cursorColor          = AccentPurple,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary
                    )
                )
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
        onDismissRequest = onDismiss,
        containerColor   = Surface2Dark,
        title = { Text("Delete Profile", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text  = { Text("Delete \"$presetName\"? This cannot be undone.", color = TextSecondary, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

@Composable
fun VerticalEqSlider(gainDb: Float, frequency: String, onChange: (Float) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    val animatedGain by animateFloatAsState(
        targetValue   = gainDb,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "eq_$frequency"
    )
    val displayGain = if (isDragging) gainDb else animatedGain

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
        Text(
            text       = "${if (gainDb > 0) "+" else ""}${gainDb.toInt()}",
            fontSize   = 10.sp,
            color      = AccentPurpleLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            modifier         = Modifier.weight(1f).width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value         = displayGain,
                onValueChange = {
                    isDragging = true
                    onChange(((it * 2).roundToInt() / 2f).coerceIn(-12f, 12f))
                },
                onValueChangeFinished = { isDragging = false },
                valueRange    = -12f..12f,
                colors        = SliderDefaults.colors(
                    thumbColor         = AccentPurpleLight,
                    activeTrackColor   = AccentPurple,
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                ),
                modifier = Modifier
                    .requiredWidth(this.maxHeight)
                    .requiredHeight(this.maxWidth)
                    .graphicsLayer {
                        rotationZ       = -90f
                        transformOrigin = TransformOrigin.Center
                    }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = frequency, fontSize = 9.sp, color = TextSecondary)
    }
}