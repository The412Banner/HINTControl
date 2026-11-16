// Request flow ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.tmi

import com.banner.hintcontrol.gateway.GatewayAddress
import com.banner.hintcontrol.gateway.GatewayCapabilities
import com.banner.hintcontrol.gateway.FirmwareBody
import com.banner.hintcontrol.gateway.FirmwareFile
import com.banner.hintcontrol.gateway.FirmwareUploadResult
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
import com.banner.hintcontrol.gateway.model.LoginResultData
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SetLoginAction
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.gateway.model.UsernamePassword
import com.banner.hintcontrol.gateway.model.WifiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Arcadyan KVD21 / TMOG4AR, Sagemcom FAST 5688W, Sercomm TMOG4SE ("TMI/v1" API). */
class TmiGatewayClient(
    host: String,
    private val http: OkHttpClient = GatewayHttp.base,
    /** Base URL override for tests, e.g. a MockWebServer root. */
    private val baseUrlOverride: String? = null,
) : GatewayClient {
    override val kind = GatewayKind.Tmi
    override val host: String = GatewayAddress.normalize(host)
    override val capabilities = GatewayCapabilities(
        changeLogin = true,
        addRemoveNetworks = true,
        radioSettings = true,
        statusWithoutLogin = true,
    )

    private val authMutex = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var credentials: Pair<String, String>? = null

    override val isLoggedIn: Boolean get() = token != null

    private val root: String
        get() = baseUrlOverride?.trimEnd('/')?.plus("/")
            ?: "http://${if (GatewayAddress.hasPort(host)) host else "$host:8080"}/TMI/v1/"

    fun url(path: String) = root + path.trimStart('/')

    /** The gateway's own web page logs in and uploads firmware on port 80, not 8080. */
    private val webRoot: String
        get() = baseUrlOverride?.trimEnd('/')?.plus("/") ?: "http://${host.substringBefore(':')}/TMI/v1/"

    override val knownEndpoints = listOf(
        KnownEndpoint("Gateway status", "GET", Endpoints.GATEWAY_INFO, needsLogin = false),
        KnownEndpoint("Cell details", "GET", Endpoints.CELL),
        KnownEndpoint("SIM", "GET", Endpoints.SIM),
        KnownEndpoint("Connected devices", "GET", Endpoints.CLIENTS),
        KnownEndpoint("Wi-Fi settings", "GET", Endpoints.WIFI_GET),
        KnownEndpoint("Gateway version", "GET", "version"),
        KnownEndpoint("Save Wi-Fi settings", "POST", Endpoints.WIFI_SET, changesSettings = true),
        KnownEndpoint("Reboot", "POST", Endpoints.REBOOT, changesSettings = true),
    )

    object Endpoints {
        const val GATEWAY_INFO = "gateway/?get=all"
        const val WIFI_GET = "network/configuration/v2?get=ap"
        const val WIFI_SET = "network/configuration/v2?set=ap"
        const val CLIENTS = "network/telemetry/?get=clients"
        const val CELL = "network/telemetry/?get=cell"
        const val SIM = "network/telemetry/?get=sim"
        const val REBOOT = "gateway/reset?set=reboot"
        const val AUTH = "auth/login"
        const val RESET_LOGIN = "auth/admin/reset"
        const val FIRMWARE = "gateway/upgrade"
    }

    override suspend fun exists(timeoutMs: Long): Boolean = try {
        val r = http.withTimeout(timeoutMs).await(get(Endpoints.GATEWAY_INFO, authed = false))
        r.code != 404 && r.code != 403
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override suspend fun login(username: String, password: String) {
        authMutex.withLock { loginLocked(username, password) }
    }

    private suspend fun loginLocked(username: String, password: String) {
        val body = GatewayJson.write.encodeToString(UsernamePassword.serializer(), UsernamePassword(username, password))
        val request = Request.Builder()
            .url(url(Endpoints.AUTH))
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        val r = send(request)
        when {
            r.isSuccess -> {
                val data = decode(r.body, LoginResultData.serializer(), "login")
                val newToken = data.auth?.token
                if (newToken.isNullOrBlank()) {
                    token = null
                    throw GatewayException.TooManyAttempts(data.result?.message ?: "Too many attempts. Wait a few minutes and try again.")
                }
                token = newToken
                credentials = username to password
            }
            r.code == 401 || r.code == 403 -> {
                token = null
                throw GatewayException.Unauthorized()
            }
            else -> throw httpError(r)
        }
    }

    override suspend fun logout() {
        token = null
        credentials = null
    }

    override suspend fun mainData(): MainData =
        decode(fetch(Endpoints.GATEWAY_INFO, authed = isLoggedIn), MainData.serializer(), "status")

    override suspend fun cellData(): CellDataRoot =
        decode(fetch(Endpoints.CELL), CellDataRoot.serializer(), "cell details")

    override suspend fun simData(): SimDataRoot =
        decode(fetch(Endpoints.SIM), SimDataRoot.serializer(), "SIM")

    override suspend fun clients(): ClientDeviceData =
        decode(fetch(Endpoints.CLIENTS), ClientDeviceData.serializer(), "devices")

    override suspend fun wifiConfig(): WifiConfig {
        // Gateways answer 408 for a while after Wi-Fi changes; the old app kept retrying.
        repeat(WIFI_RETRIES) { attempt ->
            val r = authedRequest { get(Endpoints.WIFI_GET, authed = true) }
            if (r.code == 408 && attempt < WIFI_RETRIES - 1) {
                delay(2_000)
                return@repeat
            }
            if (!r.isSuccess) throw httpError(r)
            return decode(r.body, WifiConfig.serializer(), "Wi-Fi settings")
        }
        throw GatewayException.Http(408, "The gateway is still applying Wi-Fi changes")
    }

    override suspend fun setWifiConfig(config: WifiConfig) {
        val body = GatewayJson.write.encodeToString(WifiConfig.serializer(), config)
        val r = authedRequest { post(Endpoints.WIFI_SET, body.toRequestBody(JSON)) }
        if (!r.isSuccess) throw httpError(r)
    }

    override suspend fun setLogin(username: String, password: String) {
        val body = GatewayJson.write.encodeToString(SetLoginAction.serializer(), SetLoginAction(username, password))
        val r = authedRequest { post(Endpoints.RESET_LOGIN, body.toRequestBody(JSON)) }
        if (!r.isSuccess) throw httpError(r)
        credentials = username to password
    }

    override suspend fun reboot(): RebootResult {
        ensureLoggedIn()
        return try {
            val r = http.await(post(Endpoints.REBOOT, EMPTY))
            when {
                r.isSuccess -> RebootResult.Accepted
                r.code == 401 -> {
                    reLogin()
                    val again = http.await(post(Endpoints.REBOOT, EMPTY))
                    if (again.isSuccess) RebootResult.Accepted else throw httpError(again)
                }
                else -> throw httpError(r)
            }
        } catch (e: IOException) {
            if (e.isUnreachable()) throw GatewayException.Unreachable(host, e)
            RebootResult.Unconfirmed
        }
    }

    override suspend fun rawRequest(method: String, path: String, body: String?): RawResponse {
        val m = method.uppercase()
        val r = authedRequest(allowAnonymous = true) {
            Request.Builder().url(url(path)).apply {
                if (isLoggedIn) header("Authorization", "Bearer $token")
                header("User-Agent", USER_AGENT)
                when (m) {
                    "GET" -> get()
                    else -> method(m, (body ?: "").toRequestBody(JSON))
                }
            }.build()
        }
        return RawResponse(r.code, Redact.text(r.body), r.tookMs, r.headers["Content-Type"])
    }

    override suspend fun uploadFirmware(file: FirmwareFile, onProgress: (Long) -> Unit): FirmwareUploadResult {
        val creds = credentials ?: throw GatewayException.Unauthorized("Log in to the gateway first")
        val client = http.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()
        // Same two steps as the gateway's web page: log in on port 80, then send the file there.
        val loginBody = GatewayJson.write.encodeToString(UsernamePassword.serializer(), UsernamePassword(creds.first, creds.second))
        val login = try {
            client.await(
                Request.Builder().url(webRoot + Endpoints.AUTH)
                    .header("Accept", "application/json")
                    .post(loginBody.toRequestBody(JSON))
                    .build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw GatewayException.Unreachable(host, e)
        }
        if (login.code == 401 || login.code == 403) throw GatewayException.Unauthorized()
        if (!login.isSuccess) throw httpError(login)
        val webToken = decode(login.body, LoginResultData.serializer(), "login").auth?.token
        if (webToken.isNullOrBlank()) throw GatewayException.TooManyAttempts("The gateway didn't accept the login. Wait a few minutes and try again.")

        val body = FirmwareBody(file, onProgress)
        val request = Request.Builder().url(webRoot + Endpoints.FIRMWARE)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $webToken")
            .header("Cache-Control", "no-cache")
            .post(body)
            .build()
        val r = try {
            client.await(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (file.size > 0 && body.sent >= file.size) return FirmwareUploadResult.SentNoAnswer
            if (body.sent == 0L && e.isUnreachable()) throw GatewayException.Unreachable(host, e)
            throw GatewayException.UploadInterrupted(body.sent, file.size, e)
        }
        return when {
            r.isSuccess -> FirmwareUploadResult.Accepted
            r.code == 401 || r.code == 403 -> throw GatewayException.Unauthorized("The gateway refused the upload (HTTP ${r.code}). Log in again.")
            else -> throw httpError(r)
        }
    }

    // --- internals ---

    private fun get(path: String, authed: Boolean): Request = Request.Builder()
        .url(url(path))
        .apply {
            if (authed) {
                token?.let { header("Authorization", "Bearer $it") }
                header("User-Agent", USER_AGENT)
            }
        }
        .get()
        .build()

    private fun post(path: String, body: RequestBody): Request = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer ${token.orEmpty()}")
        .header("User-Agent", USER_AGENT)
        .post(body)
        .build()

    private suspend fun fetch(path: String, authed: Boolean = true): String {
        val r = if (authed) authedRequest { get(path, authed = true) } else send(get(path, authed = false))
        if (!r.isSuccess) throw httpError(r)
        return r.body
    }

    private suspend fun ensureLoggedIn() {
        if (token == null) reLogin()
    }

    private suspend fun reLogin() {
        val creds = credentials ?: throw GatewayException.Unauthorized("Log in to the gateway first")
        authMutex.withLock { loginLocked(creds.first, creds.second) }
    }

    /** Sends with the current token; on 401 logs in again once and repeats the request. */
    private suspend fun authedRequest(allowAnonymous: Boolean = false, build: () -> Request): HttpResult {
        if (!allowAnonymous) ensureLoggedIn()
        val first = send(build())
        if (first.code != 401 || credentials == null) return first
        reLogin()
        return send(build())
    }

    private suspend fun send(request: Request): HttpResult = try {
        http.await(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw GatewayException.Unreachable(host, e)
    }

    private fun httpError(r: HttpResult): GatewayException = when (r.code) {
        401, 403 -> GatewayException.Unauthorized("The gateway refused the request (HTTP ${r.code}). Log in again.")
        else -> GatewayException.Http(r.code, "The gateway answered HTTP ${r.code}" + Redact.text(r.body).take(200).let { if (it.isBlank()) "" else ": $it" })
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>, what: String): T = try {
        GatewayJson.read.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw GatewayException.BadResponse(what, e)
    } catch (e: IllegalArgumentException) {
        throw GatewayException.BadResponse(what, e)
    }

    companion object {
        const val USER_AGENT = "homeisp/android/2.12.1"
        private const val WIFI_RETRIES = 10
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        internal fun IOException.isUnreachable(): Boolean =
            this is ConnectException || this is NoRouteToHostException || this is UnknownHostException
    }
}
