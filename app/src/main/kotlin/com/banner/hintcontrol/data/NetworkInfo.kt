package com.banner.hintcontrol.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

object NetworkInfo {
    @Volatile private var appContext: Context? = null

    /** Called from Application.onCreate so error messages can check the network without a Context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** How the phone reaches the internet right now: "Wi-Fi", "Ethernet", "Mobile data" or null. */
    fun activeConnection(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> null
        }
    }

    /** The phone's current Wi-Fi link speed in Mbps (the most the phone ↔ gateway link can carry), or null. */
    @Suppress("DEPRECATION")
    fun wifiLinkMbps(context: Context): Int? {
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            runCatching {
                cm?.allNetworks?.firstNotNullOfOrNull { n ->
                    cm.getNetworkCapabilities(n)?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.transportInfo as? WifiInfo
                }
            }.getOrNull()
        } else {
            runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        }
        return info?.linkSpeed?.takeIf { it > 0 }
    }

    /** True when a VPN is up; many VPNs send local traffic away from the gateway (HINT Control #84). */
    @Suppress("DEPRECATION")
    fun vpnActive(context: Context? = appContext): Boolean {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
    }

    /** IPv4 default gateways of the phone's Wi-Fi and Ethernet networks (usually the router). */
    @Suppress("DEPRECATION")
    fun localGateways(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            cm.allNetworks.flatMap { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local) return@flatMap emptyList()
                cm.getLinkProperties(network)?.routes.orEmpty()
                    .filter { it.isDefaultRoute }
                    .mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    /** Phone's own IPv4 addresses on Wi-Fi/Ethernet. */
    @Suppress("DEPRECATION")
    private fun localAddresses(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            cm.allNetworks.flatMap { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return@flatMap emptyList()
                }
                cm.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * True when the phone looks like it is on the gateway's own network: the gateway address is
     * the phone's router, or both are in the same /24. Used so "gateway offline" alerts don't fire
     * while the phone is away from home.
     */
    fun onGatewayNetwork(context: Context, gatewayHost: String): Boolean {
        val host = gatewayHost.substringBefore(':')
        if (localGateways(context).contains(host)) return true
        val prefix = host.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return false
        return localAddresses(context).any { it.substringBeforeLast('.') == prefix }
    }
}
