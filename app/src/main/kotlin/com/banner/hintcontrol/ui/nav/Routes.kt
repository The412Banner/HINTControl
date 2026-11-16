package com.banner.hintcontrol.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.banner.hintcontrol.AppContainer
import kotlinx.serialization.Serializable

@Serializable data object OverviewRoute
@Serializable data object SignalRoute
@Serializable data object DevicesRoute
@Serializable data object WifiRoute
@Serializable data object MoreRoute
@Serializable data class SetupRoute(val addNew: Boolean = true)
@Serializable data object GatewayInfoRoute
@Serializable data object ScheduleRoute
@Serializable data object AlertsRoute
@Serializable data object ProfilesRoute
@Serializable data object SettingsRoute
@Serializable data object UpdatesRoute
@Serializable data object ExplorerRoute
@Serializable data object EventsRoute
@Serializable data object AboutRoute
@Serializable data object AdminLoginRoute
@Serializable data object AutomationRoute
@Serializable data object SpeedTestRoute
@Serializable data object FirmwareRoute

enum class TopDestination(
    val label: String,
    val route: Any,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val matches: (NavDestination) -> Boolean,
) {
    Overview("Home", OverviewRoute, Icons.Outlined.Home, Icons.Filled.Home, { it.hasRoute<OverviewRoute>() }),
    Signal("Signal", SignalRoute, Icons.Outlined.SignalCellularAlt, Icons.Filled.SignalCellularAlt, { it.hasRoute<SignalRoute>() }),
    Devices("Devices", DevicesRoute, Icons.Outlined.Devices, Icons.Filled.Devices, { it.hasRoute<DevicesRoute>() }),
    Wifi("Wi-Fi", WifiRoute, Icons.Outlined.Wifi, Icons.Filled.Wifi, { it.hasRoute<WifiRoute>() }),
    More("More", MoreRoute, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz, { it.hasRoute<MoreRoute>() }),
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** App-wide actions screens can trigger without knowing about each other. */
class AppActions(
    val navigate: (Any) -> Unit,
    val back: () -> Unit,
    val askReboot: () -> Unit,
)

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(c) } })
}
