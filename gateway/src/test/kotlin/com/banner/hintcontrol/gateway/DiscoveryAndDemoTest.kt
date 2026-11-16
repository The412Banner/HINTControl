package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.demo.DemoGatewayClient
import com.banner.hintcontrol.gateway.model.flatten
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiscoveryAndDemoTest {
    private fun server(handler: (String) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request.path.orEmpty())
        }
        start()
    }

    @Test
    fun `detects tmi gateway`() = runTest {
        val s = server { path ->
            if (path == "/TMI/v1/gateway/?get=all") MockResponse().setBody(fixture("tmi_gateway.json"))
            else MockResponse().setResponseCode(404)
        }
        try {
            val found = GatewayDiscovery.detect(listOf("${s.hostName}:${s.port}"))
            assertEquals(GatewayKind.Tmi, found.kind)
            assertEquals("Arcadyan KVD21", found.model)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `detects nokia gateway`() = runTest {
        val s = server { path ->
            when (path) {
                "/dashboard_device_status_web_app.cgi" -> MockResponse().setBody("{}")
                "/dashboard_device_info_status_web_app.cgi" -> MockResponse().setBody(fixture("nokia_device_info.json"))
                "/cell_status_app.cgi" -> MockResponse().setBody(fixture("nokia_cell_status.json"))
                "/fastmile_radio_status_web_app.cgi" -> MockResponse().setBody(fixture("nokia_radio_status.json"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        try {
            val found = GatewayDiscovery.detect(listOf("${s.hostName}:${s.port}"))
            assertEquals(GatewayKind.Nokia, found.kind)
            assertEquals("Nokia 5G21", found.model)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `reports when nothing answers`() = runTest {
        val s = server { MockResponse().setResponseCode(404) }
        val host = "${s.hostName}:${s.port}"
        try {
            GatewayDiscovery.detect(listOf(host))
            fail()
        } catch (e: GatewayException.NoGatewayFound) {
            assertEquals(listOf(host), e.tried)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `demo gateway behaves like a gateway`() = runTest {
        var now = 1_700_000_000_000L
        val demo = DemoGatewayClient(clock = { now }, latencyMs = 0, rebootDowntimeMs = 20_000)
        assertTrue(demo.mainData().signal?.fiveG?.rsrp != null)
        try {
            demo.clients()
            fail()
        } catch (_: GatewayException.Unauthorized) {
        }
        demo.login("admin", DemoGatewayClient.DEMO_PASSWORD)
        assertEquals(8, demo.clients().flatten().size)
        val wifi = demo.wifiConfig()
        demo.setWifiConfig(wifi.copy(bandSteering = wifi.bandSteering?.copy(isEnabled = false)))
        assertEquals(false, demo.wifiConfig().bandSteering?.isEnabled)

        demo.reboot()
        assertFalse(demo.exists())
        try {
            demo.mainData()
            fail()
        } catch (_: GatewayException.Unreachable) {
        }
        now += 21_000
        assertTrue(demo.exists())
        assertTrue(demo.mainData().time?.upTime!! in 0..2)
    }
}
