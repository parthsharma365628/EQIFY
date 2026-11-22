package com.example.eqify.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.eqify.ui.theme.EqSpacing
import com.example.eqify.ui.theme.EqifyShapes
import com.example.eqify.ui.theme.EqifyTheme
import com.example.eqify.ui.theme.PillShape
import com.example.eqify.ui.theme.condensed

// ── Cards and headers ─────────────────────────────────────────────────

/**
 * The one card style. Optional [title] row with a trailing [action] (e.g. an
 * IconAction or TextAction). Content gets the standard 16 dp padding unless
 * [contentPadding] is overridden (lists of rows usually pass 0).
 */
@Composable
fun EqCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(EqSpacing.l),
    content: @Composable ColumnScope.() -> Unit
) {
    val c = EqifyTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = EqifyShapes.large,
        color = c.surfaceRaised,
        contentColor = c.textPrimary
    ) {
        Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = EqSpacing.l, end = EqSpacing.s, top = EqSpacing.m)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary,
                        modifier = Modifier.weight(1f))
                    action?.invoke()
                }
            }
            Column(Modifier.padding(contentPadding)) { content() }
        }
    }
}

/**
 * Sentence-case title above a group of rows ("Imported corrections", "Sound").
 * No uppercase eyebrows (DESIGN_REVIEW.md M1); omit it when the content explains itself.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (accent) EqifyTheme.colors.accent else EqifyTheme.colors.textMuted,
        modifier = modifier.padding(horizontal = EqSpacing.xs, vertical = EqSpacing.s)
    )
}

// ── Rows ──────────────────────────────────────────────────────────────

/**
 * Base list row: optional leading icon, title + optional subtitle, optional
 * trailing slot. At least 56 dp tall (touch target + text). [destructive]
 * colours the title as an error (Clear …, Delete …).
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = EqifyTheme.colors
    val titleColor = when {
        !enabled -> c.textFaint
        destructive -> c.error
        else -> c.textPrimary
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = EqSpacing.l, vertical = EqSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EqSpacing.l)
    ) {
        if (icon != null) EqIcon(icon, contentDescription = null,
            tint = if (destructive && enabled) c.error else c.textMuted)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) c.textMuted else c.textFaint)
        }
        trailing?.invoke()
    }
}

/** A row whose whole surface toggles the switch (one accessible "switch" element). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
    )
}

/** A row showing the current value of a setting; tapping opens its chooser. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null
) {
    SettingRow(
        title = title, subtitle = subtitle, icon = icon, onClick = onClick, modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = EqifyTheme.colors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                EqIcon(EqIcons.Expand, contentDescription = null, size = 20.dp)
            }
        }
    )
}

// ── Chips ─────────────────────────────────────────────────────────────

/**
 * Pill-shaped label. [selected] uses the accent container; tappable when
 * [onClick] is set (then at least 32 dp tall inside a 48 dp touch area).
 */
@Composable
fun PillChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    val c = EqifyTheme.colors
    val bg by animateColorAsState(if (selected) c.accentContainer else c.surfaceHighest, tween(180), label = "chip-bg")
    val fg by animateColorAsState(if (selected) c.onAccentContainer else c.textPrimary, tween(180), label = "chip-fg")
    val content: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = EqSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leadingIcon != null) EqIcon(leadingIcon, null, tint = fg, size = 18.dp)
            Text(text, style = MaterialTheme.typography.labelMedium.condensed(), color = fg, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (trailingIcon != null) EqIcon(trailingIcon, null, tint = fg, size = 18.dp)
        }
    }
    // The clickable Surface reserves a 48 dp touch area without drawing the pill that tall.
    if (onClick != null) {
        Surface(onClick = onClick, shape = PillShape, color = bg, contentColor = fg, modifier = modifier, content = content)
    } else {
        Surface(shape = PillShape, color = bg, contentColor = fg, modifier = modifier, content = content)
    }
}

enum class StatusKind { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

/** Small status pill with a coloured dot: correction state, confidence, limiter state. */
@Composable
fun StatusChip(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    val dot: Color = when (kind) {
        StatusKind.NEUTRAL -> c.textFaint
        StatusKind.ACTIVE -> c.accent
        StatusKind.SUCCESS -> c.success
        StatusKind.WARNING -> c.warning
        StatusKind.ERROR -> c.error
    }
    Row(
        modifier.background(c.surfaceHighest, PillShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(dot, PillShape))
        Text(text, style = MaterialTheme.typography.labelMedium.condensed(),
            color = if (kind == StatusKind.ERROR) c.error else c.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
