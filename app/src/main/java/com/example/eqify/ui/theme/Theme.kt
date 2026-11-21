package com.example.eqify.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Read colour roles in composables: `EqifyTheme.colors.accent`. */
object EqifyTheme {
    val colors: EqifyColors
        @Composable @ReadOnlyComposable get() = LocalEqifyColors.current
}

/**
 * App theme. [colors] is swappable at runtime (phase 2b adds the picker);
 * Material components follow it through the derived [ColorScheme].
 */
@Composable
fun EQifyTheme(
    colors: EqifyColors = EqifyPalettes.dark(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalEqifyColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = EqifyTypography,
            shapes = EqifyShapes,
            content = content
        )
    }
}

/** Maps our roles onto Material 3 so stock components (Switch, dialogs, fields) match. */
fun EqifyColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = onAccentContainer,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceHighest,
        onSurfaceVariant = textMuted,
        surfaceTint = Color.Transparent,
        surfaceDim = background,
        surfaceBright = surfaceHighest,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceHighest,
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        inversePrimary = accentContainer,
        outline = outlineStrong,
        outlineVariant = outline,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        scrim = Color.Black
    )
}
