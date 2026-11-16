package com.banner.hintcontrol.ui.signal

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.towerKey
import com.banner.hintcontrol.data.db.SignalSample
import com.banner.hintcontrol.gateway.model.BaseAdvancedData
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.signal.Bands
import com.banner.hintcontrol.gateway.signal.CellMath
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.SignalQuality
import com.banner.hintcontrol.ui.components.BandPills
import com.banner.hintcontrol.ui.components.ChartMarker
import com.banner.hintcontrol.ui.components.ChartPoint
import com.banner.hintcontrol.ui.components.ChartSeries
import com.banner.hintcontrol.ui.components.ErrorBox
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.LineChart
import com.banner.hintcontrol.ui.components.LoadingBox
import com.banner.hintcontrol.ui.components.LoginPrompt
import com.banner.hintcontrol.ui.components.MetricBar
import com.banner.hintcontrol.ui.components.Pill
import com.banner.hintcontrol.ui.components.QualityPill
import com.banner.hintcontrol.ui.components.ScoreRing
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.downsample
import com.banner.hintcontrol.ui.components.userMessageOrNull
import com.banner.hintcontrol.ui.nav.containerViewModel

@Composable
fun SignalScreen() {
    val vm = containerViewModel { SignalViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.csv().toByteArray()) } }
    }
    val feedback = remember { PlacementFeedback(context) }
    val latest = (if (ui.tech == Tech.NR) ui.readingsNr else ui.readingsLte).lastOrNull()
    LaunchedEffect(latest?.time, ui.placement.on) {
        val score = latest?.score ?: return@LaunchedEffect
        if (!ui.placement.on) return@LaunchedEffect
        if (ui.placement.sound) feedback.beep(score)
        if (ui.placement.haptics) feedback.buzz(score)
    }
    val view = LocalView.current
    DisposableEffect(ui.placement.on, ui.placement.keepScreenOn) {
        view.keepScreenOn = ui.placement.on && ui.placement.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    SignalContent(
        ui = ui,
        onTech = vm::setTech,
        onPlacement = vm::setPlacement,
        onResetBest = vm::resetBest,
        onSound = { vm.setSound(it) },
        onHaptics = { vm.setHaptics(it) },
        onRange = vm::setRange,
        onMetric = vm::setMetric,
        onFixedScale = { vm.setFixedScale(it) },
        onTowerName = { key, name -> vm.setTowerName(key, name) },
        onExport = { exporter.launch("gateway-signal-${System.currentTimeMillis() / 1000}.csv") },
        onClear = { vm.clearHistory() },
        onLoadCell = vm::loadCell,
        onLogin = vm::login,
    )
}

@Composable
fun SignalContent(
    ui: SignalUi,
    onTech: (Tech) -> Unit,
    onPlacement: (Boolean) -> Unit,
    onResetBest: () -> Unit,
    onSound: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onRange: (HistoryRange) -> Unit,
    onMetric: (Metric) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onLoadCell: () -> Unit,
    onLogin: (String, Boolean) -> Unit,
    initialTab: Int = 0,
    onFixedScale: (Boolean) -> Unit = {},
    onTowerName: (String, String) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Scaffold(topBar = { TopAppBar(title = { Text("Signal") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Live", "History", "Cell").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> LiveTab(ui, onTech, onPlacement, onResetBest, onSound, onHaptics)
                1 -> HistoryTab(ui, onRange, onMetric, onFixedScale, onExport, onClear)
                else -> {
                    LaunchedEffect(Unit) { if (ui.cell.data == null) onLoadCell() }
                    CellTab(ui, onLoadCell, onLogin, onTowerName)
                }
            }
        }
    }
}

@Composable
private fun TechSwitch(ui: SignalUi, onTech: (Tech) -> Unit) {
    val techs = ui.availableTechs
    if (techs.size < 2) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        techs.forEachIndexed { i, t ->
            SegmentedButton(selected = ui.tech == t, onClick = { onTech(t) }, shape = SegmentedButtonDefaults.itemShape(i, techs.size)) { Text(t.label) }
        }
    }
}

