package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.speed.SpeedPhase
import com.banner.hintcontrol.gateway.speed.SpeedProgress
import com.banner.hintcontrol.gateway.speed.SpeedTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SpeedTestTest {
    private lateinit var server: MockWebServer
    private val uploaded = AtomicLong(0)
    private var failAll = false

    private val config = SpeedTest.Config(
        pings = 3,
        streams = 2,
        downloadMs = 800,
        uploadMs = 800,
        chunkDownBytes = 300_000,
        chunkUpBytes = 100_000,
        firstUpBytes = 50_000,
        warmupMs = 100,
        tickMs = 50,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failAll) return MockResponse().setResponseCode(503)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/__down") -> {
                        val bytes = path.substringAfter("bytes=").toLong()
                        MockResponse()
                            .setBody(Buffer().write(ByteArray(bytes.toInt())))
                            .addHeader("cf-ray", "8f2e1a2b3c4d5e6f-TST")
                            .addHeader("Server-Timing", "cfRequestDuration;dur=0.5")
                    }
                    path.startsWith("/__up") -> {
                        uploaded.addAndGet(request.bodySize)
                        MockResponse().addHeader("cf-ray", "8f2e1a2b3c4d5e6f-TST")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun test() = SpeedTest(OkHttpClient(), server.url("/").toString().trimEnd('/'), config)

    @Test
    fun `measures ping, download and upload`() = runBlocking {
        val progress = CopyOnWriteArrayList<SpeedProgress>()
        val r = withContext(Dispatchers.Default) { test().run { progress += it } }
        assertNotNull(r.pingMs)
        assertNotNull(r.jitterMs)
        assertTrue("download ${r.downloadMbps}", (r.downloadMbps ?: 0.0) > 0)
        assertTrue("upload ${r.uploadMbps}", (r.uploadMbps ?: 0.0) > 0)
        assertEquals("TST", r.server)
        assertTrue(r.bytesDown > 0)
        assertTrue(r.bytesUp > 0)
        assertTrue(uploaded.get() > 0)
        assertEquals(listOf(SpeedPhase.Ping, SpeedPhase.Download, SpeedPhase.Upload), progress.map { it.phase }.distinct())
    }

    @Test
    fun `server errors fail the test`() = runBlocking {
        failAll = true
        try {
            withContext(Dispatchers.Default) { test().run() }
            fail("expected an error")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `stopping a test ends it promptly`() = runBlocking {
        val slow = SpeedTest(OkHttpClient(), server.url("/").toString().trimEnd('/'), config.copy(downloadMs = 60_000, uploadMs = 60_000, maxDownBytes = Long.MAX_VALUE))
        val job = async(Dispatchers.Default) { slow.run() }
        delay(700)
        withTimeout(5_000) {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `mbps maths`() {
        assertEquals(8.0, SpeedTest.mbps(1_000_000, 1_000)!!, 0.0001)
    }
}
