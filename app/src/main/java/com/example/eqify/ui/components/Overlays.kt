package com.example.eqify.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.eqify.ui.theme.EqSpacing
import com.example.eqify.ui.theme.EqifyTheme

/**
 * Bottom sheet for longer flows (share, import, preview, correction, recent
 * tracks, preset picker). Always fully expanded; content scrolls; [actions]
 * stay pinned below the content. [dismissible] = false while work is running.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissible: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = EqifyTheme.colors
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || dismissible }
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        modifier = modifier,
        containerColor = c.surfaceRaised,
        contentColor = c.textPrimary,
        scrimColor = c.background.copy(alpha = 0.6f)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = EqSpacing.l).padding(bottom = EqSpacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                if (dismissible) IconAction(EqIcons.Close, "Close", onDismiss)
            }
            Spacer(Modifier.height(EqSpacing.m))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(EqSpacing.m),
                content = content
            )
            if (actions != null) {
                Spacer(Modifier.height(EqSpacing.m))
                // Wraps instead of overflowing at large system font sizes.
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EqSpacing.s, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(EqSpacing.s), content = actions)
            }
        }
    }
}

/** Short confirmation (delete/clear). [destructive] styles the confirm button as an error. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel"
) {
    val c = EqifyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        titleContentColor = c.textPrimary,
        textContentColor = c.textMuted,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextAction(confirmLabel, onConfirm, destructive = destructive) },
        dismissButton = { TextAction(dismissLabel, onDismiss) }
    )
}
