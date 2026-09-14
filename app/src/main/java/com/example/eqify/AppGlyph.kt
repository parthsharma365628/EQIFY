package com.example.eqify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Consistent line icons, independent of the device's emoji font. */
@Composable
fun AppGlyph(name: String, tint: Color = TextSecondary) {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = name }) {
        val unit = size.minDimension / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x * unit, y * unit), Offset(x2 * unit, y2 * unit),
                1.7f * unit, StrokeCap.Round)
        when (name) {
            "Home" -> {
                val path = Path().apply {
                    moveTo(3 * unit, 11 * unit); lineTo(12 * unit, 3 * unit)
                    lineTo(21 * unit, 11 * unit); moveTo(6 * unit, 10 * unit)
                    lineTo(6 * unit, 21 * unit); lineTo(18 * unit, 21 * unit)
                    lineTo(18 * unit, 10 * unit)
                }
                drawPath(path, tint, style = Stroke(1.7f * unit))
            }
            "Headphones" -> {
                drawArc(tint, 180f, 180f, false, Offset(4 * unit, 4 * unit),
                    androidx.compose.ui.geometry.Size(16 * unit, 16 * unit),
                    style = Stroke(1.7f * unit))
                line(4f, 12f, 4f, 20f); line(7f, 14f, 7f, 20f)
                line(20f, 12f, 20f, 20f); line(17f, 14f, 17f, 20f)
                line(4f, 20f, 7f, 20f); line(17f, 20f, 20f, 20f)
            }
            else -> {
                listOf(5f to 8f, 12f to 16f, 19f to 10f).forEach { (x, y) ->
                    line(x, 4f, x, y - 2); line(x, y + 2, x, 21f)
                    drawCircle(tint, 2 * unit, Offset(x * unit, y * unit),
                        style = Stroke(1.7f * unit))
                }
            }
        }
    }
}
