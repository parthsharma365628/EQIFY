package com.liteplane.eqify

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liteplane.eqify.ui.components.*
import com.liteplane.eqify.ui.theme.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ── My devices (FEATURES_NEXT_PLAN.md §4) ─────────────────────────────
//
// Design rules: everything inherits by default; at most four settings per
// device; only customised values are highlighted (each with Reset); all device
// settings live here and nowhere else.

data class DeviceRow(
    val key: String,
    val kind: AudioOutputKind,
    val label: String,
    val summary: String,
    val connected: Boolean,
    val profile: DeviceProfile,
    val choice: DeviceChoice?
)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as EqifyApplication).repository

    val presetNames: StateFlow<List<String>> = repository.customPresetNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val devices: StateFlow<List<DeviceRow>> = combine(
        repository.deviceProfiles, repository.deviceChoices, DeviceSoundCoordinator.state
    ) { profiles, choices, current ->
        // The phone speaker and wired headphones are always listed.
        val all = profiles.toMutableMap()
        all.putIfAbsent("speaker", DeviceProfile(label = "Phone speaker", kind = AudioOutputKind.SPEAKER))
        all.putIfAbsent("wired", DeviceProfile(label = "Wired headphones", kind = AudioOutputKind.WIRED))
        all.map { (key, p) ->
            DeviceRow(
                key = key, kind = p.kind,
                label = p.label ?: DeviceProfiles.defaultLabel(key, p.kind),
                summary = DeviceProfiles.summary(p, choices[key]),
                connected = key == current.deviceKey,
                profile = p, choice = choices[key]
            )
        }.sortedWith(compareByDescending<DeviceRow> { it.connected }.thenByDescending { it.profile.lastSeenMillis }
            .thenBy { it.label.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun update(row: DeviceRow, transform: (DeviceProfile) -> DeviceProfile) {
        viewModelScope.launch { repository.updateDeviceProfile(row.key, row.kind, transform) }
    }

    fun setSound(row: DeviceRow, sound: DeviceSound?) {
        update(row) { it.copy(sound = sound) }
        // A manual change on the connected device would hide the new setting: end it.
        if (row.connected && EqState.manualOverrideActive.value) EqState.clearManualOverride()
    }
    fun setBass(row: DeviceRow, bass: Float?) = update(row) { it.copy(bassDb = bass?.let(DeviceProfiles::normaliseBass)) }
    fun setStrength(row: DeviceRow, value: Float?) = update(row) { it.copy(genreStrength = value?.let(GenreStrength::normalise)) }
    fun rename(row: DeviceRow, name: String) = update(row) { it.copy(label = name.trim().take(40).ifBlank { null }) }

    fun setNotHeadphones(row: DeviceRow, notHeadphones: Boolean) {
        viewModelScope.launch {
            repository.setDeviceChoice(row.key, if (notHeadphones) DeviceChoice.NotHeadphones else null)
        }
    }

    fun forget(row: DeviceRow) {
        viewModelScope.launch { repository.forgetDevice(row.key) }
    }
}

/** List shown at the top of the Headphones tab. */
@Composable
fun MyDevicesSection(devices: List<DeviceRow>, onOpen: (DeviceRow) -> Unit) {
    val c = EqifyTheme.colors
    Column {
        SectionHeader("My devices")
        EqCard(contentPadding = PaddingValues(0.dp)) {
            devices.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = c.outline)
                SettingRow(
                    title = row.label,
                    subtitle = row.summary,
                    icon = when (row.kind) {
                        AudioOutputKind.SPEAKER -> EqIcons.Music
                        AudioOutputKind.BLUETOOTH -> EqIcons.Bluetooth
                        else -> EqIcons.Headphones
                    },
                    onClick = { onOpen(row) },
                    trailing = {
                        if (row.connected) StatusChip("Connected", StatusKind.ACTIVE)
                        else EqIcon(EqIcons.Expand, null, size = 20.dp)
                    }
                )
            }
        }
    }
}

