package com.example.eqify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.eqify.ui.theme.EqifyTheme

enum class ToneCurveStyle { CURVE, BARS }

/**
 * The EQ curve, drawn the same way everywhere: behind the EQ sliders, in share
 * and import previews. Bands are evenly spaced columns (matching the slider
 * layout); y is linear in dB with 0 dB at the vertical centre.
 */
@Composable
fun ToneCurve(
    gains: List<Float>,
    modifier: Modifier = Modifier,
    /** Null = size comes from [modifier] (e.g. fillMaxSize behind the EQ sliders). */
    height: Dp? = 64.dp,
    style: ToneCurveStyle = ToneCurveStyle.CURVE,
    rangeDb: Float = 12f,
    showGrid: Boolean = true,
    /** Dots at each band on the CURVE style; off for the clean Home hero. */
    showPoints: Boolean = true,
    contentDescription: String? = "EQ curve"
) {
    // Theme values are read here: the Canvas draw lambda is not composable.
    val accent = EqifyTheme.colors.accent
    val fill = accent.copy(alpha = 0.16f)
    val zeroLine = EqifyTheme.colors.outlineStrong
    val gridLine = EqifyTheme.colors.outline
    Canvas(
        modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
    ) {
        if (gains.isEmpty()) return@Canvas
        val pts = toneCurvePoints(gains, size.width, size.height, rangeDb)
        val mid = size.height / 2f
        if (showGrid) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            listOf(rangeDb / 2f, -rangeDb / 2f).forEach { db ->
                val y = dbToY(db, size.height, rangeDb)
                drawLine(gridLine, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
        }
        drawLine(zeroLine, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        when (style) {
            ToneCurveStyle.BARS -> {
                val slot = size.width / gains.size
                pts.forEach { p ->
                    drawLine(accent, Offset(p.x, mid), Offset(p.x, p.y),
                        (slot * 0.45f).coerceAtMost(14.dp.toPx()), StrokeCap.Round)
                }
            }
            ToneCurveStyle.CURVE -> {
                val line = smoothPath(pts)
                val area = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, mid); lineTo(pts.first().x, mid); close()
                }
                drawPath(area, fill)
                drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (showPoints) pts.forEach { drawCircle(accent, 3.dp.toPx(), it) }
            }
        }
    }
}

/** dB → y in pixels: +range at top, −range at bottom, 0 in the middle. Values beyond range are clamped. */
fun dbToY(db: Float, height: Float, rangeDb: Float = 12f): Float {
    val clamped = db.coerceIn(-rangeDb, rangeDb)
    return height / 2f - (clamped / rangeDb) * (height / 2f)
}

/** Column-centre points for each band, matching evenly spaced slider columns. */
fun toneCurvePoints(gains: List<Float>, width: Float, height: Float, rangeDb: Float = 12f): List<Offset> {
    if (gains.isEmpty()) return emptyList()
    val slot = width / gains.size
    return gains.mapIndexed { i, g -> Offset(slot * i + slot / 2f, dbToY(g, height, rangeDb)) }
}

/** Catmull-Rom spline through the points, as cubic Béziers (passes through every band point). */
private fun smoothPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts.first().x, pts.first().y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(i - 1, 0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(i + 2, pts.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}
