package com.banner.hintcontrol.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.MainActivity
import com.banner.hintcontrol.data.StatusSummary
import com.banner.hintcontrol.data.toSummary
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.Quality
import com.banner.hintcontrol.gateway.signal.SignalQuality
import java.text.DateFormat
import java.util.Date

class StatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = HintApp.container(context).summaries.load()
        provideContent {
            GlanceTheme {
                WidgetBody(summary)
            }
        }
    }

    companion object {
        suspend fun refreshAll(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(StatusWidget::class.java).isNotEmpty()) {
                StatusWidget().updateAll(context)
            }
        }
    }
}

private fun qualityColor(q: Quality?): Color = when (q) {
    Quality.Excellent -> Color(0xFF2EB868)
    Quality.Good -> Color(0xFF8BC34A)
    Quality.Fair -> Color(0xFFF2A93B)
    Quality.Poor -> Color(0xFFE5534B)
    null -> Color(0xFF8A9493)
}

@Composable
private fun WidgetBody(summary: StatusSummary?) {
    val text = GlanceTheme.colors.onSurface
    val dim = GlanceTheme.colors.onSurfaceVariant
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary?.gatewayName ?: "Gateway",
                style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "↻",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        when {
            summary == null -> Text("Open the app to connect", style = TextStyle(color = dim, fontSize = 12.sp))
            !summary.online -> Text("Gateway offline", style = TextStyle(color = ColorProvider(qualityColor(Quality.Poor)), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            else -> {
                RadioRow("5G", summary.nrBand, summary.nrRsrp, summary.nrSinr)
                Spacer(GlanceModifier.height(4.dp))
                RadioRow("LTE", summary.lteBand, summary.lteRsrp, summary.lteSinr)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        summary?.let {
            Text(
                text = "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)),
                style = TextStyle(color = dim, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, band: String?, rsrp: Int?, sinr: Int?) {
    val q = listOfNotNull(SignalQuality.rate(Metric.RSRP, rsrp), SignalQuality.rate(Metric.SINR, sinr)).maxByOrNull { it.ordinal }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = TextStyle(color = ColorProvider(qualityColor(q)), fontSize = 12.sp))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "$label ${band ?: "—"}",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = if (rsrp == null) "not connected" else "${rsrp} dBm · ${sinr ?: "—"} dB",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val c = HintApp.container(context)
        val profile = c.profiles.active.value ?: return
        runCatching { c.session.status { it.mainData() } }
            .onSuccess { c.summaries.save(it.toSummary(profile.name)) }
            .onFailure { c.summaries.markOffline(profile.name) }
        StatusWidget().updateAll(context)
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}
