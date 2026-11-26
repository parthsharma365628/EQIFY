package com.liteplane.eqify.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Every colour role the UI may use (UI_REDESIGN.md §4.1). Screens read roles
 * through [EqifyTheme.colors], never hex values, so a theme (§4.6) is just
 * another [EqifyColors] instance.
 */
@Immutable
data class EqifyColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textMuted: Color,
    /** Hints and disabled text only (≥3:1). Never for essential text. */
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    /** Genre-mix tints, strongest first; "Other" uses [textFaint]. */
    val chart: List<Color>
)

/** User-selectable accent families (§4.6). Order = picker order. */
enum class ThemeAccent(val label: String) {
    SAGE("Sage"), AMBER("Amber"), OCEAN("Ocean"), VIOLET("Violet"), ROSE("Rose"), MONO("Mono")
}

/** One accent's colours for one mode. */
private data class AccentFamily(val accent: Color, val onAccent: Color, val container: Color, val onContainer: Color)

/** Mode-wide neutrals and status colours; only the accent family varies by theme. */
private data class Neutrals(
    val background: Color, val surface: Color, val surfaceRaised: Color, val surfaceHighest: Color,
    val outline: Color, val outlineStrong: Color,
    val textPrimary: Color, val textMuted: Color, val textFaint: Color,
    val success: Color, val warning: Color,
    val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color
)

object EqifyPalettes {

    private val darkNeutrals = Neutrals(
        background = Color(0xFF0E1110), surface = Color(0xFF151918),
        surfaceRaised = Color(0xFF1C2120), surfaceHighest = Color(0xFF252B29),
        outline = Color(0xFF3A4440), outlineStrong = Color(0xFF73807A),
        textPrimary = Color(0xFFE8ECEA), textMuted = Color(0xFFA3ADA8), textFaint = Color(0xFF6F7A75),
        success = Color(0xFF7FC8A9), warning = Color(0xFFE0B45C),
        error = Color(0xFFF2726F), onError = Color(0xFF2A0B0A),
        errorContainer = Color(0xFF3A1F1E), onErrorContainer = Color(0xFFFFDAD6)
    )

    /** AMOLED: true-black background, surfaces lifted just enough to separate cards. */
    private val pureBlackNeutrals = darkNeutrals.copy(
        background = Color(0xFF000000), surface = Color(0xFF0E1110),
        surfaceRaised = Color(0xFF161A19), surfaceHighest = Color(0xFF202524)
    )

