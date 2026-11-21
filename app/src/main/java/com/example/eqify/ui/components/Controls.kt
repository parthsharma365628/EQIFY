package com.example.eqify.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.eqify.ui.theme.EqSpacing
import com.example.eqify.ui.theme.EqifyShapes
import com.example.eqify.ui.theme.EqifyTheme
import com.example.eqify.ui.theme.PillShape
import com.example.eqify.ui.theme.tabular

// ── Buttons ───────────────────────────────────────────────────────────

/** The screen's main action. Filled accent. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = EqSpacing.touchTarget),
        shape = EqifyShapes.medium) { ButtonContent(text, icon) }
}

/** Secondary but prominent action (e.g. Save while there are unsaved changes). */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = EqSpacing.touchTarget),
        shape = EqifyShapes.medium) { ButtonContent(text, icon) }
}

/** Low-emphasis text action. [destructive] uses the error colour. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true, destructive: Boolean = false, @DrawableRes icon: Int? = null) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = EqSpacing.touchTarget),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) EqifyTheme.colors.error else EqifyTheme.colors.accent
        )
    ) { ButtonContent(text, icon) }
}

/** Icon-only action. [contentDescription] is required: there is no visible label. */
@Composable
fun IconAction(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit,
               modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = EqifyTheme.colors.textMuted) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        EqIcon(icon, contentDescription, tint = if (enabled) tint else EqifyTheme.colors.textFaint)
    }
}

@Composable
private fun ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(EqSpacing.s))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

// ── Segmented toggle ──────────────────────────────────────────────────

/**
 * A pill of mutually exclusive options (bass Off/+3/+6/+9, EQ ⇄ Original).
 * Each segment is at least 48 dp tall and announced as a radio button.
 */
@Composable
fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = EqifyTheme.colors
    Row(
        modifier
            .clip(PillShape)
            .background(c.surfaceHighest)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            // Phase 5: 48 dp touch height per segment and a short colour fade on change.
            val bg by animateColorAsState(if (isSelected) c.accent else Color.Transparent, tween(180), label = "segment-bg")
            val fg by animateColorAsState(
                when {
                    !enabled -> c.textFaint
                    isSelected -> c.onAccent
                    else -> c.textMuted
                }, tween(180), label = "segment-fg"
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = EqSpacing.touchTarget)
                    .clip(PillShape)
                    .background(bg)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = EqSpacing.s),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Value readout ─────────────────────────────────────────────────────

/**
 * A number with its unit in fixed-width digits so it never jumps while
 * changing (e.g. "+3.5 dB", "1 kHz", "42 %"). [signed] forces a "+" for
 * positive values; [decimals] controls precision.
 */
@Composable
fun ValueReadout(
    value: Float,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    signed: Boolean = false,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = EqifyTheme.colors.textPrimary,
    textAlign: TextAlign? = null
) {
    Text(
        formatValue(value, unit, decimals, signed),
        style = style.tabular(),
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        maxLines = 1
    )
}

/** Pure formatting, shared with tests. A thin space keeps number and unit together. */
fun formatValue(value: Float, unit: String, decimals: Int = 1, signed: Boolean = false): String {
    val pattern = (if (signed) "%+." else "%.") + decimals + "f"
    // Avoid "-0.0" for tiny negatives.
    val v = if (kotlin.math.abs(value) < 0.5 * Math.pow(10.0, -decimals.toDouble())) 0f else value
    val number = String.format(java.util.Locale.ROOT, pattern, v)
    return if (unit.isEmpty()) number else "$number $unit"
}

// ── Status banner and empty state ─────────────────────────────────────

/**
 * Full-width message with an icon and optional action/dismiss. Replaces the
 * wired-headphone banner and the share/import/error message strips.
 */
@Composable
fun StatusBanner(
    message: String,
    kind: StatusKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val c = EqifyTheme.colors
    val (bg, fg, icon) = when (kind) {
        StatusKind.ERROR -> Triple(c.errorContainer, c.onErrorContainer, EqIcons.Error)
        StatusKind.WARNING -> Triple(c.surfaceHighest, c.textPrimary, EqIcons.Warning)
        StatusKind.SUCCESS -> Triple(c.surfaceHighest, c.textPrimary, EqIcons.CheckCircle)
        StatusKind.ACTIVE -> Triple(c.accentContainer, c.onAccentContainer, EqIcons.Info)
        StatusKind.NEUTRAL -> Triple(c.surfaceHighest, c.textPrimary, EqIcons.Info)
    }
    val iconTint = when (kind) {
        StatusKind.ERROR -> c.error
        StatusKind.WARNING -> c.warning
        StatusKind.SUCCESS -> c.success
        StatusKind.ACTIVE -> c.onAccentContainer
        StatusKind.NEUTRAL -> c.textMuted
    }
    Row(
        modifier.fillMaxWidth().clip(EqifyShapes.medium).background(bg)
            .padding(start = EqSpacing.m, end = EqSpacing.xs, top = EqSpacing.xs, bottom = EqSpacing.xs)
            .heightIn(min = EqSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)
    ) {
        EqIcon(icon, contentDescription = null, tint = iconTint, size = 20.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f)
            .padding(vertical = EqSpacing.s))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = fg)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onDismiss != null) IconAction(EqIcons.Close, "Dismiss", onDismiss, tint = fg)
    }
}

/** Centered placeholder for empty or waiting states. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val c = EqifyTheme.colors
    Column(
        modifier.fillMaxWidth().padding(EqSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EqSpacing.s)
    ) {
        Box(Modifier.size(56.dp).clip(PillShape).background(c.surfaceHighest), contentAlignment = Alignment.Center) {
            EqIcon(icon, contentDescription = null, tint = c.textMuted, size = 28.dp)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(EqSpacing.xs))
            TonalButton(actionLabel, onAction)
        }
    }
}

/** Fades between values (genre, status) instead of snapping. UI only. */
@Composable
fun <T> EqCrossfade(targetState: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "eqify-crossfade"
    ) { content(it) }
}
