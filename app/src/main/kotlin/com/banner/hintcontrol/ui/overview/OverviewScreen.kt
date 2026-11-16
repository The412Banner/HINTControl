package com.banner.hintcontrol.ui.overview

import com.banner.hintcontrol.ui.speed.mbpsText
import com.banner.hintcontrol.ui.nav.SpeedTestRoute
import com.banner.hintcontrol.data.SpeedRecord
import androidx.compose.material.icons.outlined.Speed
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.towerKey
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.LiveState
import com.banner.hintcontrol.data.Report
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.model.BaseCellData
import com.banner.hintcontrol.gateway.model.ClientLink
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.flatten
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.SignalQuality
import com.banner.hintcontrol.ui.components.Banner
import com.banner.hintcontrol.ui.components.BandPills
import com.banner.hintcontrol.ui.components.ErrorBox
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.LoadingBox
import com.banner.hintcontrol.ui.components.MetricBar
import com.banner.hintcontrol.ui.components.Pill
import com.banner.hintcontrol.ui.components.QualityPill
import com.banner.hintcontrol.ui.components.ScoreRing
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SignalBars
import com.banner.hintcontrol.ui.components.Sparkline
import com.banner.hintcontrol.ui.nav.DevicesRoute
import com.banner.hintcontrol.ui.nav.GatewayInfoRoute
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.SetupRoute
import com.banner.hintcontrol.ui.nav.SignalRoute
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OverviewUi(
    val profile: GatewayProfile? = null,
    val profiles: List<GatewayProfile> = emptyList(),
    val live: LiveState = LiveState(),
    val access: Access = Access.Anonymous,
    val clientCounts: Map<ClientLink, Int>? = null,
    val trendNr: List<Float> = emptyList(),
    val trendLte: List<Float> = emptyList(),
    val towerNames: Map<String, String> = emptyMap(),
    /** Latest speed test on this gateway. */
    val lastSpeed: SpeedRecord? = null,
)

class OverviewViewModel(private val c: AppContainer) : ViewModel() {
    private val counts = MutableStateFlow<Map<ClientLink, Int>?>(null)

    private val trend = c.profiles.active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else c.history.samples(p.id, System.currentTimeMillis() - 30 * 60_000L)
    }

    private val trendAndNames = combine(trend, c.settings.settings, c.speedTests.records) { samples, s, speeds -> Triple(samples, s.towerNames, speeds) }

    val ui: StateFlow<OverviewUi> = combine(c.status.state, c.profiles.state, c.session.access, counts, trendAndNames) { live, profiles, access, counts, (samples, names, speeds) ->
        OverviewUi(
            profile = profiles.active,
            profiles = profiles.profiles,
            live = live,
            access = access,
            clientCounts = counts,
            trendNr = samples.mapNotNull { it.nrSinr?.toFloat() },
            trendLte = samples.mapNotNull { it.lteSinr?.toFloat() },
            towerNames = names,
            lastSpeed = speeds.firstOrNull { it.profileId == profiles.active?.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUi())

    init {
        viewModelScope.launch {
            c.session.access.map { it == Access.LoggedIn }.collect { loggedIn ->
                if (loggedIn) {
                    loadCounts()
                } else {
                    counts.value = null
                }
            }
        }
    }

    private suspend fun loadCounts() {
        runCatching { c.session.authed { it.clients() }.flatten() }
            .onSuccess { list -> counts.value = list.filter { it.connected }.groupingBy { it.link }.eachCount() }
    }

    fun refresh() {
        c.status.refresh()
        viewModelScope.launch { if (c.session.access.value == Access.LoggedIn) loadCounts() }
    }

    fun switchTo(id: String) = viewModelScope.launch { c.profiles.setActive(id) }

    suspend fun report(): String {
        val p = c.profiles.active.value ?: return ""
        val main = c.status.state.value.main
        val cell = runCatching { c.session.authed { it.cellData() } }.getOrNull()
        val sim = runCatching { c.session.authed { it.simData() } }.getOrNull()
        return Report.build(p.name, main, cell, sim)
    }
}

@Composable
fun OverviewScreen() {
    val vm = containerViewModel { OverviewViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var shareRequest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shareRequest) {
        if (shareRequest == 0L) return@LaunchedEffect
        val text = vm.report()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share gateway report"))
    }
    OverviewContent(
        ui = ui,
        onRefresh = vm::refresh,
        onSwitch = { vm.switchTo(it) },
        onAddGateway = { actions.navigate(SetupRoute(addNew = true)) },
        onReboot = actions.askReboot,
        onShare = { shareRequest = System.currentTimeMillis() },
        onDevices = { actions.navigate(DevicesRoute) },
        onSignal = { actions.navigate(SignalRoute) },
        onGateway = { actions.navigate(GatewayInfoRoute) },
        onSpeedTest = { actions.navigate(SpeedTestRoute) },
    )
}

@Composable
fun OverviewContent(
    ui: OverviewUi,
    onRefresh: () -> Unit,
    onSwitch: (String) -> Unit,
    onAddGateway: () -> Unit,
    onReboot: () -> Unit,
    onShare: () -> Unit,
    onDevices: () -> Unit,
    onSignal: () -> Unit,
    onGateway: () -> Unit,
    onSpeedTest: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ProfileSwitcher(ui.profile, ui.profiles, onSwitch, onAddGateway) },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reboot gateway") }, leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) }, onClick = { menu = false; onReboot() })
                        DropdownMenuItem(text = { Text("Share report") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare() })
                    }
                },
            )
        },
    ) { padding ->
        val main = ui.live.main
        PullToRefreshBox(
            isRefreshing = ui.live.refreshing && main != null,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                main == null && ui.live.error != null -> ErrorBox(ui.live.error.userMessage(), offline = ui.live.offline, onRetry = onRefresh)
                main == null -> LoadingBox(text = "Reading the gateway…")
                else -> OverviewGrid(ui, main, onReboot, onDevices, onSignal, onGateway, onRefresh, onSpeedTest)
            }
        }
    }
}

