package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.demo.DemoBootTimeStore
import com.banner.hintcontrol.gateway.demo.DemoGatewayClient
import com.banner.hintcontrol.gateway.tmi.TmiGatewayClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class FirmwareUploadTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var tokens = 0
    private var uploadCode = 200
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path.orEmpty().removePrefix("/TMI/v1/")) {
                    "auth/login" -> MockResponse().setBody("""{"auth":{"expiration":1,"refreshCountLeft":4,"refreshCountMax":4,"token":"t${++tokens}"}}""")
                    "gateway/upgrade" ->
                        if (request.getHeader("Authorization") == "Bearer t$tokens") MockResponse().setResponseCode(uploadCode).setBody("")
                        else MockResponse().setResponseCode(401)
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

    private fun client() = TmiGatewayClient("127.0.0.1", baseUrlOverride = server.url("/TMI/v1/").toString())

    private fun file() = FirmwareFile("fw.bin", payload.size.toLong()) { ByteArrayInputStream(payload) }

    @Test
    fun `sends the file like the gateway's web page`() = runTest {
        val c = client()
        c.login("admin", "pw")
        val progress = mutableListOf<Long>()
        assertEquals(FirmwareUploadResult.Accepted, c.uploadFirmware(file()) { progress += it })

        // A fresh login for the upload (the web page logs in on port 80), then one POST.
        val upload = requests.last()
        assertEquals("auth/login", requests[requests.size - 2].path!!.removePrefix("/TMI/v1/"))
        assertEquals("POST", upload.method)
        assertEquals("/TMI/v1/gateway/upgrade", upload.path)
        assertEquals("Bearer t2", upload.getHeader("Authorization"))
        assertEquals("application/octet-stream", upload.getHeader("Content-Type"))
        assertEquals("no-cache", upload.getHeader("Cache-Control"))
        assertEquals("application/json", upload.getHeader("Accept"))
        assertArrayEquals(payload, upload.body.readByteArray())
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(1, requests.count { it.path!!.endsWith("gateway/upgrade") })
    }

    @Test
    fun `a refused file is reported with the gateway's answer and not resent`() = runTest {
        uploadCode = 400
        val c = client()
        c.login("admin", "pw")
        try {
            c.uploadFirmware(file()) {}
            fail("expected an error")
        } catch (e: GatewayException.Http) {
            assertEquals(400, e.code)
        }
        assertEquals(1, requests.count { it.path!!.endsWith("gateway/upgrade") })
    }

    @Test
    fun `needs a login first`() = runTest {
        try {
            client().uploadFirmware(file()) {}
            fail("expected an error")
        } catch (_: GatewayException.Unauthorized) {
        }
        assertTrue(requests.none { it.path!!.endsWith("gateway/upgrade") })
    }

    @Test
    fun `only offered where the gateway's own page has it`() {
        assertTrue(FirmwareSupport.isSupported(GatewayKind.Tmi, "TMO-G5AR"))
        assertFalse(FirmwareSupport.isSupported(GatewayKind.Tmi, "Arcadyan KVD21"))
        assertFalse(FirmwareSupport.isSupported(GatewayKind.Tmi, null))
        assertFalse(FirmwareSupport.isSupported(GatewayKind.Nokia, "5G21"))
        assertTrue(FirmwareSupport.isSupported(GatewayKind.Demo, "G5AR"))
    }

    @Test
    fun `demo installs and restarts with the next version, kept across app restarts`() = runTest {
        var now = 1_000_000_000L
        val store = object : DemoBootTimeStore {
            var booted: Long? = null
            var fw: String? = null
            override fun load() = booted
            override fun save(bootedAt: Long) { booted = bootedAt }
            override fun loadFirmware() = fw
            override fun saveFirmware(version: String) { fw = version }
        }
        val demo = DemoGatewayClient(clock = { now }, latencyMs = 0, rebootDowntimeMs = 20_000, bootTime = store, uploadChunkDelayMs = 0)
        assertEquals(DemoGatewayClient.DEMO_FIRMWARE, demo.mainData().device?.softwareVersion)
        demo.login("admin", DemoGatewayClient.DEMO_PASSWORD)
        val progress = mutableListOf<Long>()
        assertEquals(FirmwareUploadResult.Accepted, demo.uploadFirmware(file()) { progress += it })
        assertEquals(payload.size.toLong(), progress.last())
        try {
            demo.mainData()
            fail("demo should be restarting")
        } catch (_: GatewayException.Unreachable) {
        }
        now += 21_000
        assertEquals("1.2403.0.13", demo.mainData().device?.softwareVersion)
        val restarted = DemoGatewayClient(clock = { now }, latencyMs = 0, bootTime = store)
        assertEquals("1.2403.0.13", restarted.mainData().device?.softwareVersion)
    }
}
