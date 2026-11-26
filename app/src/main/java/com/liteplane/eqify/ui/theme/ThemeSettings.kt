package com.liteplane.eqify.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

/** What the user picked in Settings → Appearance (UI_REDESIGN.md §4.6). */
@Immutable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: ThemeAccent = ThemeAccent.SAGE,
    /** Wallpaper colours (Android 12+). When on, [accent] is kept but unused. */
    val dynamic: Boolean = false,
    /** True-black background; dark mode only. */
    val pureBlack: Boolean = false
)

val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Single source of truth for the theme. Stored in SharedPreferences so it can
 * be read synchronously before the first frame (no wrong-colour flash); the
 * StateFlow makes changes apply live without restarting.
 */
object ThemeStore {
    private const val PREFS = "eqify_theme"
    private val _settings = MutableStateFlow(ThemeSettings())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    /** Call once from Application.onCreate. Unknown stored values fall back to defaults. */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = ThemeSettings(
            mode = p.getString("mode", null)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.DARK,
            accent = p.getString("accent", null)?.let { v -> ThemeAccent.entries.firstOrNull { it.name == v } } ?: ThemeAccent.SAGE,
            dynamic = p.getBoolean("dynamic", false) && supportsDynamicColor,
            pureBlack = p.getBoolean("pure_black", false)
        )
    }

    fun update(context: Context, transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_settings.value).let { if (!supportsDynamicColor) it.copy(dynamic = false) else it }
        _settings.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", next.mode.name)
            .putString("accent", next.accent.name)
            .putBoolean("dynamic", next.dynamic)
            .putBoolean("pure_black", next.pureBlack)
            .apply()
    }
}

/** Resolves the user's choice plus the system dark setting to concrete colours. */
@Composable
fun rememberEqifyColors(settings: ThemeSettings): EqifyColors {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val context = LocalContext.current
    return remember(settings, dark) {
        if (settings.dynamic && supportsDynamicColor) {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            fromDynamic(scheme, dark, settings.pureBlack && dark)
        } else if (dark) {
            EqifyPalettes.dark(settings.accent, settings.pureBlack)
        } else {
            EqifyPalettes.light(settings.accent)
        }
    }
}

/**
 * Maps a wallpaper-derived Material scheme onto our roles. Material's tonal
 * palettes guarantee contrast for its own pairs; status colours stay ours so
 * their meaning never changes.
 */
private fun fromDynamic(s: ColorScheme, dark: Boolean, pureBlack: Boolean): EqifyColors {
    val fixed = if (dark) EqifyPalettes.dark() else EqifyPalettes.light()
    return EqifyColors(
        isDark = dark,
        background = if (pureBlack) androidx.compose.ui.graphics.Color.Black else s.background,
        surface = s.surfaceContainerLow,
        surfaceRaised = s.surfaceContainerHigh,
        surfaceHighest = s.surfaceContainerHighest,
        outline = s.outlineVariant,
        outlineStrong = s.outline,
        textPrimary = s.onSurface,
        textMuted = s.onSurfaceVariant,
        textFaint = lerp(s.onSurfaceVariant, s.surface, 0.3f),
        accent = s.primary,
        onAccent = s.onPrimary,
        accentContainer = s.primaryContainer,
        onAccentContainer = s.onPrimaryContainer,
        success = fixed.success,
        warning = fixed.warning,
        error = s.error,
        onError = s.onError,
        errorContainer = s.errorContainer,
        onErrorContainer = s.onErrorContainer,
        chart = EqifyPalettes.chartTints(s.primary, s.onSurface)
    )
}
