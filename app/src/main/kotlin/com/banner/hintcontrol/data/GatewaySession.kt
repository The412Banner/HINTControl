package com.banner.hintcontrol.data

import com.banner.hintcontrol.gateway.GatewayClient
import com.banner.hintcontrol.gateway.GatewayException
import com.banner.hintcontrol.gateway.GatewayHttp
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.demo.DemoBootTimeStore
import com.banner.hintcontrol.gateway.demo.DemoGatewayClient
import com.banner.hintcontrol.gateway.nokia.NokiaGatewayClient
import com.banner.hintcontrol.gateway.tmi.TmiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class NoGatewayException : Exception("No gateway set up yet")

class NeedsLoginException : Exception("Log in to the gateway to see this")

sealed interface Access {
    data object NoGateway : Access
    data object Anonymous : Access
    data object LoggingIn : Access
    data object LoggedIn : Access
    data class Failed(val message: String) : Access
}

/**
 * Owns the client for the active gateway profile and its login. Screens and background work go
 * through [status] (no login needed) or [authed] (logs in with the saved password if needed).
 */
class GatewaySession(
    private val scope: CoroutineScope,
    private val profiles: ProfileRepository,
    private val http: OkHttpClient = GatewayHttp.base,
    demoBootTime: DemoBootTimeStore? = null,
) {
    private val demo = DemoGatewayClient(bootTime = demoBootTime)
    private val lock = Mutex()
    @Volatile private var cache: Pair<String, GatewayClient>? = null
    /** Password typed this session but not saved. */
    @Volatile private var sessionPassword: Pair<String, String>? = null

    private val _access = MutableStateFlow<Access>(Access.NoGateway)
    val access: StateFlow<Access> = _access.asStateFlow()

    val active: StateFlow<GatewayProfile?> get() = profiles.active

    init {
        scope.launch {
            profiles.active.distinctUntilChangedBy { it?.id to it?.host }.collect { p ->
                _access.value = if (p == null) Access.NoGateway else Access.Anonymous
                if (p != null && (p.hasSavedPassword || p.isDemo)) {
                    runCatching { ensureLogin(p) }
                }
            }
        }
    }

    private fun keyOf(p: GatewayProfile) = "${p.id}|${p.host}|${p.kind}"

    fun clientFor(p: GatewayProfile): GatewayClient {
        if (p.kind == GatewayKind.Demo) return demo
        cache?.takeIf { it.first == keyOf(p) }?.let { return it.second }
        val client = when (p.kind) {
            GatewayKind.Tmi -> TmiGatewayClient(p.host, http)
            GatewayKind.Nokia -> NokiaGatewayClient(p.host, http)
            GatewayKind.Demo -> demo
        }
        cache = keyOf(p) to client
        return client
    }

    fun currentClient(): GatewayClient? = active.value?.let(::clientFor)

    suspend fun <T> status(block: suspend (GatewayClient) -> T): T {
        val p = active.value ?: throw NoGatewayException()
        return block(clientFor(p))
    }

    suspend fun <T> authed(block: suspend (GatewayClient) -> T): T {
        val p = active.value ?: throw NoGatewayException()
        val client = clientFor(p)
        if (!client.isLoggedIn) ensureLogin(p)
        return try {
            block(client)
        } catch (e: GatewayException.Unauthorized) {
            _access.value = Access.Failed(e.message ?: "Login rejected")
            throw e
        }
    }

    private suspend fun ensureLogin(p: GatewayProfile) = lock.withLock {
        val client = clientFor(p)
        if (client.isLoggedIn) {
            _access.value = Access.LoggedIn
            return@withLock
        }
        val password = when {
            p.isDemo -> DemoGatewayClient.DEMO_PASSWORD
            sessionPassword?.first == p.id -> sessionPassword?.second
            else -> profiles.password(p)
        } ?: run {
            _access.value = Access.Anonymous
            throw NeedsLoginException()
        }
        _access.value = Access.LoggingIn
        try {
            client.login(p.username, password)
            _access.value = Access.LoggedIn
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException.Unreachable) {
            _access.value = Access.Anonymous
            throw e
        } catch (e: Exception) {
            _access.value = Access.Failed(e.userMessage())
            throw e
        }
    }

    /** Logs in with a typed password; saves it (encrypted) when [remember] is set. */
    suspend fun login(password: String, remember: Boolean, username: String? = null) {
        val p0 = active.value ?: throw NoGatewayException()
        val p = if (username != null && username != p0.username) p0.copy(username = username).also { profiles.save(it) } else p0
        val client = clientFor(p)
        _access.value = Access.LoggingIn
        try {
            client.login(p.username, password)
            _access.value = Access.LoggedIn
            if (remember) {
                profiles.setPassword(p.id, password)
                sessionPassword = null
            } else {
                sessionPassword = p.id to password
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _access.value = Access.Failed(e.userMessage())
            throw e
        }
    }

    suspend fun logout(forgetPassword: Boolean) {
        val p = active.value ?: return
        clientFor(p).logout()
        sessionPassword = null
        if (forgetPassword) profiles.setPassword(p.id, null)
        _access.value = Access.Anonymous
    }

    /** After the admin password was changed on the gateway. */
    suspend fun passwordChanged(username: String, password: String, remember: Boolean) {
        val p = active.value ?: return
        if (username != p.username) profiles.save(p.copy(username = username))
        if (remember) {
            profiles.setPassword(p.id, password)
        } else {
            sessionPassword = p.id to password
        }
    }
}