@Composable
private fun ProfileSwitcher(profile: GatewayProfile?, profiles: List<GatewayProfile>, onSwitch: (String) -> Unit, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Column {
                Text(profile?.name ?: "Gateway", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.ArrowDropDown, "Switch gateway", tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(open, { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { Icon(Icons.Outlined.Router, null) },
                    trailingIcon = if (p.id == profile?.id) ({ Icon(Icons.Outlined.Check, null) }) else null,
                    onClick = { open = false; onSwitch(p.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add gateway") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { open = false; onAdd() })
        }
    }
}

@Composable
private fun OverviewGrid(
    ui: OverviewUi,
    main: MainData,
    onReboot: () -> Unit,
    onDevices: () -> Unit,
    onSignal: () -> Unit,
    onGateway: () -> Unit,
    onRefresh: () -> Unit,
    onSpeedTest: () -> Unit,
) {
    val nr = main.signal?.fiveG?.takeIf { it.rsrp != null || !it.bands.isNullOrEmpty() }
    val lte = main.signal?.fourG?.takeIf { it.rsrp != null || !it.bands.isNullOrEmpty() }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (ui.live.error != null) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Banner(
                    text = if (ui.live.offline) "Gateway not answering — showing the last reading from ${Format.ago(ui.live.updatedAt)}" else ui.live.error.userMessage(),
                    action = "Retry",
                    onAction = onRefresh,
                )
            }
        }
        item(span = StaggeredGridItemSpan.FullLine) { HeroCard(main, nr, lte, ui.live.updatedAt, onSignal) }
        if (nr != null) item { RadioCard("5G", nr, true, ui.towerNames) }
        if (lte != null) item { RadioCard("LTE", lte, false, ui.towerNames) }
        if (nr == null && lte == null) item {
            SectionCard("Radio", Icons.AutoMirrored.Outlined.ShowChart) { Text("The gateway reports no cellular connection right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (ui.trendNr.size > 2 || ui.trendLte.size > 2) item { TrendCard(ui.trendNr, ui.trendLte, onSignal) }
        item { DevicesCard(ui.clientCounts, ui.access, onDevices) }
        item { SpeedCard(ui.lastSpeed, onSpeedTest) }
        item { NetworkCard(main) }
        item { GatewayCard(main, onGateway, onReboot) }
    }
}

@Composable
private fun HeroCard(main: MainData, nr: BaseCellData?, lte: BaseCellData?, updatedAt: Long?, onSignal: () -> Unit) {
    val primary = nr ?: lte
    val quality = SignalQuality.overall(primary)
    val score = SignalQuality.score(primary)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    SectionCard(title = null, onClick = onSignal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score, quality)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            nr != null && lte != null -> "5G + LTE"
                            nr != null -> "5G"
                            lte != null -> "LTE"
                            else -> "No signal"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    QualityPill(quality)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BandPills(nr?.bands, nr = true)
                    BandPills(lte?.bands, nr = false)
                }
                Text(
                    listOfNotNull(
                        main.time?.upTime?.let { "Up ${Format.uptime(it)}" },
                        "Updated ${Format.ago(updatedAt, now)}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RadioCard(title: String, cell: BaseCellData, nr: Boolean, towerNames: Map<String, String> = emptyMap()) {
    val quality = SignalQuality.overall(cell)
    SectionCard(title = title, trailing = { SignalBars(cell.bars, quality) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricBar(Metric.RSRP, cell.rsrp, Modifier.weight(1f))
            MetricBar(Metric.SINR, cell.sinr, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricBar(Metric.RSRQ, cell.rsrq, Modifier.weight(1f))
            MetricBar(Metric.RSSI, cell.rssi, Modifier.weight(1f))
        }
        val ids = listOfNotNull(
            cell.nbid?.takeIf { it > 0 }?.let { towerNames[towerKey(nr, it)] },
            cell.nbid?.takeIf { it > 0 }?.let { "${if (nr) "gNB" else "eNB"} $it" },
            cell.cid?.takeIf { it > 0 }?.let { "cell $it" },
        )
        if (ids.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(ids.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendCard(nr: List<Float>, lte: List<Float>, onOpen: () -> Unit) {
    SectionCard("SINR, last 30 minutes", Icons.AutoMirrored.Outlined.ShowChart, onClick = onOpen, trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) }) {
        if (nr.size > 2) {
            Text("5G  ${nr.last().toInt()} dB", style = MaterialTheme.typography.labelMedium)
            Sparkline(nr, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(40.dp).padding(vertical = 4.dp))
        }
        if (lte.size > 2) {
            Text("LTE  ${lte.last().toInt()} dB", style = MaterialTheme.typography.labelMedium)
            Sparkline(lte, MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth().height(40.dp).padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun DevicesCard(counts: Map<ClientLink, Int>?, access: Access, onOpen: () -> Unit) {
    SectionCard("Connected devices", Icons.Outlined.Devices, onClick = onOpen, trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) }) {
        if (counts == null) {
            Text(
                if (access is Access.LoggingIn) "Logging in…" else "Log in to see who's connected.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val wifi = counts.filterKeys { it != ClientLink.Wired }.values.sum()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${counts.values.sum()}", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(8.dp))
                Text("online", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Wi-Fi $wifi", icon = Icons.Outlined.Wifi)
                Pill("Wired ${counts[ClientLink.Wired] ?: 0}", icon = Icons.Outlined.Cable)
                counts[ClientLink.TwoGig]?.let { Pill("2.4 GHz $it") }
                counts[ClientLink.FiveGig]?.let { Pill("5 GHz $it") }
                counts[ClientLink.SixGig]?.let { Pill("6 GHz $it") }
            }
        }
    }
}

@Composable
private fun NetworkCard(main: MainData) {
    val g = main.signal?.generic
    SectionCard("Network", Icons.Outlined.Language) {
        InfoRow("Registration", g?.registration?.replaceFirstChar(Char::uppercase))
        InfoRow("APN", g?.apn)
        InfoRow("IPv6", g?.hasIPv6?.let { if (it) "Yes" else "No" })
        InfoRow("Roaming", g?.roaming?.let { if (it) "Yes" else "No" })
    }
}

@Composable
private fun GatewayCard(main: MainData, onDetails: () -> Unit, onReboot: () -> Unit) {
    val d = main.device
    SectionCard("Gateway", Icons.Outlined.Router) {
        InfoRow("Model", d?.displayName)
        InfoRow("Firmware", d?.softwareVersion)
        InfoRow("Uptime", Format.uptime(main.time?.upTime))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onDetails) { Text("Details") }
            OutlinedButton(onClick = onReboot) {
                Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reboot")
            }
        }
    }
}

@Composable
private fun SpeedCard(last: SpeedRecord?, onOpen: () -> Unit) {
    SectionCard(
        "Speed test",
        Icons.Outlined.Speed,
        onClick = onOpen,
        trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open speed test") },
    ) {
        if (last == null) {
            Text("Measure download, upload and ping through this gateway.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "↓ ${last.downloadMbps?.let(::mbpsText) ?: "—"}  ↑ ${last.uploadMbps?.let(::mbpsText) ?: "—"} Mbps" + (last.pingMs?.let { " · ${it.toInt()} ms" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(Format.ago(last.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
