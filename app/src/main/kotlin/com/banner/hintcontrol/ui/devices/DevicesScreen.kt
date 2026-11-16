package com.banner.hintcontrol.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.db.KnownDevice
import com.banner.hintcontrol.data.needsLogin
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.model.Client
import com.banner.hintcontrol.gateway.model.ClientLink
import com.banner.hintcontrol.gateway.model.flatten
import com.banner.hintcontrol.ui.components.ErrorBox
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.LoadingBox
import com.banner.hintcontrol.ui.components.LoginPrompt
import com.banner.hintcontrol.ui.components.MessageBox
import com.banner.hintcontrol.ui.components.Pill
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceFilter(val label: String) { All("All"), Online("Online"), WiFi("Wi-Fi"), Wired("Wired"), New("New") }

enum class DeviceSort(val label: String) { Name("Name"), Address("IP address"), Connection("Connection"), FirstSeen("First seen") }

data class DeviceItem(
    val client: Client,
    val alias: String?,
    val firstSeen: Long?,
    val isNew: Boolean,
) {
    val title: String get() = alias ?: client.name ?: "Unknown device"
}

data class DevicesUi(
    val loading: Boolean = true,
    val error: String? = null,
    val needsLogin: Boolean = false,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val items: List<DeviceItem> = emptyList(),
    val query: String = "",
    val filter: DeviceFilter = DeviceFilter.All,
    val sort: DeviceSort = DeviceSort.Name,
    val updatedAt: Long? = null,
) {
    val visible: List<DeviceItem>
        get() = items
            .filter {
                when (filter) {
                    DeviceFilter.All -> true
                    DeviceFilter.Online -> it.client.connected
                    DeviceFilter.WiFi -> it.client.link != ClientLink.Wired
                    DeviceFilter.Wired -> it.client.link == ClientLink.Wired
                    DeviceFilter.New -> it.isNew
                }
            }
            .filter { d ->
                query.isBlank() || listOfNotNull(d.title, d.client.name, d.client.ipv4, d.client.mac).any { it.contains(query.trim(), ignoreCase = true) }
            }
            .let { list ->
                when (sort) {
                    DeviceSort.Name -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    DeviceSort.Address -> list.sortedBy { ipKey(it.client.ipv4) }
                    DeviceSort.Connection -> list.sortedWith(compareBy({ it.client.link.ordinal }, { it.title.lowercase() }))
                    DeviceSort.FirstSeen -> list.sortedByDescending { it.firstSeen ?: 0 }
                }
            }
}

private fun ipKey(ip: String?): Long = ip?.split('.')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 4 }
    ?.fold(0L) { acc, part -> acc * 256 + part } ?: Long.MAX_VALUE

fun linkLabel(link: ClientLink): String = when (link) {
    ClientLink.Wired -> "Ethernet"
    ClientLink.TwoGig -> "Wi-Fi 2.4 GHz"
    ClientLink.FiveGig -> "Wi-Fi 5 GHz"
    ClientLink.SixGig -> "Wi-Fi 6 GHz"
    ClientLink.Wireless -> "Wi-Fi"
}

class DevicesViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(DevicesUi())
    private val clients = MutableStateFlow<List<Client>?>(null)
    private val known = c.profiles.active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else c.history.devices(p.id) }

    val ui: StateFlow<DevicesUi> = combine(local, clients, known) { l, list, known ->
        val byKey = known.associateBy { it.deviceKey }
        val baseline = known.minOfOrNull { it.firstSeen }
        val now = System.currentTimeMillis()
        l.copy(items = list.orEmpty().map { cl ->
            val k: KnownDevice? = byKey[cl.key]
            DeviceItem(
                client = cl,
                alias = k?.alias,
                firstSeen = k?.firstSeen,
                isNew = k != null && baseline != null && k.firstSeen - baseline > 60_000 && now - k.firstSeen < 86_400_000L,
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUi())

    fun load() {
        viewModelScope.launch {
            local.update { it.copy(loading = clients.value == null, error = null) }
            try {
                val list = c.session.authed { it.clients() }.flatten()
                c.profiles.active.value?.let { c.history.updateDevices(it.id, list) }
                clients.value = list
                local.update { it.copy(loading = false, needsLogin = false, updatedAt = System.currentTimeMillis()) }
            } catch (e: Exception) {
                local.update { it.copy(loading = false, error = e.userMessage(), needsLogin = e.needsLogin) }
            }
        }
    }

    fun login(password: String, remember: Boolean) {
        viewModelScope.launch {
            local.update { it.copy(loginBusy = true, loginError = null) }
            try {
                c.session.login(password, remember)
                local.update { it.copy(loginBusy = false, needsLogin = false) }
                load()
            } catch (e: Exception) {
                local.update { it.copy(loginBusy = false, loginError = e.userMessage()) }
            }
        }
    }

    fun setQuery(q: String) = local.update { it.copy(query = q) }
    fun setFilter(f: DeviceFilter) = local.update { it.copy(filter = f) }
    fun setSort(s: DeviceSort) = local.update { it.copy(sort = s) }

    fun rename(item: DeviceItem, alias: String?) {
        viewModelScope.launch {
            val p = c.profiles.active.value ?: return@launch
            c.history.setAlias(p.id, item.client.key, alias)
        }
    }

    val access get() = c.session.access
}

@Composable
fun DevicesScreen() {
    val vm = containerViewModel { DevicesViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    LaunchedEffect(access) { if (access == Access.LoggedIn && ui.needsLogin) vm.load() }
    LaunchedEffect(Unit) {
        while (true) {
            vm.load()
            delay(30_000)
        }
    }
    DevicesContent(ui, vm::load, vm::login, vm::setQuery, vm::setFilter, vm::setSort, vm::rename)
}

@Composable
fun DevicesContent(
    ui: DevicesUi,
    onRefresh: () -> Unit,
    onLogin: (String, Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (DeviceFilter) -> Unit,
    onSort: (DeviceSort) -> Unit,
    onRename: (DeviceItem, String?) -> Unit,
) {
    var selected by remember { mutableStateOf<DeviceItem?>(null) }
    var renaming by remember { mutableStateOf<DeviceItem?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Devices")
                        if (ui.items.isNotEmpty()) {
                            Text(
                                "${ui.items.count { it.client.connected }} online · updated ${Format.ago(ui.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                    DropdownMenu(menu, { menu = false }) {
                        DeviceSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { menu = false; onSort(s) }, trailingIcon = if (s == ui.sort) ({ Text("✓") }) else null)
                        }
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.needsLogin && ui.items.isEmpty() -> LoginPrompt(ui.loginBusy, ui.loginError, onLogin)
                ui.loading && ui.items.isEmpty() -> LoadingBox(text = "Reading connected devices…")
                ui.error != null && ui.items.isEmpty() -> ErrorBox(ui.error, onRetry = onRefresh)
                else -> PullToRefreshBox(isRefreshing = ui.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    DeviceList(ui, onQuery, onFilter) { selected = it }
                }
            }
        }
    }
    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            DeviceDetails(item, onRename = { renaming = item; selected = null })
        }
    }
    renaming?.let { item ->
        var text by rememberSaveable(item.client.key) { mutableStateOf(item.alias ?: item.client.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this device") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { onRename(item, text); renaming = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (item.alias != null) TextButton(onClick = { onRename(item, null); renaming = null }) { Text("Reset") }
                    TextButton(onClick = { renaming = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun DeviceList(ui: DevicesUi, onQuery: (String) -> Unit, onFilter: (DeviceFilter) -> Unit, onOpen: (DeviceItem) -> Unit) {
    val visible = ui.visible
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(
                value = ui.query,
                onValueChange = onQuery,
                placeholder = { Text("Search name, IP or MAC") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DeviceFilter.entries) { f ->
                    val count = when (f) {
                        DeviceFilter.All -> ui.items.size
                        DeviceFilter.Online -> ui.items.count { it.client.connected }
                        DeviceFilter.WiFi -> ui.items.count { it.client.link != ClientLink.Wired }
                        DeviceFilter.Wired -> ui.items.count { it.client.link == ClientLink.Wired }
                        DeviceFilter.New -> ui.items.count { it.isNew }
                    }
                    FilterChip(ui.filter == f, { onFilter(f) }, label = { Text("${f.label} $count") })
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    MessageBox(Icons.Outlined.DevicesOther, "No devices", if (ui.query.isNotBlank()) "Nothing matches \"${ui.query}\"." else null)
                }
            }
        }
        items(visible, key = { it.client.key }) { d ->
            DeviceRow(d, Modifier.clickable { onOpen(d) })
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceItem, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = if (d.client.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (d.client.connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (d.client.link == ClientLink.Wired) Icons.Outlined.Cable else Icons.Outlined.Wifi, null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (d.isNew) {
                    Spacer(Modifier.width(8.dp))
                    Pill("New", color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                listOfNotNull(d.client.ipv4, linkLabel(d.client.link), if (!d.client.connected) "offline" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        d.client.signal?.let {
            Text("$it dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceDetails(d: DeviceItem, onRename: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "Rename") }
        }
        if (d.alias != null && d.client.name != null && d.alias != d.client.name) {
            Text("Reported name: ${d.client.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        InfoRow("Status", if (d.client.connected) "Online" else "Offline")
        InfoRow("Connection", linkLabel(d.client.link))
        InfoRow("IPv4", d.client.ipv4, copyable = true)
        d.client.ipv6.forEach { InfoRow("IPv6", it, mono = true, copyable = true) }
        InfoRow("MAC", d.client.mac, mono = true, copyable = true)
        if (d.client.isPrivateMac) InfoRow("MAC type", "Private (randomised by the device)")
        d.client.signal?.let { InfoRow("Wi-Fi signal", "$it dBm") }
        d.firstSeen?.let { InfoRow("First seen", Format.dateTime(it)) }
    }
}
