package com.example.eqify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.eqify.ui.theme.*

/*
 * Android Studio previews of the component kit (UI_REDESIGN.md §5) in dark and
 * light. Open this file in Studio's Split/Design view. Not used at runtime.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitSample() {
    val c = EqifyTheme.colors
    var bass by remember { mutableStateOf("+3") }
    var ab by remember { mutableStateOf("EQ") }
    var on by remember { mutableStateOf(true) }
    Column(
        Modifier.background(c.background).padding(EqSpacing.screen).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.cardGap)
    ) {
        SectionHeader("Now playing", accent = true)
        EqCard(title = "Sound", action = { IconAction(EqIcons.More, "More options", {}) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s), verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
                PillChip("Hip-Hop", selected = true, trailingIcon = EqIcons.Expand, onClick = {})
                StatusChip("High confidence", StatusKind.SUCCESS)
            }
            Spacer(Modifier.height(EqSpacing.m))
            ToneCurve(listOf(6f, 4f, -1f, -1f, 2f, 1f, 0f, 1f), height = 72.dp)
            Spacer(Modifier.height(EqSpacing.m))
            SegmentedToggle(listOf("Off", "+3", "+6", "+9"), bass, { bass = it }, { it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(EqSpacing.s))
            SegmentedToggle(listOf("EQ", "Original"), ab, { ab = it }, { it }, Modifier.fillMaxWidth())
        }
        EqCard(contentPadding = PaddingValues(0.dp)) {
            SwitchRow("Auto EQ", on, { on = it }, subtitle = "Applying genre profiles automatically", icon = EqIcons.Equalizer)
            HorizontalDivider(color = c.outline)
            ValueRow("Output protection", "Balanced", {}, icon = EqIcons.Tune)
            HorizontalDivider(color = c.outline)
            SettingRow("Headphones", subtitle = "Sony WH-1000XM5", icon = EqIcons.Headphones, onClick = {},
                trailing = { StatusChip("Cached", StatusKind.ACTIVE) })
            HorizontalDivider(color = c.outline)
            SettingRow("Clear genre history", icon = EqIcons.Delete, destructive = true, onClick = {})
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.l)) {
            ValueReadout(3.5f, "dB", signed = true)
            ValueReadout(-12f, "dB", signed = true)
            ValueReadout(0f, "dB", signed = true)
            ValueReadout(42f, "%", decimals = 0)
        }
        ToneCurve(listOf(5f, 3f, -1f, -2f, 1f, 3f, 4f, 2f), style = ToneCurveStyle.BARS, height = 48.dp)
        StatusBanner("Wired headphones connected. Which model?", StatusKind.ACTIVE, actionLabel = "Choose",
            onAction = {}, onDismiss = {})
        StatusBanner("Gain values could not be downloaded.", StatusKind.ERROR, actionLabel = "Retry", onAction = {})
        StatusBanner("Auto-Genre will replace this tone on the next track.", StatusKind.WARNING)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s), verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            PrimaryButton("Save & use", {}, icon = EqIcons.Check)
            TonalButton("Save", {})
            TextAction("Cancel", {})
        }
        EmptyState(EqIcons.Music, "Waiting for music", body = "Play something in Spotify, YouTube Music or Apple Music.",
            actionLabel = "Allow notification access", onAction = {})
    }
}

@Preview(name = "Kit · Sage dark", widthDp = 380, heightDp = 1500)
@Composable
private fun KitDarkPreview() = EQifyTheme(EqifyPalettes.dark()) { KitSample() }

@Preview(name = "Kit · Sage light", widthDp = 380, heightDp = 1500)
@Composable
private fun KitLightPreview() = EQifyTheme(EqifyPalettes.light()) { KitSample() }

@Preview(name = "Kit · Amber pure black", widthDp = 380, heightDp = 1500)
@Composable
private fun KitAmberBlackPreview() = EQifyTheme(EqifyPalettes.dark(ThemeAccent.AMBER, pureBlack = true)) { KitSample() }

@Preview(name = "Kit · Ocean light", widthDp = 380, heightDp = 1500)
@Composable
private fun KitOceanLightPreview() = EQifyTheme(EqifyPalettes.light(ThemeAccent.OCEAN)) { KitSample() }

@Preview(name = "Icons", widthDp = 380)
@Composable
private fun IconsPreview() = EQifyTheme {
    val all = listOf(EqIcons.Close, EqIcons.Add, EqIcons.Check, EqIcons.CheckCircle, EqIcons.Star, EqIcons.StarOutline,
        EqIcons.Back, EqIcons.Share, EqIcons.Search, EqIcons.Delete, EqIcons.Edit, EqIcons.More, EqIcons.Expand,
        EqIcons.Info, EqIcons.Warning, EqIcons.Error, EqIcons.Settings, EqIcons.Play, EqIcons.Headphones,
        EqIcons.Equalizer, EqIcons.Tune, EqIcons.GraphicEq, EqIcons.QrCode, EqIcons.QrScan, EqIcons.FileOpen,
        EqIcons.Bluetooth, EqIcons.Copy, EqIcons.Paste, EqIcons.Refresh, EqIcons.Palette, EqIcons.Music,
        EqIcons.Home, EqIcons.Save, EqIcons.Reset, EqIcons.Download)
    Column(Modifier.background(EqifyTheme.colors.background).padding(EqSpacing.l)) {
        all.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(EqSpacing.l)) {
                row.forEach { EqIcon(it, null, tint = EqifyTheme.colors.textPrimary) }
            }
            Spacer(Modifier.height(EqSpacing.m))
        }
    }
}

// ── Large system font sizes (phase 5) ─────────────────────────────────
// Android lets users scale text up to 2×. Rows, chips and toggles must wrap
// or ellipsize, never overlap.

@Preview(name = "Kit · font 1.5×", widthDp = 360, heightDp = 1800, fontScale = 1.5f)
@Composable
private fun KitFont150Preview() = EQifyTheme(EqifyPalettes.dark()) { KitSample() }

@Preview(name = "Kit · font 2×", widthDp = 360, heightDp = 2200, fontScale = 2f)
@Composable
private fun KitFont200Preview() = EQifyTheme(EqifyPalettes.light()) { KitSample() }
