package com.liteplane.eqify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liteplane.eqify.SoundLayer
import com.liteplane.eqify.ui.theme.EqSpacing
import com.liteplane.eqify.ui.theme.EqifyTheme

private val FREQ_LABELS = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k")

/** How each layer is drawn: colour plus line style, so it reads without colour too. */
private data class LayerStyle(val color: Color, val width: Dp, val dash: FloatArray?)

@Composable
private fun styleFor(kind: SoundLayer.Kind): LayerStyle {
    val c = EqifyTheme.colors
    return when (kind) {
        SoundLayer.Kind.TOTAL -> LayerStyle(c.accent, 3.dp, null)
        SoundLayer.Kind.TONE -> LayerStyle(c.chart.getOrElse(1) { c.textPrimary }, 1.5.dp, floatArrayOf(10f, 6f))
        SoundLayer.Kind.CORRECTION -> LayerStyle(c.chart.getOrElse(2) { c.textMuted }, 1.5.dp, floatArrayOf(3f, 5f))
        SoundLayer.Kind.BASS -> LayerStyle(c.chart.getOrElse(3) { c.warning }, 1.5.dp, floatArrayOf(14f, 5f, 3f, 5f))
    }
}

/**
 * Several EQ curves over the eight bands (FEATURES_NEXT_PLAN.md §3), with a
 * legend that shows or hides each layer and frequency labels underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayeredCurve(
    layers: List<SoundLayer>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    rangeDb: Float = 12f,
    showLegend: Boolean = true,
    description: String
) {
    val c = EqifyTheme.colors
    var hidden by remember { mutableStateOf(emptySet<SoundLayer.Kind>()) }
    val styles = layers.associate { it.kind to styleFor(it.kind) }
    val zeroLine = c.outlineStrong
    val gridLine = c.outline
    val fill = c.accent.copy(alpha = 0.12f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            listOf(rangeDb / 2f, -rangeDb / 2f).forEach { db ->
                val y = dbToY(db, size.height, rangeDb)
                drawLine(gridLine, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
            val mid = size.height / 2f
            drawLine(zeroLine, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            layers.filter { it.kind !in hidden && it.gains.isNotEmpty() }.forEach { layer ->
                val style = styles.getValue(layer.kind)
                val pts = toneCurvePoints(layer.gains, size.width, size.height, rangeDb)
                val path = smoothCurve(pts)
                if (layer.kind == SoundLayer.Kind.TOTAL) {
                    val area = Path().apply {
                        addPath(path); lineTo(pts.last().x, mid); lineTo(pts.first().x, mid); close()
                    }
                    drawPath(area, fill)
                }
                drawPath(path, style.color, style = Stroke(
                    style.width.toPx(), cap = StrokeCap.Round,
                    pathEffect = style.dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density }) }
                ))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            FREQ_LABELS.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.textMuted,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        if (showLegend && layers.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
                layers.forEach { layer ->
                    val on = layer.kind !in hidden
                    val style = styles.getValue(layer.kind)
                    Row(
                        Modifier.heightIn(min = 40.dp)
                            .clickable(role = Role.Checkbox) {
                                hidden = if (on) hidden + layer.kind else hidden - layer.kind
                            }
                            .semantics { stateDescription = if (on) "Shown" else "Hidden" }
                            .padding(vertical = EqSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EqSpacing.xs)
                    ) {
                        Canvas(Modifier.width(20.dp).height(10.dp)) {
                            val y = size.height / 2f
                            drawLine(if (on) style.color else style.color.copy(alpha = 0.3f),
                                Offset(0f, y), Offset(size.width, y), style.width.toPx(),
                                pathEffect = style.dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density / 2f }) })
                        }
                        Text(layer.kind.label, style = MaterialTheme.typography.labelMedium,
                            color = if (on) c.textPrimary else c.textFaint)
                    }
                }
            }
        }
    }
}

/** Catmull-Rom spline through the points (same shape as [ToneCurve]). */
private fun smoothCurve(pts: List<Offset>): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts.first().x, pts.first().y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(i - 1, 0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(i + 2, pts.size - 1)]
        cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
    }
}
