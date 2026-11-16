package com.banner.hintcontrol.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetworkInfo {
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
