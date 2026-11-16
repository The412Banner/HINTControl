// Data shapes ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientDeviceData(
    val clients: ClientsData? = null,
)

@Serializable
data class ClientsData(
    @SerialName("2.4ghz")
    val twoGig: List<WirelessClientData>? = null,
    @SerialName("5.0ghz")
    val fiveGig: List<WirelessClientData>? = null,
    @SerialName("6.0ghz")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sixGig: List<WirelessClientData>? = null,
    val ethernet: List<WiredClientData>? = null,
    val wireless: List<WirelessClientData>? = null,
)

interface BaseClientData {
    val connected: Boolean
    val ipv4: String?
    val ipv6: List<String>?
    val mac: String?
    val name: String?
}

@Serializable
data class WiredClientData(
    override val connected: Boolean = true,
    override val ipv4: String? = null,
    override val ipv6: List<String>? = null,
    override val mac: String? = null,
    override val name: String? = null,
) : BaseClientData

@Serializable
data class WirelessClientData(
    override val connected: Boolean = true,
    override val ipv4: String? = null,
    override val ipv6: List<String>? = null,
    override val mac: String? = null,
    override val name: String? = null,
    val signal: Int? = null,
) : BaseClientData

enum class ClientLink { Wired, TwoGig, FiveGig, SixGig, Wireless }

data class Client(
    val link: ClientLink,
    val connected: Boolean,
    val name: String?,
    val ipv4: String?,
    val ipv6: List<String>,
    val mac: String?,
    val signal: Int?,
) {
    /** Second-lowest bit of the first octet set = locally administered (randomised / private) MAC. */
    val isPrivateMac: Boolean
        get() = mac?.split(':', '-')?.firstOrNull()?.toIntOrNull(16)?.let { it and 0x02 != 0 } == true

    val key: String get() = mac?.uppercase() ?: "${link.name}:${ipv4 ?: name}"
}

fun ClientDeviceData.flatten(): List<Client> {
    val c = clients ?: return emptyList()
    fun List<BaseClientData>?.map(link: ClientLink) = orEmpty().map {
        Client(
            link = link,
            connected = it.connected,
            name = it.name?.takeIf(String::isNotBlank),
            ipv4 = it.ipv4?.takeIf(String::isNotBlank),
            ipv6 = it.ipv6.orEmpty().filter(String::isNotBlank),
            mac = it.mac?.takeIf(String::isNotBlank),
            signal = (it as? WirelessClientData)?.signal,
        )
    }
    return c.ethernet.map(ClientLink.Wired) +
        c.twoGig.map(ClientLink.TwoGig) +
        c.fiveGig.map(ClientLink.FiveGig) +
        c.sixGig.map(ClientLink.SixGig) +
        c.wireless.map(ClientLink.Wireless)
}