/** One device's settings. Every value starts as "Default"; customised ones get Reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceSheet(row: DeviceRow, presets: List<String>, vm: DevicesViewModel, onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    val p = row.profile
    var renaming by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    if (renaming) {
        var draft by remember { mutableStateOf(row.label) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename device", style = MaterialTheme.typography.titleMedium) },
            text = { OutlinedTextField(draft, { draft = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextAction("Save", { vm.rename(row, draft); renaming = false }) },
            dismissButton = { TextAction("Cancel", { renaming = false }) }
        )
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "Forget ${row.label}?",
            message = "Its sound settings and remembered headphone correction are removed. " +
                "It will be listed again the next time it connects.",
            confirmLabel = "Forget", destructive = true,
            onConfirm = { confirmForget = false; vm.forget(row); onDismiss() },
            onDismiss = { confirmForget = false }
        )
    }

    EqSheet(
        title = row.label,
        subtitle = if (row.connected) "Connected now. Changes apply immediately." else "Applies the next time it connects.",
        onDismiss = onDismiss,
        actions = {
            TextAction("Rename", { renaming = true }, icon = EqIcons.Edit)
            if (row.kind != AudioOutputKind.SPEAKER && row.kind != AudioOutputKind.WIRED)
                TextAction("Forget device", { confirmForget = true }, icon = EqIcons.Delete, destructive = true)
        }
    ) {
        if (row.connected) {
            val tone by EqState.activeToneGains.collectAsState()
            val correction by EqState.currentHeadphoneCorrection.collectAsState()
            val bass by EqState.bassBoostLevel.collectAsState()
            val total by EqState.activeBandGains.collectAsState()
            LayeredCurve(
                layers = SoundLayers.build(tone.toList(), correction.toList(), bass, total.toList()),
                height = 110.dp,
                description = "Sound on ${row.label}: ${SoundLayers.describe(total.toList())}"
            )
        }

        // 1. Headphone correction
        DeviceSection("Headphone correction", correctionValue(row), customised = row.choice != null,
            onReset = if (row.choice != null && row.kind != AudioOutputKind.SPEAKER) ({ vm.setNotHeadphones(row, false) }) else null) {
            when (row.kind) {
                AudioOutputKind.SPEAKER -> Hint("The phone speaker never gets a headphone correction.")
                else -> {
                    Hint("To choose a model, connect this device and pick it from the list on the Headphones tab. EQify remembers it.")
                    if (row.kind != AudioOutputKind.WIRED) {
                        SwitchRow(
                            title = "Not headphones",
                            subtitle = "Car, speaker or TV: never apply a headphone correction",
                            checked = row.choice == DeviceChoice.NotHeadphones,
                            onCheckedChange = { vm.setNotHeadphones(row, it) }
                        )
                    }
                }
            }
        }

        // 2. Sound
        val sound = p.sound
        DeviceSection("Sound", soundValue(sound), customised = sound != null,
            onReset = if (sound != null) ({ vm.setSound(row, null) }) else null) {
            Choice("Same as default", "Uses your normal settings", sound == null) { vm.setSound(row, null) }
            Choice("Follow genre automatically", "Genre EQ on this device, even if it's off elsewhere",
                sound == DeviceSound.FollowGenre) { vm.setSound(row, DeviceSound.FollowGenre) }
            var pickPreset by remember(row.key) { mutableStateOf(sound is DeviceSound.Preset) }
            Choice("Always use a preset", (sound as? DeviceSound.Preset)?.let { "Using ${it.name}" }
                ?: "Pick one below", sound is DeviceSound.Preset || pickPreset) { pickPreset = true }
            if (sound is DeviceSound.Preset || pickPreset) {
                val all = EqProfileManager.defaultProfiles.keys.toList() + presets
                FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(EqSpacing.xs),
                    modifier = Modifier.padding(start = EqSpacing.xl)) {
                    all.forEach { name ->
                        PillChip(name, selected = (sound as? DeviceSound.Preset)?.name == name,
                            onClick = { vm.setSound(row, DeviceSound.Preset(name)) })
                    }
                }
            }
        }

        // 3. Bass
        val bass = p.bassDb
        DeviceSection("Bass", bass?.let { if (it <= 0f) "Off (for this device)" else "+${it.toInt()} dB (for this device)" } ?: "Default",
            customised = bass != null, onReset = if (bass != null) ({ vm.setBass(row, null) }) else null) {
            SegmentedToggle(
                options = listOf<Float?>(null) + DeviceProfiles.BASS_STEPS,
                selected = bass,
                onSelect = { vm.setBass(row, it) },
                label = { when (it) { null -> "Default"; 0f -> "Off"; else -> "+${it.toInt()}" } },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 4. Genre strength (only meaningful when genre EQ can run on this device)
        if (sound !is DeviceSound.Preset) {
            val strength = p.genreStrength
            DeviceSection("Genre strength",
                strength?.let { "${GenreStrength.label(it)} (for this device)" } ?: "Default",
                customised = strength != null, onReset = if (strength != null) ({ vm.setStrength(row, null) }) else null) {
                SwitchRow(
                    title = "Own strength for this device",
                    checked = strength != null,
                    onCheckedChange = { on -> vm.setStrength(row, if (on) GenreStrength.DEFAULT else null) }
                )
                if (strength != null) {
                    var dragging by remember(row.key) { mutableStateOf<Float?>(null) }
                    val shown = dragging ?: strength
                    Slider(
                        value = shown,
                        onValueChange = { dragging = GenreStrength.normalise(it) },
                        onValueChangeFinished = { dragging?.let { vm.setStrength(row, it) }; dragging = null },
                        valueRange = GenreStrength.MIN..GenreStrength.MAX,
                        steps = Math.round((GenreStrength.MAX - GenreStrength.MIN) / GenreStrength.STEP) - 1,
                        modifier = Modifier.semantics {
                            contentDescription = "Genre strength for ${row.label}, ${Math.round(shown * 100)} percent"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(
    title: String, value: String, customised: Boolean, onReset: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = EqifyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
        HorizontalDivider(color = c.outline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = EqSpacing.s)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    color = if (customised) c.accent else c.textMuted)
            }
            if (onReset != null) TextAction("Reset", onReset, icon = EqIcons.Reset)
        }
        content()
    }
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    SettingRow(
        title = title, subtitle = subtitle,
        icon = if (selected) EqIcons.CheckCircle else null,
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected }
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = EqifyTheme.colors.textMuted)
}

private fun correctionValue(row: DeviceRow): String = when {
    row.kind == AudioOutputKind.SPEAKER -> "None on the phone speaker"
    else -> when (val ch = row.choice) {
        is DeviceChoice.Dataset -> ch.name
        is DeviceChoice.Custom -> "Imported correction"
        DeviceChoice.NotHeadphones -> "None (not headphones)"
        null -> "Default (your current selection)"
    }
}

private fun soundValue(sound: DeviceSound?): String = when (sound) {
    null -> "Default"
    DeviceSound.FollowGenre -> "Follows genre (for this device)"
    is DeviceSound.Preset -> "${sound.name} preset (for this device)"
}
