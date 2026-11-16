package com.banner.hintcontrol.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.banner.hintcontrol.gateway.signal.Bands
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.Quality
import com.banner.hintcontrol.gateway.signal.SignalQuality
import com.banner.hintcontrol.ui.theme.LocalQualityColors
import com.banner.hintcontrol.ui.theme.NumberStyle

@Composable
fun qualityColor(q: Quality?): Color = LocalQualityColors.current.of(q)

@Composable
fun QualityPill(q: Quality?, modifier: Modifier = Modifier) {
    val c = qualityColor(q)
    Pill(
        text = q?.label ?: "No signal",
        color = c.copy(alpha = 0.16f),
        contentColor = c,
        modifier = modifier,
    )
}

/** Label, value and a bar that fills and colours by quality. */
@Composable
fun MetricBar(metric: Metric, value: Int?, modifier: Modifier = Modifier) {
    val q = SignalQuality.rate(metric, value)
    val target = SignalQuality.fraction(metric, value)
    val fraction by animateFloatAsState(target, tween(600), label = "metric")
    val color by animateColorAsState(qualityColor(q), tween(600), label = "metricColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.semantics { contentDescription = "${metric.label} ${value ?: "unknown"} ${metric.unit}, ${q?.label ?: "no reading"}" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                value?.toString() ?: "—",
                style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
            )
            Spacer(Modifier.width(3.dp))
            Text(metric.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (value != null) drawRoundRect(color, size = Size((size.width * fraction).coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(2.dp))
        Text(q?.label ?: "", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Ring showing the 0–100 combined score. */
@Composable
fun ScoreRing(score: Int?, quality: Quality?, modifier: Modifier = Modifier, size: Dp = 112.dp, stroke: Dp = 10.dp, caption: String = "score") {
    val sweep by animateFloatAsState((score ?: 0) / 100f, tween(700), label = "score")
    val color by animateColorAsState(qualityColor(quality), tween(700), label = "scoreColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size).semantics { contentDescription = "Signal score ${score ?: "unknown"} of 100" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            val topLeft = Offset(s / 2, s / 2)
            drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
            if (score != null) drawArc(color, 135f, 270f * sweep, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium.merge(NumberStyle).copy(fontWeight = FontWeight.Bold))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Five rising bars, [bars] lit. */
@Composable
fun SignalBars(bars: Double?, quality: Quality?, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val lit = (bars ?: 0.0).toInt().coerceIn(0, 5)
    val on = qualityColor(quality)
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.size(width = height * 1.2f, height = height).semantics { contentDescription = "$lit of 5 bars" }) {
        val gap = size.width * 0.06f
        val w = (size.width - gap * 4) / 5
        for (i in 0 until 5) {
            val h = size.height * (0.28f + 0.18f * i)
            drawRoundRect(
                if (i < lit) on else off,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3),
            )
        }
    }
}

@Composable
fun BandPills(bands: List<String>?, nr: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bands.orEmpty().forEach { raw ->
            val info = Bands.describe(raw, nr)
            Pill(listOfNotNull(info.id, info.frequency).joinToString(" · "))
        }
    }
}
