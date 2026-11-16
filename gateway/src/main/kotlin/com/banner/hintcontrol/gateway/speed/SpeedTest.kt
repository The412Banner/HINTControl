package com.banner.hintcontrol.gateway.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

enum class SpeedPhase { Ping, Download, Upload }

/** Live state while a test runs. [mbps] is the speed over the last second of the current phase. */
data class SpeedProgress(val phase: SpeedPhase, val mbps: Double? = null, val pingMs: Double? = null, val fraction: Float = 0f)

data class SpeedResult(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val pingMs: Double?,
    val jitterMs: Double?,
    /** Cloudflare data-centre code from the cf-ray header, e.g. "EWR". */
    val server: String?,
    val bytesDown: Long,
    val bytesUp: Long,
)

/**
 * Internet speed test against Cloudflare's public speed-test endpoints (the ones behind
 * speed.cloudflare.com; reference implementation github.com/cloudflare/speedtest, MIT):
 * `GET /__down?bytes=N` and `POST /__up`. Download and upload each run several connections in
 * parallel until a time or data limit is reached; the first [Config.warmupMs] are left out of the
 * result so TCP ramp-up doesn't drag the number down.
 */
class SpeedTest(
    http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val config: Config = Config(),
) {
    data class Config(
        val pings: Int = 12,
        val streams: Int = 6,
        val downloadMs: Long = 10_000,
        val uploadMs: Long = 8_000,
        val maxDownBytes: Long = 300_000_000,
        val maxUpBytes: Long = 100_000_000,
        val chunkDownBytes: Long = 25_000_000,
        /** Upload pieces start at [firstUpBytes] and double while they finish quickly, up to [chunkUpBytes]. */
        val chunkUpBytes: Int = 8_000_000,
        val firstUpBytes: Int = 256_000,
        val warmupMs: Long = 1_000,
        val tickMs: Long = 200,
    )

    private val http: OkHttpClient = http.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var server: String? = null

    suspend fun run(onProgress: (SpeedProgress) -> Unit = {}): SpeedResult {
        server = null
        onProgress(SpeedProgress(SpeedPhase.Ping))
        val latencies = ping(onProgress)
        val pingMs = latencies.median()
        val jitterMs = latencies.zipWithNext { a, b -> abs(b - a) }.takeIf { it.isNotEmpty() }?.average()

        onProgress(SpeedProgress(SpeedPhase.Download, pingMs = pingMs))
        val down = transfer(SpeedPhase.Download, config.downloadMs, config.maxDownBytes, pingMs, onProgress, ::downloadLoop)
        onProgress(SpeedProgress(SpeedPhase.Upload, pingMs = pingMs))
        val up = transfer(SpeedPhase.Upload, config.uploadMs, config.maxUpBytes, pingMs, onProgress, ::uploadLoop)
        if (down.mbps == null && up.mbps == null) throw IOException("The speed test server didn't respond.")
        return SpeedResult(down.mbps, up.mbps, pingMs, jitterMs, server, down.bytes, up.bytes)
    }

    private suspend fun ping(onProgress: (SpeedProgress) -> Unit): List<Double> = withContext(Dispatchers.IO) {
        // The first request sets up the connection (DNS, TLS) and isn't counted.
        val out = mutableListOf<Double>()
        repeat(config.pings + 1) { i ->
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            http.newCall(Request.Builder().url("$baseUrl/__down?bytes=0").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Speed test server answered HTTP ${r.code}")
                r.body?.bytes()
                noteServer(r)
                val totalMs = (System.nanoTime() - started) / 1e6
                if (i > 0) {
                    out += (totalMs - serverTimeMs(r)).coerceAtLeast(0.1)
                    onProgress(SpeedProgress(SpeedPhase.Ping, pingMs = out.median(), fraction = i / config.pings.toFloat()))
                }
            }
        }
        out
    }

    private data class Transfer(val mbps: Double?, val bytes: Long)

    /** Shared by the parallel streams of one phase. */
    private class Streams {
        val stop = AtomicBoolean(false)
        /** Download: bytes received. Upload: bytes the server confirmed (whole pieces). */
        val counter = AtomicLong(0)
        /** Upload: bytes handed to the network, including unfinished pieces (data used). */
        val sent = AtomicLong(0)
        val startedNanos = System.nanoTime()
        /** Upload: when the last piece was confirmed, in ms since the phase started. */
        val lastConfirmedMs = AtomicLong(0)
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

        fun <T> execute(call: Call, block: (Response) -> T): T {
            calls += call
            try {
                if (stop.get()) throw IOException("stopped")
                return call.execute().use(block)
            } finally {
                calls -= call
            }
        }

        fun stopAll() {
            stop.set(true)
            calls.forEach { it.cancel() }
        }
    }

    private suspend fun transfer(
        phase: SpeedPhase,
        durationMs: Long,
        maxBytes: Long,
        pingMs: Double?,
        onProgress: (SpeedProgress) -> Unit,
        loop: (Streams) -> Unit,
    ): Transfer = coroutineScope {
        val streams = Streams()
        val errors = AtomicLong(0)
        val started = streams.startedNanos
        val workers = List(config.streams) {
            launch(Dispatchers.IO) {
                try {
                    loop(streams)
                } catch (e: IOException) {
                    if (!streams.stop.get()) errors.incrementAndGet()
                }
            }
        }
        // (elapsed ms, bytes) every tick, for the live speed and the final result.
        val samples = mutableListOf(0L to 0L)
        try {
            while (isActive) {
                delay(config.tickMs)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val bytes = streams.counter.get()
                samples += elapsed to bytes
                val live = if (phase == SpeedPhase.Upload) {
                    // Upload is counted per confirmed piece, so a 1-second window would jump around.
                    mbps(bytes, elapsed)
                } else {
                    val windowStart = samples.lastOrNull { it.first <= elapsed - 1_000 } ?: samples.first()
                    mbps(bytes - windowStart.second, elapsed - windowStart.first)
                }
                onProgress(SpeedProgress(phase, live, pingMs, (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)))
                val allFailed = errors.get() >= config.streams && bytes == 0L
                val used = if (phase == SpeedPhase.Upload) streams.sent.get() else bytes
                if (elapsed >= durationMs || used >= maxBytes || allFailed || workers.all { it.isCompleted }) break
            }
        } finally {
            // Also runs when the test is cancelled, so blocked reads and writes end promptly.
            streams.stopAll()
        }
        workers.forEach { it.join() }
        if (phase == SpeedPhase.Upload) {
            val confirmed = streams.counter.get()
            val last = streams.lastConfirmedMs.get()
            Transfer(if (confirmed == 0L || last <= 0) null else mbps(confirmed, last), streams.sent.get())
        } else {
            val end = samples.last()
            val from = samples.firstOrNull { it.first >= config.warmupMs }?.takeIf { end.first - it.first >= 500 } ?: samples.first()
            val result = if (end.second == 0L) null else mbps(end.second - from.second, end.first - from.first)
            Transfer(result, streams.counter.get())
        }
    }

    private fun downloadLoop(s: Streams) {
        val buffer = Buffer()
        while (!s.stop.get()) {
            s.execute(http.newCall(Request.Builder().url("$baseUrl/__down?bytes=${config.chunkDownBytes}").build())) { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                noteServer(r)
                val source = r.body?.source() ?: throw IOException("empty response")
                while (!s.stop.get()) {
                    val n = source.read(buffer, 64 * 1024)
                    if (n == -1L) break
                    buffer.clear()
                    s.counter.addAndGet(n)
                }
            }
        }
    }

    private fun uploadLoop(s: Streams) {
        val block = Random.nextBytes(64 * 1024)
        var size = config.firstUpBytes.coerceAtMost(config.chunkUpBytes)
        while (!s.stop.get()) {
            val pieceSize = size
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = pieceSize.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var left = pieceSize
                    while (left > 0) {
                        if (s.stop.get()) throw IOException("stopped")
                        val n = minOf(left, block.size)
                        sink.write(block, 0, n)
                        sink.flush()
                        s.sent.addAndGet(n.toLong())
                        left -= n
                    }
                }
            }
            val pieceStarted = System.nanoTime()
            s.execute(http.newCall(Request.Builder().url("$baseUrl/__up").post(body).build())) { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                noteServer(r)
            }
            // Counted only once the server has the whole piece: the phone's send buffer can hold
            // megabytes, so counting at write time would report the buffer, not the connection.
            s.counter.addAndGet(pieceSize.toLong())
            s.lastConfirmedMs.set((System.nanoTime() - s.startedNanos) / 1_000_000)
            if ((System.nanoTime() - pieceStarted) / 1_000_000 < 1_500) size = (size * 2).coerceAtMost(config.chunkUpBytes)
        }
    }

    private fun noteServer(r: Response) {
        if (server == null) server = r.header("cf-ray")?.substringAfterLast('-', "")?.takeIf { it.length in 3..4 }
    }

    /** Server-side processing time from `Server-Timing: cfRequestDuration;dur=1.2`, so ping is network time only. */
    private fun serverTimeMs(r: Response): Double =
        r.header("Server-Timing")?.let { Regex("dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0

    companion object {
        const val DEFAULT_BASE_URL = "https://speed.cloudflare.com"

        fun mbps(bytes: Long, ms: Long): Double? = if (ms <= 0 || bytes < 0) null else bytes * 8.0 / ms / 1_000.0

        internal fun List<Double>.median(): Double? {
            if (isEmpty()) return null
            val s = sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
