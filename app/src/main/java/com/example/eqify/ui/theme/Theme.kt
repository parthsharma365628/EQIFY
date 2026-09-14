package com.example.eqify

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Existing token names retained so every screen shares the new palette.
val AccentPurple = Color(0xFF557B70)
val AccentPurpleLight = Color(0xFFB6CEC4)
val NeonCyan = Color(0xFFA0BDB1)
val BgDark = Color(0xFF101212)
val SurfaceDark = Color(0xFF171A19)
val Surface2Dark = Color(0xFF202423)
val TextPrimary = Color(0xFFEAEDEB)
val TextSecondary = Color(0xFFA4ADA8)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    secondary = AccentPurpleLight,
    tertiary = NeonCyan,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = Surface2Dark,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF646F69),
    outlineVariant = Color(0xFF343D38),
    primaryContainer = Color(0xFF30433B),
    onPrimaryContainer = TextPrimary,
    secondaryContainer = Color(0xFF30433B),
    onSecondaryContainer = TextPrimary,
    surfaceTint = Color.Transparent,
)

@Composable
fun EQifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
