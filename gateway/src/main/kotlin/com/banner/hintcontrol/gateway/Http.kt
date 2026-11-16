package com.banner.hintcontrol.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResult(
    val code: Int,
    val headers: Headers,
    val body: String,
    val tookMs: Long,
) {
    val isSuccess: Boolean get() = code in 200..299
}

object GatewayHttp {
    const val DEFAULT_TIMEOUT_MS = 20_000L
    const val PROBE_TIMEOUT_MS = 5_000L

    fun newClient(timeoutMs: Long = DEFAULT_TIMEOUT_MS): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.coerceAtMost(10_000L), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Shared connection pool; per-gateway clients derive from it. */
    val base: OkHttpClient by lazy { newClient() }

    fun OkHttpClient.withTimeout(timeoutMs: Long): OkHttpClient = newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs + 1_000L, TimeUnit.MILLISECONDS)
        .build()
}

/** Runs the request without blocking a thread; cancelling the coroutine cancels the call. */
suspend fun OkHttpClient.await(request: Request): HttpResult {
    val started = System.nanoTime()
    val call = newCall(request)
    val response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
    return response.use {
        HttpResult(
            code = it.code,
            headers = it.headers,
            body = it.body?.string().orEmpty(),
            tookMs = (System.nanoTime() - started) / 1_000_000,
        )
    }
}

/** Removes passwords, keys and tokens before text is shown or shared. */
object Redact {
    private val patterns = listOf(
        Regex("(\"(?:password|passwordNew|pswd|wpaKey|PreSharedKey|token|WEPKey)\"\\s*:\\s*)\"[^\"]*\"", RegexOption.IGNORE_CASE),
        Regex("((?:password|pswd|token)=)[^&\\s]*", RegexOption.IGNORE_CASE),
        Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+"),
    )

    fun text(input: String): String = patterns.fold(input) { acc, p ->
        p.replace(acc) { m -> m.groupValues[1] + if (m.value.contains('"')) "\"•••\"" else "•••" }
    }
}
