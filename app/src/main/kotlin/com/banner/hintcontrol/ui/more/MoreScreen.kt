package com.banner.hintcontrol.ui.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banner.hintcontrol.BuildConfig
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.db.GatewayEvent
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.MessageBox
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SectionHeader
import com.banner.hintcontrol.ui.components.SettingRow
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.AboutRoute
import com.banner.hintcontrol.ui.nav.AdminLoginRoute
import com.banner.hintcontrol.ui.nav.AlertsRoute
import com.banner.hintcontrol.ui.nav.AutomationRoute
import com.banner.hintcontrol.ui.nav.EventsRoute
import com.banner.hintcontrol.ui.nav.ExplorerRoute
import com.banner.hintcontrol.ui.nav.SpeedTestRoute
import com.banner.hintcontrol.ui.nav.GatewayInfoRoute
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.ProfilesRoute
import com.banner.hintcontrol.ui.nav.ScheduleRoute
import com.banner.hintcontrol.ui.nav.SettingsRoute
import com.banner.hintcontrol.ui.nav.UpdatesRoute
import com.banner.hintcontrol.ui.nav.containerViewModel
import com.banner.hintcontrol.update.UpdateState

data class MoreUi(
    val gatewayName: String = "Gateway",
    val scheduleSummary: String = "Off",
    val canChangeLogin: Boolean = false,
    val developerMode: Boolean = false,
    val updateAvailable: String? = null,
    val profileCount: Int = 1,
)

@Composable
fun MoreScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val ui = MoreUi(
        gatewayName = profiles.active?.name ?: "Gateway",
        scheduleSummary = settings.rebootSchedule.summary(),
        canChangeLogin = vm.capabilities()?.changeLogin == true,
        developerMode = settings.developerMode,
        updateAvailable = when (val u = update) {
            is UpdateState.Available -> u.release.tag
            is UpdateState.ReadyToInstall -> u.release.tag
            else -> null
        },
        profileCount = profiles.profiles.size,
    )
    MoreContent(ui)
}

@Composable
fun MoreContent(ui: MoreUi) {
    val actions = LocalAppActions.current
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                SectionHeader(ui.gatewayName)
                SettingRow("Gateway details", subtitle = "Model, firmware, SIM, report", icon = Icons.Outlined.Router, onClick = { actions.navigate(GatewayInfoRoute) })
                SettingRow("Speed test", subtitle = "Download, upload and ping", icon = Icons.Outlined.Speed, onClick = { actions.navigate(SpeedTestRoute) })
                SettingRow("Reboot now", icon = Icons.Outlined.RestartAlt, onClick = actions.askReboot)
                SettingRow("Scheduled reboot", subtitle = ui.scheduleSummary, icon = Icons.Outlined.Schedule, onClick = { actions.navigate(ScheduleRoute) })
                if (ui.canChangeLogin) SettingRow("Admin login", subtitle = "Change the gateway's username and password", icon = Icons.Outlined.Key, onClick = { actions.navigate(AdminLoginRoute) })
                SettingRow("Event log", subtitle = "Restarts, outages, band changes", icon = Icons.AutoMirrored.Outlined.ListAlt, onClick = { actions.navigate(EventsRoute) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader("App")
                SettingRow("Gateways", subtitle = "${ui.profileCount} saved", icon = Icons.Outlined.Badge, onClick = { actions.navigate(ProfilesRoute) })
                SettingRow("Alerts", subtitle = "Offline, restarts, weak signal, new devices", icon = Icons.Outlined.NotificationsActive, onClick = { actions.navigate(AlertsRoute) })
                SettingRow("Automation", subtitle = "Tasker, MacroDroid and others", icon = Icons.Outlined.Bolt, onClick = { actions.navigate(AutomationRoute) })
                SettingRow("Settings", icon = Icons.Outlined.Settings, onClick = { actions.navigate(SettingsRoute) })
                SettingRow(
                    "Updates",
                    subtitle = ui.updateAvailable?.let { "$it available" } ?: "Version ${BuildConfig.VERSION_NAME}",
                    icon = Icons.Outlined.SystemUpdate,
                    onClick = { actions.navigate(UpdatesRoute) },
                    trailing = if (ui.updateAvailable != null) ({ Badge { Text("New") } }) else null,
                )
                if (ui.developerMode) SettingRow("API explorer", subtitle = "Send raw requests to the gateway", icon = Icons.Outlined.Api, onClick = { actions.navigate(ExplorerRoute) })
                SettingRow("About", icon = Icons.Outlined.Info, onClick = { actions.navigate(AboutRoute) })
            }
        }
    }
}

@Composable
fun EventsScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val events by vm.events.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = LocalAppActions.current
    var confirm by remember { mutableStateOf(false) }
    SubScreen(
        "Event log",
        actions.back,
        actions = { IconButton(onClick = { confirm = true }, enabled = events.isNotEmpty()) { Icon(Icons.Outlined.DeleteOutline, "Clear") } },
        scroll = false,
    ) {
        EventList(events)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear the event log?") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.clearEvents() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun EventList(events: List<GatewayEvent>) {
    if (events.isEmpty()) {
        MessageBox(Icons.AutoMirrored.Outlined.ListAlt, "Nothing logged yet", "Restarts, outages, band changes and saved settings show up here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = when (e.type) {
                    EventType.Offline, EventType.ScheduledRebootFailed, EventType.WeakSignal -> MaterialTheme.colorScheme.error
                    EventType.Online, EventType.RebootDetected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(eventIcon(e.type), null, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.type.label, style = MaterialTheme.typography.bodyLarge)
                    e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.width(8.dp))
                Text(Format.dateTime(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

private fun eventIcon(t: EventType) = when (t) {
    EventType.RebootDetected, EventType.RebootSent, EventType.ScheduledReboot, EventType.ScheduledRebootFailed -> Icons.Outlined.RestartAlt
    EventType.Offline, EventType.Online -> Icons.Outlined.Router
    EventType.BandChange, EventType.WeakSignal -> Icons.Outlined.Bolt
    EventType.NewDevice -> Icons.Outlined.Badge
    EventType.WifiSaved -> Icons.Outlined.Settings
    EventType.LoginChanged -> Icons.Outlined.Key
}

@Composable
fun AboutScreen() {
    val actions = LocalAppActions.current
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    SubScreen("About", actions.back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Hint Plus", Icons.Outlined.Info) {
                Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "View and control T-Mobile Home Internet gateways. Not made by or affiliated with T-Mobile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { open("https://github.com/${BuildConfig.UPDATE_REPO}") }) { Text("Source code on GitHub") }
            }
            SectionCard("Credits", null) {
                Text(
                    "Based on HINT Control by Zachary Wander (zacharee), MIT License. The gateway communication, data formats and safety warnings come from that project.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { open("https://github.com/${BuildConfig.UPSTREAM_REPO}") }) { Text("Original HINT Control") }
            }
            SectionCard("Open-source licences", null) {
                listOf(
                    "HINT Control — MIT (c) 2023 Zachary Wander",
                    "AndroidX, Jetpack Compose, Material 3, Room, WorkManager, Glance, DataStore — Apache 2.0",
                    "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache 2.0",
                    "OkHttp — Apache 2.0",
                    "ZXing (QR codes) — Apache 2.0",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            SectionCard("Privacy", null) {
                Text(
                    "Everything stays on this phone. The app talks to your gateway on the local network and, when update checks are on, to GitHub to look for new versions. No analytics, no crash uploads. Saved gateway passwords are encrypted with the Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
