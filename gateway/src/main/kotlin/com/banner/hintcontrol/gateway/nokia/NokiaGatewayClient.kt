// Request flow ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.nokia

import com.banner.hintcontrol.gateway.GatewayAddress
import com.banner.hintcontrol.gateway.GatewayCapabilities
import com.banner.hintcontrol.gateway.GatewayClient
import com.banner.hintcontrol.gateway.GatewayException
import com.banner.hintcontrol.gateway.GatewayHttp
import com.banner.hintcontrol.gateway.GatewayHttp.withTimeout
import com.banner.hintcontrol.gateway.GatewayJson
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.HttpResult
import com.banner.hintcontrol.gateway.KnownEndpoint
import com.banner.hintcontrol.gateway.RawResponse
import com.banner.hintcontrol.gateway.RebootResult
import com.banner.hintcontrol.gateway.Redact
import com.banner.hintcontrol.gateway.await
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.ClientDeviceData
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.gateway.model.WifiConfig
import com.banner.hintcontrol.gateway.tmi.TmiGatewayClient.Companion.isUnreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Nokia 5G21 gateway (CGI endpoints, cookie session). */
class NokiaGatewayClient(
    host: String,
    private val http: OkHttpClient = GatewayHttp.base,
    private val baseUrlOverride: String? = null,
    /** Pause after saving Wi-Fi settings before checking that the gateway is back. */
    private val wifiSettleMs: Long = 10_000L,
) : GatewayClient {
    override val kind = GatewayKind.Nokia
    override val host: String = GatewayAddress.normalize(host)
    override val capabilities = GatewayCapabilities(
        changeLogin = false,
        addRemoveNetworks = false,
        radioSettings = false,
        statusWithoutLogin = true,
    )

    private val authMutex = Mutex()
    private val cookies = ConcurrentHashMap<String, String>()
    @Volatile private var credentials: Pair<String, String>? = null
    @Volatile private var loggedIn = false

    override val isLoggedIn: Boolean get() = loggedIn

    private val root: String
        get() = baseUrlOverride?.trimEnd('/')?.plus("/") ?: "http://$host/"

    fun url(path: String) = root + path.trimStart('/')

    object Endpoints {
        const val LOGIN = "login_app.cgi"
        const val DEVICE_STATUS = "dashboard_device_status_web_app.cgi"
        const val RADIO_STATUS = "fastmile_radio_status_web_app.cgi"
        const val DEVICE_INFO = "dashboard_device_info_status_web_app.cgi"
        const val STATISTICS = "fastmile_statistics_status_web_app.cgi"
        const val CELL_STATUS = "cell_status_app.cgi"
        const val WIFI_LIST = "wlan_list_app.cgi"
        const val SERVICE = "service_function_app.cgi"
        const val SNTP = "sntp_status_app.cgi"
        const val SIM = "sim_status_app.cgi"
    }

    override val knownEndpoints = listOf(
        KnownEndpoint("Device status", "GET", Endpoints.DEVICE_STATUS, needsLogin = false),
        KnownEndpoint("Device info", "GET", Endpoints.DEVICE_INFO, needsLogin = false),
        KnownEndpoint("Radio status", "GET", Endpoints.RADIO_STATUS, needsLogin = false),
        KnownEndpoint("Cell status", "GET", Endpoints.CELL_STATUS, needsLogin = false),
        KnownEndpoint("Statistics", "GET", Endpoints.STATISTICS),
        KnownEndpoint("Wi-Fi networks", "GET", Endpoints.WIFI_LIST),
        KnownEndpoint("Time", "GET", Endpoints.SNTP),
        KnownEndpoint("SIM", "GET", Endpoints.SIM),
    )

    override suspend fun exists(timeoutMs: Long): Boolean = try {
        http.withTimeout(timeoutMs)
            .await(Request.Builder().url(url(Endpoints.DEVICE_STATUS)).get().build())
            .isSuccess
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override suspend fun login(username: String, password: String) {
        authMutex.withLock { loginLocked(username, password) }
    }

    private suspend fun loginLocked(username: String, password: String) {
        // The old app sent the previously stored name/password here instead of the typed ones.
        val form = FormBody.Builder().add("name", username).add("pswd", password).build()
        val r = send(Request.Builder().url(url(Endpoints.LOGIN)).post(form).build())
        if (!r.isSuccess) {
            loggedIn = false
            throw if (r.code == 401 || r.code == 403) GatewayException.Unauthorized() else httpError(r)
        }
        credentials = username to password
        loggedIn = true
    }

    override suspend fun logout() {
        cookies.clear()
        credentials = null
        loggedIn = false
    }

    override suspend fun mainData(): MainData {
        val device = getJson(Endpoints.DEVICE_INFO, DeviceInfoStatus.serializer(), "device info", authed = loggedIn)
        val cell = getJson(Endpoints.CELL_STATUS, CellStatus.serializer(), "cell status", authed = loggedIn)
        val radio = getJson(Endpoints.RADIO_STATUS, ConnectionStatus.serializer(), "radio status", authed = loggedIn)
        return NokiaMapper.main(device, cell, radio)
    }

    override suspend fun cellData(): CellDataRoot {
        val radio = getJson(Endpoints.RADIO_STATUS, ConnectionStatus.serializer(), "radio status")
        val cell = getJson(Endpoints.CELL_STATUS, CellStatus.serializer(), "cell status")
        return NokiaMapper.cell(radio, cell)
    }

    override suspend fun simData(): SimDataRoot =
        NokiaMapper.sim(getJson(Endpoints.STATISTICS, StatisticsInfo.serializer(), "statistics"))

    override suspend fun clients(): ClientDeviceData =
        NokiaMapper.clients(getJson(Endpoints.DEVICE_INFO, DeviceInfoStatus.serializer(), "device info"))

    override suspend fun wifiConfig(): WifiConfig =
        NokiaMapper.wifi(getJson(Endpoints.WIFI_LIST, WifiListing.serializer(), "Wi-Fi networks"))

    override suspend fun setWifiConfig(config: WifiConfig) {
        val body = GatewayJson.write.encodeToString(SetWifiConfig.serializer(), NokiaMapper.setWifi(config))
        val r = authed { Request.Builder().url(url(Endpoints.SERVICE)).post(body.toRequestBody(JSON)).build() }
        if (!r.isSuccess) throw httpError(r)
        delay(wifiSettleMs)
        waitForLive()
    }

    override suspend fun setLogin(username: String, password: String) {
        throw GatewayException.NotSupported("Changing the admin login")
    }

    override suspend fun reboot(): RebootResult {
        if (!loggedIn) reLogin()
        return try {
            val body = GatewayJson.write.encodeToString(RebootAction.serializer(), RebootAction())
            authed { Request.Builder().url(url(Endpoints.SERVICE)).post(body.toRequestBody(JSON)).build() }
            val form = FormBody.Builder().add("action", "Reboot").build()
            val r = authed { Request.Builder().url(url(Endpoints.LOGIN)).post(form).build() }
            if (r.isSuccess) RebootResult.Accepted else throw httpError(r)
        } catch (e: GatewayException.Unreachable) {
            val cause = e.cause
            if (cause is IOException && !cause.isUnreachable()) RebootResult.Unconfirmed else throw e
        }
    }

    override suspend fun rawRequest(method: String, path: String, body: String?): RawResponse {
        val m = method.uppercase()
        val r = authed(allowAnonymous = true) {
            Request.Builder().url(url(path)).apply {
                if (m == "GET") get() else method(m, (body ?: "").toRequestBody(JSON))
            }.build()
        }
        return RawResponse(r.code, Redact.text(r.body), r.tookMs, r.headers["Content-Type"])
    }

    // --- internals ---

    private suspend fun <T> getJson(path: String, serializer: KSerializer<T>, what: String, authed: Boolean = true): T {
        val r = if (authed) {
            authed { Request.Builder().url(url(path)).get().build() }
        } else {
            send(Request.Builder().url(url(path)).get().build())
        }
        if (!r.isSuccess) throw httpError(r)
        return try {
            GatewayJson.readNokia.decodeFromString(serializer, r.body)
        } catch (e: SerializationException) {
            throw GatewayException.BadResponse(what, e)
        } catch (e: IllegalArgumentException) {
            throw GatewayException.BadResponse(what, e)
        }
    }

    private suspend fun waitForLive(attempts: Int = 60) {
        repeat(attempts) {
            try {
                if (send(Request.Builder().url(url(Endpoints.WIFI_LIST)).get().build()).isSuccess) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: GatewayException) {
            }
            delay(1_000)
        }
    }

    private suspend fun reLogin() {
        val creds = credentials ?: throw GatewayException.Unauthorized("Log in to the gateway first")
        authMutex.withLock { loginLocked(creds.first, creds.second) }
    }

    /** The old app retried 403 and 5xx answers up to three times, logging in again before each retry. */
    private suspend fun authed(allowAnonymous: Boolean = false, build: () -> Request): HttpResult {
        if (!allowAnonymous && !loggedIn) reLogin()
        var r = send(build())
        var retries = 0
        while ((r.code == 403 || r.code in 500..599) && retries < 3 && credentials != null) {
            retries++
            reLogin()
            r = send(build())
        }
        return r
    }

    private suspend fun send(request: Request): HttpResult {
        val withCookies = if (cookies.isEmpty()) request else request.newBuilder()
            .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .build()
        val r = try {
            http.await(withCookies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw GatewayException.Unreachable(host, e)
        }
        r.headers.values("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty() && pair.contains('=')) cookies[name] = pair.substringAfter('=').trim()
        }
        return r
    }

    private fun httpError(r: HttpResult): GatewayException = when (r.code) {
        401, 403 -> GatewayException.Unauthorized("The gateway refused the request (HTTP ${r.code}). Log in again.")
        else -> GatewayException.Http(r.code, "The gateway answered HTTP ${r.code}")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