    private val lightNeutrals = Neutrals(
        background = Color(0xFFF1F4F2), surface = Color(0xFFFAFBFB),
        surfaceRaised = Color(0xFFFFFFFF), surfaceHighest = Color(0xFFE6EAE8),
        outline = Color(0xFFC5CCC9), outlineStrong = Color(0xFF7A8580),
        textPrimary = Color(0xFF161A19), textMuted = Color(0xFF4B5551), textFaint = Color(0xFF77817D),
        success = Color(0xFF23694B), warning = Color(0xFF8A5A00),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B)
    )

    private fun darkAccent(a: ThemeAccent) = when (a) {
        ThemeAccent.SAGE -> AccentFamily(Color(0xFF6FA393), Color(0xFF0B1310), Color(0xFF22352F), Color(0xFFCFE5DC))
        ThemeAccent.AMBER -> AccentFamily(Color(0xFFE0A458), Color(0xFF1F1405), Color(0xFF3D2C14), Color(0xFFF6DDBA))
        ThemeAccent.OCEAN -> AccentFamily(Color(0xFF6C9EFF), Color(0xFF07152E), Color(0xFF1D2F52), Color(0xFFD3E1FF))
        ThemeAccent.VIOLET -> AccentFamily(Color(0xFFA48CF0), Color(0xFF170F2E), Color(0xFF31274F), Color(0xFFE3DAFF))
        ThemeAccent.ROSE -> AccentFamily(Color(0xFFE88AA0), Color(0xFF2B0D15), Color(0xFF4A2530), Color(0xFFFFD9E1))
        ThemeAccent.MONO -> AccentFamily(Color(0xFFC9CFCC), Color(0xFF111413), Color(0xFF343A38), Color(0xFFE8ECEA))
    }

    private fun lightAccent(a: ThemeAccent) = when (a) {
        ThemeAccent.SAGE -> AccentFamily(Color(0xFF3D6E5F), Color.White, Color(0xFFD3EAE1), Color(0xFF0F2B22))
        ThemeAccent.AMBER -> AccentFamily(Color(0xFF8C5A12), Color.White, Color(0xFFF8E2C2), Color(0xFF2E1B00))
        ThemeAccent.OCEAN -> AccentFamily(Color(0xFF2F5FB8), Color.White, Color(0xFFDCE6FF), Color(0xFF0B1E45))
        ThemeAccent.VIOLET -> AccentFamily(Color(0xFF6A4FC4), Color.White, Color(0xFFE6DEFF), Color(0xFF22134F))
        ThemeAccent.ROSE -> AccentFamily(Color(0xFFA23B5A), Color.White, Color(0xFFFFDCE4), Color(0xFF3E0A1C))
        ThemeAccent.MONO -> AccentFamily(Color(0xFF3F4744), Color.White, Color(0xFFE2E6E4), Color(0xFF1B1F1D))
    }

    fun dark(accent: ThemeAccent = ThemeAccent.SAGE, pureBlack: Boolean = false): EqifyColors {
        val family = darkAccent(accent)
        var n = if (pureBlack) pureBlackNeutrals else darkNeutrals
        // Amber's accent sits close to the standard warning; use a yellower warning there.
        if (accent == ThemeAccent.AMBER) n = n.copy(warning = Color(0xFFF0D060))
        return build(true, n, family, tintSurfaces = accent != ThemeAccent.MONO && !pureBlack)
    }

    fun light(accent: ThemeAccent = ThemeAccent.SAGE): EqifyColors {
        var n = lightNeutrals
        if (accent == ThemeAccent.AMBER) n = n.copy(warning = Color(0xFF6E5A00))
        return build(false, n, lightAccent(accent), tintSurfaces = false)
    }

    /**
     * Builds the role set. Dark surfaces get ~4% of the accent so each theme
     * feels cohesive; text and status colours are never tinted.
     */
    private fun build(dark: Boolean, n: Neutrals, f: AccentFamily, tintSurfaces: Boolean): EqifyColors {
        fun tint(c: Color) = if (tintSurfaces) lerp(c, f.accent, 0.04f) else c
        return EqifyColors(
            isDark = dark,
            background = tint(n.background),
            surface = tint(n.surface),
            surfaceRaised = tint(n.surfaceRaised),
            surfaceHighest = tint(n.surfaceHighest),
            outline = n.outline,
            outlineStrong = n.outlineStrong,
            textPrimary = n.textPrimary,
            textMuted = n.textMuted,
            textFaint = n.textFaint,
            accent = f.accent,
            onAccent = f.onAccent,
            accentContainer = f.container,
            onAccentContainer = f.onContainer,
            success = n.success,
            warning = n.warning,
            error = n.error,
            onError = n.onError,
            errorContainer = n.errorContainer,
            onErrorContainer = n.onErrorContainer,
            chart = chartTints(f.accent, n.textPrimary)
        )
    }

    /** Four single-hue steps from the accent toward the text colour (lighter in dark mode, darker in light). */
    internal fun chartTints(accent: Color, towards: Color): List<Color> =
        listOf(0f, 0.25f, 0.5f, 0.75f).map { lerp(accent, towards, it) }

    /** Every curated palette that ships (dark, pure black, light × each accent), for tests and previews. */
    val all: List<Pair<String, EqifyColors>>
        get() = ThemeAccent.entries.flatMap { a ->
            listOf(
                "${a.label} dark" to dark(a),
                "${a.label} pure black" to dark(a, pureBlack = true),
                "${a.label} light" to light(a)
            )
        }
}

val LocalEqifyColors = staticCompositionLocalOf { EqifyPalettes.dark() }
