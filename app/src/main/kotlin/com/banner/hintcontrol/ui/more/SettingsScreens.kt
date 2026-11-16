package com.banner.hintcontrol.ui.more

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banner.hintcontrol.automation.AutomationReceiver
import com.banner.hintcontrol.data.AppSettings
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.ThemeMode
import com.banner.hintcontrol.ui.components.Dropdown
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SectionHeader
import com.banner.hintcontrol.ui.components.SettingRow
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.SetupRoute
import com.banner.hintcontrol.ui.nav.containerViewModel

private val refreshOptions = listOf(0, 2, 5, 10, 30, 60)
private fun refreshLabel(s: Int) = if (s == 0) "Off (pull to refresh)" else "Every $s s"
private val backgroundOptions = listOf(0, 15, 30, 60, 180)
private fun backgroundLabel(m: Int) = when (m) {
    0 -> "Off"
    in 1..59 -> "Every $m min"
    60 -> "Every hour"
    else -> "Every ${m / 60} h"
}

@Composable
fun SettingsScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val samples by vm.sampleCount.collectAsStateWithLifecycle(initialValue = 0)
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf<String?>(null) }
    SubScreen("Settings", actions.back) {
        SettingsBody(s, samples, vm::set, onClearHistory = { confirmClear = true }, onCrash = { crash = vm.crashLog() ?: "No crash recorded." })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete signal history?") },
            text = { Text("$samples readings for this gateway are deleted.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("Last crash") },
            text = { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash log"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { vm.deleteCrashLog(); crash = null }) { Text("Delete") } },
        )
    }
}

@Composable
fun SettingsBody(
    s: AppSettings,
    samples: Int,
    set: ((AppSettings) -> AppSettings) -> Unit,
    onClearHistory: () -> Unit,
    onCrash: () -> Unit,
) {
    SectionHeader("Appearance")
    Column(Modifier.padding(horizontal = 16.dp)) {
        Dropdown("Theme", ThemeMode.entries, s.theme, { m -> set { it.copy(theme = m) } }, Modifier.fillMaxWidth(), text = {
            when (it) {
                ThemeMode.System -> "Follow system"
                ThemeMode.Light -> "Light"
                ThemeMode.Dark -> "Dark"
            }
        })
    }
    if (Build.VERSION.SDK_INT >= 31) {
        SettingRow("Colours from wallpaper", trailing = { Switch(s.dynamicColor, { v -> set { it.copy(dynamicColor = v) } }) })
    }
    SettingRow("Pure black in dark theme", trailing = { Switch(s.pureBlack, { v -> set { it.copy(pureBlack = v) } }) })

    SectionHeader("Refresh")
    Column(Modifier.padding(horizontal = 16.dp)) {
        Dropdown("While the app is open", refreshOptions, s.refreshSeconds, { v -> set { it.copy(refreshSeconds = v) } }, Modifier.fillMaxWidth(), text = ::refreshLabel)
        Spacer(Modifier.height(10.dp))
        Dropdown("In the background (history, alerts, widget)", backgroundOptions, s.backgroundMinutes, { v -> set { it.copy(backgroundMinutes = v) } }, Modifier.fillMaxWidth(), text = ::backgroundLabel)
    }

    SectionHeader("Signal history")
    SettingRow("Record history", subtitle = "$samples readings stored for this gateway", trailing = { Switch(s.recordHistory, { v -> set { it.copy(recordHistory = v) } }) })
    Column(Modifier.padding(horizontal = 16.dp)) {
        Dropdown("Keep readings for", listOf(7, 30, 90, 365), s.historyDays, { v -> set { it.copy(historyDays = v) } }, Modifier.fillMaxWidth(), text = { "$it days" })
    }
    SettingRow("Delete history", onClick = onClearHistory)

    SectionHeader("Placement mode")
    SettingRow("Sound", trailing = { Switch(s.placementSound, { v -> set { it.copy(placementSound = v) } }) })
    SettingRow("Vibration", trailing = { Switch(s.placementHaptics, { v -> set { it.copy(placementHaptics = v) } }) })
    SettingRow("Keep screen on", trailing = { Switch(s.keepScreenOn, { v -> set { it.copy(keepScreenOn = v) } }) })

    SectionHeader("Updates")
    SettingRow("Check for updates automatically", trailing = { Switch(s.autoUpdateCheck, { v -> set { it.copy(autoUpdateCheck = v) } }) })
    SettingRow("Include test builds (pre-releases)", trailing = { Switch(s.includePrereleases, { v -> set { it.copy(includePrereleases = v) } }) })

    SectionHeader("Advanced")
    SettingRow("Developer options", subtitle = "Shows the API explorer", trailing = { Switch(s.developerMode, { v -> set { it.copy(developerMode = v) } }) })
    SettingRow("Last crash", onClick = onCrash)
}

@Composable
fun AlertsScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    SubScreen("Alerts", actions.back) {
        if (!granted) {
            SectionCard("Notifications are off", null, modifier = Modifier.padding(16.dp)) {
                Text("Allow notifications so alerts can reach you.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
        }
        AlertsBody(s, vm::set)
    }
}

@Composable
fun AlertsBody(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    SectionHeader("Background checks")
    Column(Modifier.padding(horizontal = 16.dp)) {
        Dropdown("Check the gateway", backgroundOptions, s.backgroundMinutes, { v -> set { it.copy(backgroundMinutes = v) } }, Modifier.fillMaxWidth(), text = ::backgroundLabel)
        if (s.backgroundMinutes == 0) {
            Spacer(Modifier.height(6.dp))
            Text("Alerts need background checks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    SectionHeader("Tell me when")
    SettingRow("The gateway goes offline", subtitle = "Only while this phone is on its network", trailing = { Switch(s.alertOffline, { v -> set { it.copy(alertOffline = v) } }) })
    SettingRow("The gateway restarted", trailing = { Switch(s.alertReboot, { v -> set { it.copy(alertReboot = v) } }) })
    SettingRow("A new device joins", subtitle = "Needs a saved admin password", trailing = { Switch(s.alertNewDevice, { v -> set { it.copy(alertNewDevice = v) } }) })
    SettingRow("Signal gets weak", subtitle = "SINR below ${s.weakSinrThreshold} dB on two checks in a row", trailing = { Switch(s.alertWeakSignal, { v -> set { it.copy(alertWeakSignal = v) } }) })
    if (s.alertWeakSignal) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Slider(
                value = s.weakSinrThreshold.toFloat(),
                onValueChange = { v -> set { it.copy(weakSinrThreshold = v.toInt()) } },
                valueRange = -5f..15f,
                steps = 19,
            )
        }
    }
}

@Composable
fun ProfilesScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.profiles.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var editing by remember { mutableStateOf<GatewayProfile?>(null) }
    var deleting by remember { mutableStateOf<GatewayProfile?>(null) }
    SubScreen("Gateways", actions.back) {
        state.profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.id == state.active?.id, onClick = { vm.setActive(p.id) })
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(p.kind.label, p.host.takeIf { !p.isDemo }, if (p.hasSavedPassword) "password saved" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { editing = p }) { Icon(Icons.Outlined.Edit, "Edit ${p.name}") }
                if (p.hasSavedPassword) IconButton(onClick = { vm.forgetPassword(p) }) { Icon(Icons.Outlined.KeyOff, "Forget password") }
                IconButton(onClick = { deleting = p }) { Icon(Icons.Outlined.DeleteOutline, "Remove ${p.name}") }
            }
            HorizontalDivider(Modifier.padding(start = 56.dp))
        }
        OutlinedButton(onClick = { actions.navigate(SetupRoute(addNew = true)) }, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add gateway")
        }
    }
    editing?.let { p ->
        var name by rememberSaveable(p.id) { mutableStateOf(p.name) }
        var host by rememberSaveable(p.id) { mutableStateOf(p.host) }
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(Icons.Outlined.Router, null) },
            title = { Text("Edit gateway") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    if (!p.isDemo) OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.rename(p, name, host); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text("Its saved password is removed from this phone. Signal history stays until you delete it.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(p.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AutomationScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val clipboard = LocalClipboardManager.current
    SubScreen("Automation", actions.back) {
        SettingRow(
            "Allow automation apps",
            subtitle = "Reboot or refresh from Tasker, MacroDroid, Automate…",
            trailing = { Switch(s.automationEnabled, { v -> if (v && s.automationToken.isEmpty()) vm.newAutomationToken() else vm.set { it.copy(automationEnabled = v) } }) },
        )
        if (s.automationEnabled) {
            SectionCard("How to use", null, modifier = Modifier.padding(16.dp)) {
                Text("Send a broadcast intent with:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                CodeLine("Action (reboot)", AutomationReceiver.ACTION_REBOOT) { clipboard.setText(AnnotatedString(it)) }
                CodeLine("Action (refresh widget)", AutomationReceiver.ACTION_REFRESH) { clipboard.setText(AnnotatedString(it)) }
                CodeLine("Package", "com.banner.hintcontrol") { clipboard.setText(AnnotatedString(it)) }
                CodeLine("Extra", "token:${s.automationToken}") { clipboard.setText(AnnotatedString(it)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keep the token private: any app that has it can reboot your gateway. Make a new one if it leaks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { vm.newAutomationToken() }) { Text("Make a new token") }
            }
        }
    }
}

@Composable
private fun CodeLine(label: String, value: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { onCopy(value) }) { Icon(Icons.Outlined.ContentCopy, "Copy $label") }
    }
}

@Composable
fun DayChips(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            val d = i + 1
            FilterChip(selected = d in days, onClick = { onChange(if (d in days) days - d else days + d) }, label = { Text(n) })
        }
    }
}
