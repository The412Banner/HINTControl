package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.ClientDeviceData
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.gateway.model.WifiConfig

enum class GatewayKind(val label: String) {
    /** Arcadyan, Sagemcom and Sercomm gateways (TMI/v1 API on port 8080). */
    Tmi("Arcadyan / Sagemcom / Sercomm"),

    /** Nokia 5G21 (web CGI API). */
    Nokia("Nokia"),

    /** Built-in simulated gateway. */
    Demo("Demo"),
}

data class GatewayCapabilities(
    val changeLogin: Boolean,
    val addRemoveNetworks: Boolean,
    val radioSettings: Boolean,
    val statusWithoutLogin: Boolean,
)

enum class RebootResult {
    /** The gateway confirmed the request. */
    Accepted,

    /** The connection dropped while sending; the gateway is most likely restarting. */
    Unconfirmed,
}

data class RawResponse(
    val code: Int,
    val body: String,
    val tookMs: Long,
    val contentType: String?,
)

data class KnownEndpoint(
    val label: String,
    val method: String,
    val path: String,
    val needsLogin: Boolean = true,
    val changesSettings: Boolean = false,
)

interface GatewayClient {
    val kind: GatewayKind
    val host: String
    val capabilities: GatewayCapabilities
    val isLoggedIn: Boolean
    val knownEndpoints: List<KnownEndpoint>

    /** Quick check that this kind of gateway answers at [host]. Never throws. */
    suspend fun exists(): Boolean

    suspend fun login(username: String, password: String)

    suspend fun logout()

    /** Device, signal and time. Works without a login where the gateway allows it. */
    suspend fun mainData(): MainData

    suspend fun cellData(): CellDataRoot

    suspend fun simData(): SimDataRoot

    suspend fun clients(): ClientDeviceData

    suspend fun wifiConfig(): WifiConfig

    suspend fun setWifiConfig(config: WifiConfig)

    suspend fun setLogin(username: String, password: String)

    suspend fun reboot(): RebootResult

    /** Sends a request as-is (API explorer). [path] is relative to the API root. */
    suspend fun rawRequest(method: String, path: String, body: String?): RawResponse
}

object GatewayAddress {
    const val DEFAULT_HOST = "192.168.12.1"

    /** Accepts "192.168.12.1", "http://192.168.12.1/", "host:8080"; returns host[:port]. */
    fun normalize(input: String): String {
        var s = input.trim()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        return s.ifBlank { DEFAULT_HOST }
    }

    fun hasPort(host: String): Boolean = Regex(":\\d+$").containsMatchIn(host)

    fun isPlausible(input: String): Boolean {
        val h = normalize(input)
        return Regex("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$").matches(h)
    }
}
