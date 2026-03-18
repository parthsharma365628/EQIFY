package com.example.eqify

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentPurple = Color(0xFF7C6CFF)
val AccentPurpleLight = Color(0xFFA78BFA)
val NeonCyan = Color(0xFF00E5FF)
val BgDark = Color(0xFF0A0A0F)
val SurfaceDark = Color(0xFF12121A)
val Surface2Dark = Color(0xFF1A1A26)
val TextPrimary = Color(0xFFF0EEFF)
val TextSecondary = Color(0xFF9D9DBD)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    secondary = AccentPurpleLight,
    tertiary = NeonCyan,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun EQifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}