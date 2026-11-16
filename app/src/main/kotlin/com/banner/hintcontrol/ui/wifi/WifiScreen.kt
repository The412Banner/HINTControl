package com.banner.hintcontrol.ui.wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.EncryptionModes
import com.banner.hintcontrol.gateway.model.EncryptionVersions
import com.banner.hintcontrol.gateway.model.SSIDConfig
import com.banner.hintcontrol.gateway.model.TransmissionPowers
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.WifiConfig
import com.banner.hintcontrol.gateway.model.WifiRules
import com.banner.hintcontrol.ui.components.Dropdown
import com.banner.hintcontrol.ui.components.ErrorBox
import com.banner.hintcontrol.ui.components.LoadingBox
import com.banner.hintcontrol.ui.components.LoginPrompt
import com.banner.hintcontrol.ui.components.PasswordField
import com.banner.hintcontrol.ui.components.Pill
import com.banner.hintcontrol.ui.components.QrCode
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.wifiQrText
import com.banner.hintcontrol.ui.nav.containerViewModel

@Composable
fun WifiScreen() {
    val vm = containerViewModel { WifiViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    WifiContent(ui, WifiActions(vm))
}

class WifiActions(
    val refresh: () -> Unit,
    val login: (String, Boolean) -> Unit,
    val setRadio: (WifiBand, Boolean) -> Unit,
    val setChannel: (WifiBand, String) -> Unit,
    val setWidth: (WifiBand, String) -> Unit,
    val setPower: (WifiBand, String) -> Unit,
    val setBandSteering: (Boolean) -> Unit,
    val updateNetwork: (Int, SSIDConfig) -> Unit,
    val addNetwork: (SSIDConfig) -> Unit,
    val removeNetwork: (Int) -> Unit,
    val discard: () -> Unit,
    val save: () -> Unit,
    val dismissMessage: () -> Unit,
) {
    constructor(vm: WifiViewModel) : this(
        vm::load, vm::login, vm::setRadio, vm::setChannel, vm::setWidth, vm::setPower, vm::setBandSteering,
        vm::updateNetwork, vm::addNetwork, vm::removeNetwork, vm::discard, vm::save, vm::dismissMessage,
    )

    companion object {
        val None = WifiActions({}, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, {})
    }
}

@Composable
fun WifiContent(ui: WifiUi, actions: WifiActions) {
    var editing by remember { mutableStateOf<Pair<Int?, SSIDConfig>?>(null) }
    var sharing by remember { mutableStateOf<SSIDConfig?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    val changes = ui.changes
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi") },
                actions = { IconButton(onClick = actions.refresh, enabled = !ui.saving) { Icon(Icons.Outlined.Refresh, "Reload") } },
            )
        },
        bottomBar = {
            AnimatedVisibility(changes.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${changes.size} unsaved change${if (changes.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = actions.discard, enabled = !ui.saving) { Text("Discard") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { confirmSave = true }, enabled = !ui.saving) { Text("Review & save") }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val config = ui.config
            when {
                ui.needsLogin && config == null -> LoginPrompt(ui.loginBusy, ui.loginError, actions.login)
                ui.loading && config == null -> LoadingBox(text = "Reading Wi-Fi settings…")
                ui.error != null && config == null -> ErrorBox(ui.error, onRetry = actions.refresh)
                config != null -> WifiBody(ui, config, actions, onEdit = { i, s -> editing = i to s }, onShare = { sharing = it }, onDelete = { deleting = it })
            }
            if (ui.saving) {
                Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize()) {}
                Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.align(Alignment.Center)) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Saving to the gateway…", style = MaterialTheme.typography.titleMedium)
                        Text("Wi-Fi may drop for a moment.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    editing?.let { (index, ssid) ->
        NetworkEditor(
            initial = ssid,
            isNew = index == null,
            hasSixGhz = ui.config?.sixGig != null,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (index == null) actions.addNetwork(updated) else actions.updateNetwork(index, updated)
                editing = null
            },
        )
    }
    sharing?.let { s ->
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text(s.ssidName.orEmpty()) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrCode(wifiQrText(s.ssidName.orEmpty(), s.wpaKey, s.encryptionVersion.orEmpty(), s.isBroadcastEnabled == false))
                    Spacer(Modifier.height(12.dp))
                    Text("Scan with a phone camera to join.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { sharing = null }) { Text("Done") } },
        )
    }
    deleting?.let { i ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove \"${ui.config?.ssids?.getOrNull(i)?.ssidName}\"?") },
            text = { Text("It is removed when you save. Devices on it will disconnect.") },
            confirmButton = { TextButton(onClick = { actions.removeNetwork(i); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (confirmSave) {
        val warnings = ui.warnings
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            icon = if (warnings.isNotEmpty()) ({ Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) }) else null,
            title = { Text("Save these changes?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    warnings.forEach { w ->
                        Spacer(Modifier.height(4.dp))
                        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                            Text(w.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmSave = false; actions.save() }) {
                    Text(if (warnings.isEmpty()) "Save" else "Save anyway", color = if (warnings.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
        )
    }
    (ui.saveError ?: ui.savedMessage)?.let { msg ->
        AlertDialog(
            onDismissRequest = actions.dismissMessage,
            title = { Text(if (ui.saveError != null) "Couldn't save" else "Saved") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = actions.dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun WifiBody(
    ui: WifiUi,
    config: WifiConfig,
    actions: WifiActions,
    onEdit: (Int?, SSIDConfig) -> Unit,
    onShare: (SSIDConfig) -> Unit,
    onDelete: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Networks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            config.ssids.orEmpty().forEachIndexed { i, s ->
                NetworkCard(
                    ssid = s,
                    canDelete = config.canAddAndRemove && i > 0,
                    canToggle = s.enabled != null,
                    onEdit = { onEdit(i, s) },
                    onShare = { onShare(s) },
                    onDelete = { onDelete(i) },
                    onToggle = { actions.updateNetwork(i, s.copy(enabled = it)) },
                )
            }
            if (config.canAddAndRemove) {
                OutlinedButton(
                    onClick = {
                        onEdit(
                            null,
                            SSIDConfig(
                                twoGigSsid = true,
                                fiveGigSsid = true,
                                sixGigSsid = if (config.sixGig != null) true else null,
                                encryptionMode = EncryptionModes.AES,
                                encryptionVersion = EncryptionVersions.WPA2_WPA3,
                                guest = false,
                                isBroadcastEnabled = true,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add network")
                }
            }
            val bands = config.availableBands
            if (bands.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Radios", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
                bands.forEach { band -> config.band(band)?.let { RadioCard(band, it, actions) } }
            }
            config.bandSteering?.let { steering ->
                SectionCard("Band steering", Icons.Outlined.Router, trailing = { Switch(steering.isEnabled, actions.setBandSteering) }) {
                    Text("Moves devices between 2.4 and 5 GHz on one network name.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(
    ssid: SSIDConfig,
    canDelete: Boolean,
    canToggle: Boolean,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var reveal by rememberSaveable(ssid.ssidName) { mutableStateOf(false) }
    SectionCard(
        title = ssid.ssidName ?: "(no name)",
        icon = Icons.Outlined.Wifi,
        trailing = if (canToggle) ({ Switch(ssid.enabled == true, onToggle) }) else null,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WifiBand.entries.filter { ssid.onBand(it) }.forEach { Pill(it.label) }
            ssid.encryptionVersion?.let { Pill(it, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ssid.isBroadcastEnabled == false) Pill("Hidden", icon = Icons.Outlined.VisibilityOff, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
            if (ssid.guest == true) Pill("Guest", color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (reveal) ssid.wpaKey.orEmpty() else "•".repeat((ssid.wpaKey?.length ?: 8).coerceIn(8, 16)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide password" else "Show password") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
            TextButton(onClick = onShare, enabled = !ssid.ssidName.isNullOrEmpty()) { Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
            if (canDelete) TextButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove") }
        }
    }
}

@Composable
private fun RadioCard(band: WifiBand, config: BandConfig, actions: WifiActions) {
    SectionCard(band.label, null, trailing = { Switch(config.isRadioEnabled == true, { actions.setRadio(band, it) }) }) {
        // Only show what the gateway reports: the G5AR, for one, has just the on/off switch
        // (HINT Control #120), and offering "Auto" pickers there would change nothing.
        val channel = config.channel
        val width = config.channelBandwidth
        if (channel == null && width == null && config.transmissionPower == null) {
            Text("This gateway only lets you turn this radio on or off.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (channel != null || width != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (channel != null) Dropdown(
                label = "Channel",
                options = (listOf(WifiBand.AUTO) + band.channels + channel).distinct(),
                selected = channel,
                onSelect = { actions.setChannel(band, it) },
                modifier = Modifier.weight(1f),
                enabled = config.isRadioEnabled == true,
            )
            if (width != null) Dropdown(
                label = "Width",
                options = (listOf(WifiBand.AUTO) + band.bandwidths + width).distinct(),
                selected = width,
                onSelect = { actions.setWidth(band, it) },
                modifier = Modifier.weight(1f),
                enabled = config.isRadioEnabled == true,
            )
        }
        config.transmissionPower?.let { power ->
            if (channel != null || width != null) Spacer(Modifier.height(12.dp))
            Text("Transmit power", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val options = (TransmissionPowers.all + power).distinct()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, p ->
                    SegmentedButton(selected = power == p, onClick = { actions.setPower(band, p) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), enabled = config.isRadioEnabled == true) {
                        Text(if (p == TransmissionPowers.FULL) "Full" else if (p == TransmissionPowers.HALF) "Half" else p)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkEditor(
    initial: SSIDConfig,
    isNew: Boolean,
    hasSixGhz: Boolean,
    onDismiss: () -> Unit,
    onSave: (SSIDConfig) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    val nameOk = WifiRules.isValidSsid(s.ssidName)
    val keyOk = WifiRules.isValidWpaKey(s.wpaKey)
    val bandsOk = !s.canEditFrequencyAndGuest || WifiBand.entries.any { s.onBand(it) }
    val canSave = nameOk && keyOk && bandsOk
    // Full screen rather than an AlertDialog: this form is taller than the space above the keyboard,
    // and a tall AlertDialog ended up drawn over the keyboard (seen on the Pocket FIT, Android 14).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (isNew) "New network" else "Edit network") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cancel") } },
                        actions = { TextButton(onClick = { onSave(s) }, enabled = canSave) { Text("Done") } },
                    )
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = s.ssidName.orEmpty(),
                            onValueChange = { s = s.copy(ssidName = it) },
                            label = { Text("Network name") },
                            singleLine = true,
                            isError = s.ssidName != null && !nameOk,
                            supportingText = { Text("${s.ssidName.orEmpty().encodeToByteArray().size}/32 bytes") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PasswordField(
                            value = s.wpaKey.orEmpty(),
                            onValueChange = { s = s.copy(wpaKey = it) },
                            label = "Password",
                            isError = s.wpaKey != null && !keyOk,
                            supporting = "8–63 characters, letters, numbers and symbols",
                        )
                        Dropdown("Security", EncryptionVersions.all, s.encryptionVersion ?: EncryptionVersions.WPA2_WPA3, { s = s.copy(encryptionVersion = it) }, Modifier.fillMaxWidth())
                        Dropdown("Encryption", EncryptionModes.all, s.encryptionMode ?: EncryptionModes.AES, { s = s.copy(encryptionMode = it) }, Modifier.fillMaxWidth())
                        if (s.canEditFrequencyAndGuest) {
                            Text("Bands", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                WifiBand.entries.filter { it != WifiBand.SixGig || hasSixGhz }.forEach { band ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(s.onBand(band), { s = s.withBand(band, it) })
                                        Text(band.label)
                                    }
                                }
                            }
                            if (!bandsOk) Text("Pick at least one band.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        ToggleRow("Visible", "Broadcast the network name", s.isBroadcastEnabled != false) { s = s.copy(isBroadcastEnabled = it) }
                        if (s.canEditFrequencyAndGuest) ToggleRow("Guest network", null, s.guest == true) { s = s.copy(guest = it) }
                        Button(onClick = { onSave(s) }, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
