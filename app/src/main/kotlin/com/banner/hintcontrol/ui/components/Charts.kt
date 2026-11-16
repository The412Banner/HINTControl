package com.banner.hintcontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.banner.hintcontrol.ui.theme.NumberStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@Immutable
data class ChartPoint(val x: Long, val y: Float)

@Immutable
data class ChartSeries(val label: String, val color: Color, val points: List<ChartPoint>)

/**
 * Line chart with grid, Y labels and time labels. Drag or tap to read values at a point in time.
 * Gaps longer than [gapMs] break the line (e.g. when the app wasn't recording).
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    unit: String = "",
    gapMs: Long = 20 * 60_000L,
    xLabel: (Long) -> String = Format::time,
) {
    val all = series.flatMap { it.points }
    if (all.isEmpty()) {
        Column(modifier.fillMaxWidth().height(height), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No readings in this period yet", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.x }.let { if (it == minX) minX + 1 else it }
    val rawMin = all.minOf { it.y }
    val rawMax = all.maxOf { it.y }
    val step = niceStep((rawMax - rawMin).coerceAtLeast(4f) / 4f)
    val minY = floor(rawMin / step) * step
    val maxY = (ceil(rawMax / step) * step).let { if (it <= minY) minY + step else it }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(NumberStyle).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    var cursorX by remember(series) { mutableStateOf<Long?>(null) }

    Column(modifier) {
        val selectedTime = cursorX
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(8.dp).background(s.color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    val readout = selectedTime?.let { t -> s.points.minByOrNull { abs(it.x - t) }?.y?.let { v -> " ${v.toInt()}$unit" } }.orEmpty()
                    Text(s.label + readout, style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
                }
            }
            if (selectedTime != null) {
                Spacer(Modifier.weight(1f))
                Text(Format.dateTime(selectedTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(series) {
                    val leftPad = 44.dp.toPx()
                    detectTapGestures { cursorX = if (cursorX == null) nearestTime(all, minX, maxX, leftPad, size.width, it.x) else null }
                }
                .pointerInput(series) {
                    val leftPad = 44.dp.toPx()
                    detectDragGestures(onDragEnd = {}, onDragCancel = {}) { change, _ ->
                        cursorX = nearestTime(all, minX, maxX, leftPad, size.width, change.position.x)
                    }
                },
        ) {
            val leftPad = 44.dp.toPx()
            val bottomPad = 20.dp.toPx()
            val w = size.width - leftPad
            val h = size.height - bottomPad
            fun px(x: Long) = leftPad + (x - minX).toFloat() / (maxX - minX) * w
            fun py(y: Float) = h - (y - minY) / (maxY - minY) * h

            var v = minY
            while (v <= maxY + 0.001f) {
                val y = py(v)
                drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val text = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(text, topLeft = Offset(leftPad - text.size.width - 8f, y - text.size.height / 2f))
                v += step
            }
            listOf(minX, (minX + maxX) / 2, maxX).forEachIndexed { i, t ->
                val text = measurer.measure(xLabel(t), labelStyle)
                val x = when (i) {
                    0 -> leftPad
                    1 -> px(t) - text.size.width / 2f
                    else -> size.width - text.size.width
                }
                drawText(text, topLeft = Offset(x, h + 4f))
            }

            series.forEach { s ->
                if (s.points.isEmpty()) return@forEach
                val path = Path()
                val fill = Path()
                var started = false
                var lastX = 0L
                var segStartPx = 0f
                fun closeFill(endPx: Float) {
                    fill.lineTo(endPx, h)
                    fill.lineTo(segStartPx, h)
                    fill.close()
                }
                s.points.forEach { p ->
                    val x = px(p.x)
                    val y = py(p.y)
                    if (!started || p.x - lastX > gapMs) {
                        if (started) closeFill(px(lastX))
                        path.moveTo(x, y)
                        fill.moveTo(x, y)
                        segStartPx = x
                        started = true
                    } else {
                        path.lineTo(x, y)
                        fill.lineTo(x, y)
                    }
                    lastX = p.x
                }
                closeFill(px(lastX))
                drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.22f), Color.Transparent), startY = 0f, endY = h))
                drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (s.points.size == 1) drawCircle(s.color, 4.dp.toPx(), Offset(px(s.points[0].x), py(s.points[0].y)))
            }

            cursorX?.let { t ->
                val x = px(t)
                drawLine(cursorColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                series.forEach { s ->
                    s.points.minByOrNull { abs(it.x - t) }?.let { p ->
                        drawCircle(s.color, 5.dp.toPx(), Offset(px(p.x), py(p.y)))
                    }
                }
            }
        }
    }
}

@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max().let { if (it - min < 1f) min + 1f else it }
    Canvas(modifier) {
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = size.height - (v - min) / (max - min) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Time of the reading closest to a touch, so the cursor line, dots and readout all show the same sample. */
internal fun nearestTime(points: List<ChartPoint>, minX: Long, maxX: Long, leftPad: Float, width: Int, touchX: Float): Long? {
    if (points.isEmpty()) return null
    val t = minX + (((touchX - leftPad) / (width - leftPad)).coerceIn(0f, 1f) * (maxX - minX)).toLong()
    return points.minBy { abs(it.x - t) }.x
}

private fun niceStep(raw: Float): Float {
    val candidates = floatArrayOf(1f, 2f, 5f, 10f, 20f, 25f, 50f)
    return candidates.firstOrNull { it >= raw } ?: 50f
}

/** Averages points into at most [buckets] buckets so long ranges stay fast to draw. */
fun List<ChartPoint>.downsample(buckets: Int = 400): List<ChartPoint> {
    if (size <= buckets) return this
    val minX = first().x
    val span = (last().x - minX).coerceAtLeast(1)
    return groupBy { ((it.x - minX) * buckets / span).coerceAtMost(buckets - 1L) }
        .values
        .map { g -> ChartPoint(g.sumOf { it.x } / g.size, g.sumOf { it.y.toDouble() }.toFloat() / g.size) }
}
