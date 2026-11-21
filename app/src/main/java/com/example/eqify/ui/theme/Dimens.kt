package com.example.eqify.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** UI_REDESIGN.md §4.3 spacing scale. */
object EqSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Screen side padding. */
    val screen = 16.dp
    /** Gap between cards. */
    val cardGap = 12.dp
    /** Minimum touch target. */
    val touchTarget = 48.dp
}

/** small: chips/inputs · medium: rows/buttons · large: cards · extraLarge: sheet tops. */
val EqifyShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Fully rounded toggles, segments and status pills. */
val PillShape = RoundedCornerShape(percent = 50)