@Composable
private fun LiveTab(
    ui: SignalUi,
    onTech: (Tech) -> Unit,
    onPlacement: (Boolean) -> Unit,
    onResetBest: () -> Unit,
    onSound: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    val cell = ui.cell(ui.tech)
    if (ui.live.main == null) {
        if (ui.live.error != null) ErrorBox(ui.live.error.userMessageOrNull().orEmpty(), offline = ui.live.offline) else LoadingBox()
        return
    }
    val readings = if (ui.tech == Tech.NR) ui.readingsNr else ui.readingsLte
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TechSwitch(ui, onTech)
            SectionCard(title = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(SignalQuality.score(cell), SignalQuality.overall(cell), size = 148.dp, stroke = 12.dp)
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ui.tech.label, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(10.dp))
                            QualityPill(SignalQuality.overall(cell))
                        }
                        BandPills(cell?.bands, nr = ui.tech == Tech.NR)
                        if (ui.placement.on) {
                            Text(
                                "Best ${ui.placement.best ?: "—"}" + (ui.placement.bestAt?.let { " · ${Format.ago(it)}" } ?: ""),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            SectionCard("Placement mode", Icons.Outlined.MyLocation, trailing = { Switch(ui.placement.on, onPlacement) }) {
                Text(
                    "Reads the gateway every second. Move it around and watch the score; higher pitch means a better spot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.placement.on) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(ui.placement.sound, { onSound(!ui.placement.sound) }, label = { Text("Sound") })
                        FilterChip(ui.placement.haptics, { onHaptics(!ui.placement.haptics) }, label = { Text("Vibration") })
                        TextButton(onClick = onResetBest) { Text("Reset best") }
                    }
                }
            }
            SectionCard("Readings", Icons.Outlined.SettingsInputAntenna) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricBar(Metric.RSRP, cell?.rsrp, Modifier.weight(1f))
                    MetricBar(Metric.SINR, cell?.sinr, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricBar(Metric.RSRQ, cell?.rsrq, Modifier.weight(1f))
                    MetricBar(Metric.RSSI, cell?.rssi, Modifier.weight(1f))
                }
            }
            if (readings.size > 1) {
                SectionCard("Since you opened this screen", null) {
                    val color = MaterialTheme.colorScheme.primary
                    LineChart(
                        listOf(ChartSeries("SINR", color, readings.mapNotNull { r -> r.sinr?.let { ChartPoint(r.time, it.toFloat()) } })),
                        height = 140.dp,
                        unit = " dB",
                        gapMs = 120_000,
                    )
                    Spacer(Modifier.height(12.dp))
                    LineChart(
                        listOf(ChartSeries("RSRP", MaterialTheme.colorScheme.tertiary, readings.mapNotNull { r -> r.rsrp?.let { ChartPoint(r.time, it.toFloat()) } })),
                        height = 140.dp,
                        unit = " dBm",
                        gapMs = 120_000,
                    )
                }
            }
        }
    }
}

private fun SignalSample.value(metric: Metric, nr: Boolean): Int? = when (metric) {
    Metric.RSRP -> if (nr) nrRsrp else lteRsrp
    Metric.SINR -> if (nr) nrSinr else lteSinr
    Metric.RSRQ -> if (nr) nrRsrq else lteRsrq
    Metric.RSSI -> if (nr) nrRssi else lteRssi
}

