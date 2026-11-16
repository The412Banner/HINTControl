package com.banner.hintcontrol.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.RebootPhase
import com.banner.hintcontrol.ui.devices.DevicesScreen
import com.banner.hintcontrol.ui.more.AboutScreen
import com.banner.hintcontrol.ui.more.AdminLoginScreen
import com.banner.hintcontrol.ui.more.AlertsScreen
import com.banner.hintcontrol.ui.more.AutomationScreen
import com.banner.hintcontrol.ui.more.EventsScreen
import com.banner.hintcontrol.ui.more.ExplorerScreen
import com.banner.hintcontrol.ui.more.GatewayInfoScreen
import com.banner.hintcontrol.ui.more.MoreScreen
import com.banner.hintcontrol.ui.more.ProfilesScreen
import com.banner.hintcontrol.ui.more.ScheduleScreen
import com.banner.hintcontrol.ui.more.SettingsScreen
import com.banner.hintcontrol.ui.more.UpdatesScreen
import com.banner.hintcontrol.ui.overview.OverviewScreen
import com.banner.hintcontrol.ui.reboot.RebootConfirmDialog
import com.banner.hintcontrol.ui.reboot.RebootProgressDialog
import com.banner.hintcontrol.ui.setup.SetupScreen
import com.banner.hintcontrol.ui.signal.SignalScreen
import com.banner.hintcontrol.ui.speed.SpeedTestScreen
import com.banner.hintcontrol.ui.more.FirmwareScreen
import com.banner.hintcontrol.ui.wifi.WifiScreen
import com.banner.hintcontrol.update.UpdateState

@Composable
fun AppRoot(container: AppContainer, link: String?, onLinkHandled: () -> Unit) {
    val profiles by container.profiles.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalContainer provides container) {
        when {
            !profiles.loaded -> Box(Modifier.fillMaxSize())
            profiles.profiles.isEmpty() -> SetupScreen(onDone = {})
            else -> MainScaffold(container, link, onLinkHandled)
        }
    }
}

private fun NavHostController.goTop(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun MainScaffold(container: AppContainer, link: String?, onLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    var confirmReboot by remember { mutableStateOf(false) }
    val phase by container.reboots.phase.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()
    val profile by container.profiles.active.collectAsStateWithLifecycle()
    val actions = remember(nav) {
        AppActions(
            navigate = { route -> nav.navigate(route) { launchSingleTop = true } },
            back = { if (!nav.popBackStack()) nav.goTop(OverviewRoute) },
            askReboot = { confirmReboot = true },
        )
    }

    val context = LocalContext.current
    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // Ask once, after setup: alerts and "update available" notices are silent without it on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !container.settings.current().askedNotifications
        ) {
            container.settings.update { it.copy(askedNotifications = true) }
            notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(link) {
        when (link) {
            null -> return@LaunchedEffect
            "live", "signal" -> nav.goTop(SignalRoute)
            "devices" -> nav.goTop(DevicesRoute)
            "wifi" -> nav.goTop(WifiRoute)
            "reboot" -> confirmReboot = true
            "updates" -> nav.navigate(UpdatesRoute)
            "events" -> nav.navigate(EventsRoute)
            "speedtest" -> nav.navigate(SpeedTestRoute)
        }
        onLinkHandled()
    }

    LaunchedEffect(Unit) {
        val s = container.settings.current()
        if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastUpdateCheck > 6 * 3_600_000L) container.updates.check()
    }

    // Sub-screens (Updates, Event log, …) are not nested under a tab, so keep the tab they were opened from highlighted.
    val currentTop = TopDestination.entries.firstOrNull { d -> destination?.hierarchy?.any(d.matches) == true }
    var lastTop by rememberSaveable { mutableStateOf(TopDestination.Overview.name) }
    LaunchedEffect(currentTop) { if (currentTop != null) lastTop = currentTop.name }
    val selectedTop = currentTop ?: TopDestination.valueOf(lastTop)

    AppNavigationSuite(
        isSelected = { d -> d == selectedTop },
        updateBadge = update is UpdateState.Available || update is UpdateState.ReadyToInstall,
        onSelect = { d -> nav.goTop(d.route) },
    ) {
        CompositionLocalProvider(LocalAppActions provides actions) {
            NavHost(nav, startDestination = OverviewRoute) {
                composable<OverviewRoute> { OverviewScreen() }
                composable<SignalRoute> { SignalScreen() }
                composable<DevicesRoute> { DevicesScreen() }
                composable<WifiRoute> { WifiScreen() }
                composable<MoreRoute> { MoreScreen() }
                composable<SetupRoute> { SetupScreen(onDone = { nav.goTop(OverviewRoute) }, onBack = { nav.popBackStack() }) }
                composable<GatewayInfoRoute> { GatewayInfoScreen() }
                composable<ScheduleRoute> { ScheduleScreen() }
                composable<AlertsRoute> { AlertsScreen() }
                composable<ProfilesRoute> { ProfilesScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<UpdatesRoute> { UpdatesScreen() }
                composable<ExplorerRoute> { ExplorerScreen() }
                composable<EventsRoute> { EventsScreen() }
                composable<AboutRoute> { AboutScreen() }
                composable<AdminLoginRoute> { AdminLoginScreen() }
                composable<AutomationRoute> { AutomationScreen() }
                composable<SpeedTestRoute> { SpeedTestScreen() }
                composable<FirmwareRoute> { FirmwareScreen() }
            }
        }
    }

    if (confirmReboot) {
        RebootConfirmDialog(
            gatewayName = profile?.name ?: "the gateway",
            onDismiss = { confirmReboot = false },
            onConfirm = {
                confirmReboot = false
                container.reboots.start()
            },
        )
    }
    if (phase != RebootPhase.Idle) RebootProgressDialog(phase, onDismiss = container.reboots::dismiss)
}

/** Bottom bar on phones, navigation rail on wide or landscape screens. */
@Composable
fun AppNavigationSuite(
    isSelected: (TopDestination) -> Boolean,
    updateBadge: Boolean,
    onSelect: (TopDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val config = LocalConfiguration.current
    val rail = config.screenWidthDp >= 840 || (config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp)
    NavigationSuiteScaffold(
        layoutType = if (rail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            TopDestination.entries.forEach { d ->
                val selected = isSelected(d)
                item(
                    selected = selected,
                    onClick = { onSelect(d) },
                    icon = {
                        if (d == TopDestination.More && updateBadge) {
                            BadgedBox(badge = { Badge() }) { Icon(if (selected) d.selectedIcon else d.icon, d.label) }
                        } else {
                            Icon(if (selected) d.selectedIcon else d.icon, d.label)
                        }
                    },
                    label = { Text(d.label) },
                )
            }
        },
        content = content,
    )
}
