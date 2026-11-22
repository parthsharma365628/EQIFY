package com.example.eqify

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.eqify.ui.theme.*

/**
 * Settings → Appearance (UI_REDESIGN.md §4.6): mode, accent swatches (+ Dynamic),
 * pure black, and a live preview. Every change applies immediately via ThemeStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    settings: ThemeSettings,
    onMode: (ThemeMode) -> Unit,
    onAccent: (ThemeAccent) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onPureBlack: (Boolean) -> Unit
) {
    val colors = EqifyTheme.colors
    Column(Modifier.fillMaxWidth().padding(EqSpacing.l), verticalArrangement = Arrangement.spacedBy(EqSpacing.l)) {

        // Mode
        Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            Text("Mode", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { onMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.accentContainer,
                            activeContentColor = colors.onAccentContainer,
                            inactiveContainerColor = colors.surfaceRaised,
                            inactiveContentColor = colors.textMuted,
                            activeBorderColor = colors.outline,
                            inactiveBorderColor = colors.outline
                        )
                    ) { Text(mode.label, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }

        // Accent
        Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            Text("Accent colour", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)
            ) {
                ThemeAccent.entries.forEach { accent ->
                    val swatch = if (colors.isDark) EqifyPalettes.dark(accent).accent else EqifyPalettes.light(accent).accent
                    AccentSwatch(
                        label = accent.label,
                        selected = !settings.dynamic && settings.accent == accent,
                        fill = Brush.linearGradient(listOf(swatch, swatch)),
                        onClick = { onAccent(accent) }
                    )
                }
                if (supportsDynamicColor) {
                    AccentSwatch(
                        label = "Dynamic",
                        selected = settings.dynamic,
                        // Hint of "comes from your wallpaper": a blend of three accents.
                        fill = Brush.sweepGradient(listOf(
                            EqifyPalettes.dark(ThemeAccent.OCEAN).accent,
                            EqifyPalettes.dark(ThemeAccent.ROSE).accent,
                            EqifyPalettes.dark(ThemeAccent.AMBER).accent,
                            EqifyPalettes.dark(ThemeAccent.SAGE).accent,
                            EqifyPalettes.dark(ThemeAccent.OCEAN).accent
                        )),
                        onClick = { onDynamic(true) }
                    )
                }
            }
            if (settings.dynamic) {
                Text("Colours follow your wallpaper (Android 12+).",
                    style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }

        // Pure black (dark mode only; hidden when the effective theme is light)
        if (colors.isDark) {
            Row(
                Modifier.fillMaxWidth().clip(EqifyShapes.medium)
                    .selectable(selected = settings.pureBlack, role = Role.Switch) { onPureBlack(!settings.pureBlack) }
                    .heightIn(min = EqSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pure black", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text("True-black background for OLED screens", style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted)
                }
                Switch(checked = settings.pureBlack, onCheckedChange = onPureBlack)
            }
        }

        ThemePreviewCard()
    }
}

@Composable
private fun AccentSwatch(label: String, selected: Boolean, fill: Brush, onClick: () -> Unit) {
    val colors = EqifyTheme.colors
    Column(
        Modifier
            .width(56.dp)
            .clip(EqifyShapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = EqSpacing.xs)
            .semantics { contentDescription = "$label accent" + if (selected) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(BorderStroke(2.dp, if (selected) colors.textPrimary else Color.Transparent), PillShape)
                .padding(4.dp)
                .clip(PillShape)
                .background(fill),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(PillShape).background(colors.background))
        }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.textPrimary else colors.textMuted)
    }
}

/** A small sample of real UI in the current theme, so a choice can be judged at a glance. */
@Composable
private fun ThemePreviewCard() {
    val c = EqifyTheme.colors
    var sample by remember { mutableFloatStateOf(0.65f) }
    var switchOn by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().clip(EqifyShapes.large).background(c.background)
            .border(1.dp, c.outline, EqifyShapes.large).padding(EqSpacing.l),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.m)
    ) {
        Text("Preview", style = MaterialTheme.typography.titleSmall, color = c.textMuted)
        Column(
            Modifier.fillMaxWidth().clip(EqifyShapes.large).background(c.surfaceRaised).padding(EqSpacing.l),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Song title", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Artist", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
                Switch(checked = switchOn, onCheckedChange = { switchOn = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
                Surface(shape = PillShape, color = c.accentContainer) {
                    Text("Pop", style = MaterialTheme.typography.labelMedium, color = c.onAccentContainer,
                        modifier = Modifier.padding(horizontal = EqSpacing.m, vertical = EqSpacing.xs))
                }
                Surface(shape = PillShape, color = c.surfaceHighest) {
                    Text("High confidence", style = MaterialTheme.typography.labelMedium, color = c.success,
                        modifier = Modifier.padding(horizontal = EqSpacing.m, vertical = EqSpacing.xs))
                }
            }
            PreviewBars()
            Slider(value = sample, onValueChange = { sample = it })
            Row(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
                Button(onClick = {}) { Text("Save", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(onClick = {}) { Text("Reset", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun PreviewBars() {
    val accent = EqifyTheme.colors.accent
    val baseline = EqifyTheme.colors.outline
    val gains = listOf(5f, 3f, -1f, -2f, 1f, 3f, 4f, 2f)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val mid = size.height / 2f
        val slot = size.width / gains.size
        drawLine(baseline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        gains.forEachIndexed { i, g ->
            val x = slot * i + slot / 2f
            drawLine(accent, Offset(x, mid), Offset(x, mid - g / 12f * (mid - 2.dp.toPx())),
                (slot * 0.4f).coerceAtMost(10.dp.toPx()), StrokeCap.Round)
        }
    }
}