@Composable
private fun HistoryTab(
    ui: SignalUi,
    onRange: (HistoryRange) -> Unit,
    onMetric: (Metric) -> Unit,
    onFixedScale: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r -> FilterChip(ui.range == r, { onRange(r) }, label = { Text(r.label) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric.entries.forEach { m -> FilterChip(ui.metric == m, { onMetric(m) }, label = { Text(m.label) }) }
                FilterChip(ui.fixedScale, { onFixedScale(!ui.fixedScale) }, label = { Text("Fixed scale") })
            }
            val nrColor = MaterialTheme.colorScheme.primary
            val lteColor = MaterialTheme.colorScheme.tertiary
            val series = remember(ui.history, ui.metric, nrColor, lteColor) {
                listOf(
                    ChartSeries("5G", nrColor, ui.history.mapNotNull { s -> s.value(ui.metric, true)?.let { ChartPoint(s.time, it.toFloat()) } }.downsample()),
                    ChartSeries("LTE", lteColor, ui.history.mapNotNull { s -> s.value(ui.metric, false)?.let { ChartPoint(s.time, it.toFloat()) } }.downsample()),
                ).filter { it.points.isNotEmpty() }
            }
            val changes = remember(ui.history) { bandChanges(ui.history) }
            val markers = remember(changes) { changes.map { ChartMarker(it.time, it.to ?: "none") } }
            SectionCard("${ui.metric.label} (${ui.metric.unit})", null) {
                LineChart(
                    series,
                    height = 240.dp,
                    unit = " ${ui.metric.unit}",
                    gapMs = maxOf(20 * 60_000L, ui.range.ms / 50),
                    xLabel = if (ui.range.ms > 86_400_000L) Format::day else Format::time,
                    markers = markers,
                    fixedRange = if (ui.fixedScale) ui.metric.chartRange else null,
                )
                if (!ui.recording) {
                    Spacer(Modifier.height(8.dp))
                    Text("Recording is off in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (series.isNotEmpty()) {
                SectionCard("Summary", null) {
                    series.forEach { s ->
                        val ys = s.points.map { it.y }
                        InfoRow(s.label, "min ${ys.min().toInt()} · avg ${ys.average().toInt()} · max ${ys.max().toInt()} ${ui.metric.unit}")
                    }
                    InfoRow("Readings", "${ui.history.size}")
                }
            }
            if (changes.isNotEmpty()) {
                SectionCard("Band changes", Icons.Outlined.SwapVert) {
                    changes.takeLast(12).reversed().forEach { ch ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(ch.text, style = MaterialTheme.typography.bodyMedium)
                            Text(Format.dateTime(ch.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onExport, enabled = ui.history.isNotEmpty()) {
                    Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export CSV")
                }
                TextButton(onClick = { confirmClear = true }, enabled = ui.history.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear history")
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear signal history?") },
            text = { Text("All stored readings for this gateway are deleted.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

internal data class BandChange(val time: Long, val radio: String, val from: String?, val to: String?) {
    val text: String get() = "$radio ${from ?: "none"} → ${to ?: "none"}"
}

internal fun bandChanges(samples: List<SignalSample>): List<BandChange> {
    val out = mutableListOf<BandChange>()
    var prevNr: String? = null
    var prevLte: String? = null
    samples.forEachIndexed { i, s ->
        if (i > 0 && s.nrBand != prevNr) out += BandChange(s.time, "5G", prevNr, s.nrBand)
        if (i > 0 && s.lteBand != prevLte) out += BandChange(s.time, "LTE", prevLte, s.lteBand)
        prevNr = s.nrBand
        prevLte = s.lteBand
    }
    return out
}

/** Fixed chart ranges for "Fixed scale" (HINT Control #127). */
internal val Metric.chartRange: ClosedFloatingPointRange<Float>
    get() = when (this) {
        Metric.RSRP -> -130f..-50f
        Metric.SINR -> -10f..35f
        Metric.RSRQ -> -25f..0f
        Metric.RSSI -> -110f..-30f
    }

@Composable
private fun CellTab(ui: SignalUi, onLoad: () -> Unit, onLogin: (String, Boolean) -> Unit, onTowerName: (String, String) -> Unit) {
    val cell = ui.cell
    when {
        cell.needsLogin && cell.data == null -> LoginPrompt(busy = ui.loginBusy || ui.access is Access.LoggingIn, error = ui.loginError, onLogin = onLogin)
        cell.data == null && cell.error != null -> ErrorBox(cell.error, onRetry = onLoad)
        cell.data == null -> LoadingBox(text = "Reading cell details…")
        else -> CellDetails(cell.data, onLoad, ui.towerNames, onTowerName)
    }
}

@Composable
private fun CellDetails(root: CellDataRoot, onRefresh: () -> Unit, towerNames: Map<String, String>, onTowerName: (String, String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val c = root.cell
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            c?.fiveG?.let { AdvancedCard("5G cell", it, nr = true, towerNames, onTowerName) }
            c?.fourG?.let { AdvancedCard("LTE cell", it, nr = false, towerNames, onTowerName) }
            if (c?.fiveG == null && c?.fourG == null) Text("The gateway reports no cell details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            c?.generic?.let { g ->
                SectionCard("Network", null) {
                    InfoRow("Registration", g.registration?.replaceFirstChar(Char::uppercase))
                    InfoRow("APN", g.apn)
                    InfoRow("Roaming", g.roaming?.let { if (it) "Yes" else "No" })
                    InfoRow("IPv6", g.hasIPv6?.let { if (it) "Yes" else "No" })
                }
            }
            c?.gps?.takeIf { it.latitude != null && it.longitude != null }?.let { gps ->
                SectionCard("Location reported by the gateway", Icons.Outlined.Map) {
                    InfoRow("Latitude", "%.5f".format(gps.latitude), copyable = true)
                    InfoRow("Longitude", "%.5f".format(gps.longitude), copyable = true)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${gps.latitude},${gps.longitude}?q=${gps.latitude},${gps.longitude}(Gateway)")))
                        }
                    }) { Text("Open in maps") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(towerText(root))) }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy tower info")
                }
            }
        }
    }
}

@Composable
private fun AdvancedCard(title: String, a: BaseAdvancedData, nr: Boolean, towerNames: Map<String, String>, onTowerName: (String, String) -> Unit) {
    SectionCard(title, null, trailing = { QualityPill(SignalQuality.overall(a.sector)) }) {
        BandPills(a.sector?.bands, nr)
        Spacer(Modifier.height(8.dp))
        a.bandwidth?.let { InfoRow("Bandwidth", it) }
        InfoRow(if (nr) "NR-ARFCN" else "EARFCN", a.earfcn, copyable = true)
        InfoRow("PCI", a.pci, copyable = true)
        InfoRow("CQI", a.cqi?.toString())
        val enb = a.sector?.nbid?.takeIf { it > 0 } ?: if (!nr) CellMath.lteFromEcgi(a.ecgi, a.mcc, a.mnc)?.first else null
        InfoRow(if (nr) "gNB ID" else "eNB ID", enb?.toString(), copyable = true)
        enb?.let { towerKey(nr, it) }?.let { key ->
            var editing by remember { mutableStateOf(false) }
            InfoRow("Tower name", towerNames[key], trailing = {
                IconButton(onClick = { editing = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, "Name this tower", modifier = Modifier.size(18.dp))
                }
            })
            if (editing) {
                TowerNameDialog(towerNames[key].orEmpty(), onDismiss = { editing = false }, onSave = { onTowerName(key, it); editing = false })
            }
        }
        InfoRow("Cell / sector", a.sector?.cid?.toString() ?: if (!nr) CellMath.lteFromEcgi(a.ecgi, a.mcc, a.mnc)?.second?.toString() else null)
        InfoRow("TAC", a.tac)
        InfoRow(if (nr) "NCGI" else "ECGI", a.ecgi, mono = true, copyable = true)
        InfoRow("Operator", listOfNotNull(a.plmn, listOfNotNull(a.mcc, a.mnc).takeIf { it.size == 2 }?.joinToString("-")).distinct().joinToString(" · ").ifBlank { null })
        val supported = a.supportedBands.orEmpty()
        if (supported.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Supported bands", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val active = a.sector?.bands.orEmpty().map { Bands.describe(it, nr).id }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                supported.forEach { b ->
                    val info = Bands.describe(b, nr)
                    if (info.id in active) {
                        Pill(info.id, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Pill(info.id, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun towerText(root: CellDataRoot): String = buildString {
    root.cell?.fiveG?.let { a ->
        appendLine("5G ${a.sector?.bands.orEmpty().joinToString("+")} PCI ${a.pci ?: "?"} NR-ARFCN ${a.earfcn ?: "?"} gNB ${a.sector?.nbid ?: "?"} NCGI ${a.ecgi ?: "?"}")
    }
    root.cell?.fourG?.let { a ->
        val enb = a.sector?.nbid ?: CellMath.lteFromEcgi(a.ecgi, a.mcc, a.mnc)?.first
        appendLine("LTE ${a.sector?.bands.orEmpty().joinToString("+")} PCI ${a.pci ?: "?"} EARFCN ${a.earfcn ?: "?"} eNB ${enb ?: "?"} TAC ${a.tac ?: "?"} ECGI ${a.ecgi ?: "?"}")
    }
}.trim()

@Composable
private fun TowerNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this tower") },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, label = { Text("Name") }, placeholder = { Text("e.g. Water tower") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
