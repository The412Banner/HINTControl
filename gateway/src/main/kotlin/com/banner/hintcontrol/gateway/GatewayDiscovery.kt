package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.nokia.NokiaGatewayClient
import com.banner.hintcontrol.gateway.tmi.TmiGatewayClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

data class DetectedGateway(
    val kind: GatewayKind,
    val host: String,
    val model: String?,
)

object GatewayDiscovery {
    fun clientFor(kind: GatewayKind, host: String, http: OkHttpClient = GatewayHttp.base): GatewayClient = when (kind) {
        GatewayKind.Tmi -> TmiGatewayClient(host, http)
        GatewayKind.Nokia -> NokiaGatewayClient(host, http)
        GatewayKind.Demo -> com.banner.hintcontrol.gateway.demo.DemoGatewayClient()
    }

    /**
     * Tries every host in order (all hosts in parallel, both API styles per host) and returns
     * the first that answers. TMI wins over Nokia on the same host, as in the old app.
     */
    suspend fun detect(
        hosts: List<String>,
        http: OkHttpClient = GatewayHttp.base,
        timeoutMs: Long = GatewayHttp.PROBE_TIMEOUT_MS,
    ): DetectedGateway {
        val candidates = hosts.map(GatewayAddress::normalize).distinct()
        val found = coroutineScope {
            candidates.map { host ->
                async {
                    val tmi = TmiGatewayClient(host, http)
                    val nokia = NokiaGatewayClient(host, http)
                    val tmiOk = async { tmi.exists(timeoutMs) }
                    val nokiaOk = async { nokia.exists(timeoutMs) }
                    when {
                        tmiOk.await() -> {
                            nokiaOk.cancel()
                            tmi
                        }
                        nokiaOk.await() -> nokia
                        else -> null
                    }
                }
            }.awaitAll()
        }
        val client = found.firstOrNull { it != null } ?: throw GatewayException.NoGatewayFound(candidates)
        val model = runCatching { client.mainData().device?.displayName }.getOrNull()
        return DetectedGateway(client.kind, client.host, model)
    }
}
