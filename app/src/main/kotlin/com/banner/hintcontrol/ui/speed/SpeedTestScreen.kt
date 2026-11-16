package com.banner.hintcontrol.ui.speed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.NetworkInfo
import com.banner.hintcontrol.data.SpeedRecord
import com.banner.hintcontrol.gateway.GatewayHttp
import com.banner.hintcontrol.gateway.speed.SpeedPhase
import com.banner.hintcontrol.gateway.speed.SpeedProgress
import com.banner.hintcontrol.gateway.speed.SpeedTest
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Locale

data class SpeedUi(
    val running: Boolean = false,
    val progress: SpeedProgress? = null,
    val error: String? = null,
    val confirmMobile: Boolean = false,
    val history: List<SpeedRecord> = emptyList(),
) {
    val latest: SpeedRecord? get() = history.firstOrNull()
}

class SpeedTestViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(SpeedUi())
    private var job: Job? = null

    // Includes the live status so the gateway keeps being polled; its signal is saved with each result.
    val ui: StateFlow<SpeedUi> = combine(local, c.speedTests.records, c.status.state) { l, records, _ -> l.copy(history = records) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUi())

    fun start(evenOnMobileData: Boolean = false) {
        if (job?.isActive == true) return
        val network = NetworkInfo.activeConnection(c.context)
        if (network == "Mobile data" && !evenOnMobileData) {
            local.update { it.copy(confirmMobile = true) }
            return
        }
        local.update { it.copy(confirmMobile = false, running = true, error = null, progress = SpeedProgress(SpeedPhase.Ping)) }
        job = viewModelScope.launch {
            try {
                val link = if (network == "Wi-Fi") NetworkInfo.wifiLinkMbps(c.context) else null
                val r = SpeedTest(GatewayHttp.base).run { p -> local.update { it.copy(progress = p) } }
                val main = c.status.state.value.main
                val profile = c.profiles.active.value
                val nr = main?.signal?.fiveG
                val lte = main?.signal?.fourG
                c.speedTests.add(
                    SpeedRecord(
                        time = System.currentTimeMillis(),
                        profileId = profile?.id,
                        gatewayName = profile?.name,
                        downloadMbps = r.downloadMbps,
                        uploadMbps = r.uploadMbps,
                        pingMs = r.pingMs,
                        jitterMs = r.jitterMs,
                        server = r.server,
                        bytesDown = r.bytesDown,
                        bytesUp = r.bytesUp,
                        network = network,
                        wifiLinkMbps = link,
                        nrBand = nr?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
                        nrRsrp = nr?.rsrp,
                        nrSinr = nr?.sinr,
                        lteBand = lte?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
                        lteRsrp = lte?.rsrp,
                        lteSinr = lte?.sinr,
                    ),
                )
                local.update { it.copy(running = false, progress = null) }
            } catch (e: CancellationException) {
                local.update { it.copy(running = false, progress = null) }
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is UnknownHostException, is ConnectException -> "Couldn't reach the speed test server. Check that the internet works and try again."
                    else -> e.message ?: "The speed test failed."
                }
                local.update { it.copy(running = false, progress = null, error = message) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissMobile() = local.update { it.copy(confirmMobile = false) }

    fun clear() = c.speedTests.clear()
}

@Composable
fun SpeedTestScreen() {
    val vm = containerViewModel { SpeedTestViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }
    SpeedTestContent(
        ui = ui,
        onBack = actions.back,
        onStart = { vm.start() },
        onStop = vm::stop,
        onRunOnMobile = { vm.start(evenOnMobileData = true) },
        onDismissMobile = vm::dismissMobile,
        onClear = vm::clear,
        onOokla = { openOokla(context) },
    )
}

@Composable
fun SpeedTestContent(
    ui: SpeedUi,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRunOnMobile: () -> Unit,
    onDismissMobile: () -> Unit,
    onClear: () -> Unit,
    onOokla: () -> Unit,
) {
    SubScreen("Speed test", onBack) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Internet speed", Icons.Outlined.Speed) {
                val p = ui.progress
                val shown = if (ui.running) p?.mbps else ui.latest?.downloadMbps
                val label = when {
                    ui.running -> when (p?.phase) {
                        SpeedPhase.Ping, null -> "Measuring ping…"
                        SpeedPhase.Download -> "Download"
                        SpeedPhase.Upload -> "Upload"
                    }
                    ui.latest != null -> "Download"
                    else -> "Not tested yet"
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(shown?.let(::mbpsText) ?: "—", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.width(6.dp))
                        Text("Mbps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    val ping = if (ui.running) p?.pingMs else ui.latest?.pingMs
                    if (ping != null) Text("Ping ${ping.toInt()} ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ui.running) {
                    LinearProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.latest == null) "Start test" else "Test again")
                    }
                }
                ui.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ui.latest?.takeIf { !ui.running }?.let { r -> ResultCard(r) }
            val earlier = ui.history.drop(1).take(10)
            if (earlier.isNotEmpty()) {
                SectionCard("Earlier tests", Icons.Outlined.History, trailing = { TextButton(onClick = onClear, enabled = !ui.running) { Text("Clear") } }) {
                    earlier.forEach { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                "↓ ${r.downloadMbps?.let(::mbpsText) ?: "—"}  ↑ ${r.uploadMbps?.let(::mbpsText) ?: "—"} Mbps" +
                                    (r.pingMs?.let { " · ${it.toInt()} ms" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                listOfNotNull(Format.dateTime(r.time), r.signalText()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = onOokla, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Speedtest by Ookla")
            }
            Text(
                "Measures this phone's connection through the gateway, using Cloudflare's speed test servers. A test uses up to about 400 MB, usually less.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (ui.confirmMobile) {
        AlertDialog(
            onDismissRequest = onDismissMobile,
            title = { Text("You're on mobile data") },
            text = { Text("This phone isn't on Wi-Fi, so the test won't measure your gateway, and it uses mobile data (up to about 400 MB).") },
            confirmButton = { TextButton(onClick = onRunOnMobile) { Text("Run anyway") } },
            dismissButton = { TextButton(onClick = onDismissMobile) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ResultCard(r: SpeedRecord) {
    SectionCard("Last result", null) {
        InfoRow("Download", r.downloadMbps?.let { "${mbpsText(it)} Mbps" })
        InfoRow("Upload", r.uploadMbps?.let { "${mbpsText(it)} Mbps" })
        InfoRow("Ping", r.pingMs?.let { "${it.toInt()} ms" })
        InfoRow("Jitter", r.jitterMs?.let { "${it.toInt()} ms" })
        InfoRow("Signal", r.signalText())
        InfoRow("Connection", listOfNotNull(r.network, r.wifiLinkMbps?.let { "phone link $it Mbps" }).joinToString(" · ").ifBlank { null })
        InfoRow("Server", r.server?.let { "Cloudflare ($it)" } ?: "Cloudflare")
        InfoRow("Data used", "${(r.bytesDown + r.bytesUp) / 1_000_000} MB")
        InfoRow("Tested", Format.dateTime(r.time))
    }
}

/** e.g. "5G n41 · RSRP -95 · SINR 10", or the LTE equivalent when there's no 5G. */
internal fun SpeedRecord.signalText(): String? = when {
    nrBand != null || nrRsrp != null -> listOfNotNull("5G", nrBand, nrRsrp?.let { "RSRP $it" }, nrSinr?.let { "SINR $it" }).joinToString(" ")
    lteBand != null || lteRsrp != null -> listOfNotNull("LTE", lteBand, lteRsrp?.let { "RSRP $it" }, lteSinr?.let { "SINR $it" }).joinToString(" ")
    else -> null
}

internal fun mbpsText(mbps: Double): String =
    if (mbps < 10) String.format(Locale.US, "%.1f", mbps) else mbps.toInt().toString()

private fun openOokla(context: Context) {
    val app = context.packageManager.getLaunchIntentForPackage(OOKLA_PACKAGE)
    val intent = app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedtest.net"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val OOKLA_PACKAGE = "org.zwanoo.android.speedtest"
