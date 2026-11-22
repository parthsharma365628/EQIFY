package com.example.eqify.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.eqify.R

/*
 * Panel typeface (DESIGN_REVIEW.md item 7): Archivo, one variable font with
 * weight (100–900) and width (62–125) axes, SIL OFL 1.1 (res/raw/archivo_ofl.txt).
 * Normal width for text; a condensed width for data such as band labels, dB
 * readouts, chips and segments, like lettering on a hardware panel. One
 * family, two widths; no second typeface. Variable fonts need API 26 (= minSdk).
 */

private const val NORMAL_WIDTH = 100f
/** Condensed enough to fit "+12.0" in an EQ band column, still clearly the same face. */
private const val CONDENSED_WIDTH = 75f

// FontVariation is still marked experimental in this Compose version (BOM 2024.09).
@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: FontWeight, width: Float) = Font(
    resId = R.font.archivo_variable,
    weight = weight,
    // The file's default instance is SemiBold, so the weight axis is always set explicitly.
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.width(width))
)

private val textWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/** Titles and body text. */
val ArchivoText = FontFamily(textWeights.map { archivo(it, NORMAL_WIDTH) })

/** Numbers and short panel labels. */
val ArchivoCondensed = FontFamily(textWeights.map { archivo(it, CONDENSED_WIDTH) })

/** UI_REDESIGN.md §4.2 type scale, now set in Archivo. Nothing below 11 sp. */
val EqifyTypography = Typography(
    headlineSmall = style(24, 32, FontWeight.SemiBold, letterSpacing = -0.2),
    titleLarge = style(20, 28, FontWeight.SemiBold, letterSpacing = -0.1),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    // Small captions and units (e.g. the EQ "Hz" caption). Not for uppercase labels.
    labelSmall = style(11, 16, FontWeight.Medium, letterSpacing = 0.2)
)

/** Tabular (fixed-width) figures so dB/Hz/% readouts never jump while changing. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** The condensed width of the same face, for data: band labels, readouts, chips, segments. */
fun TextStyle.condensed(): TextStyle = copy(fontFamily = ArchivoCondensed)

private fun style(size: Int, line: Int, weight: FontWeight, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = ArchivoText,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = letterSpacing.sp
)
