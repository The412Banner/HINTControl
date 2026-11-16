package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.model.flatten
import com.banner.hintcontrol.gateway.nokia.NokiaGatewayClient
import com.banner.hintcontrol.gateway.nokia.NokiaMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NokiaGatewayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NokiaGatewayClient
    private val requests = mutableListOf<RecordedRequest>()
    private var session = "s1"
    private var forbidOnce = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().trimStart('/')
                val hasSession = request.getHeader("Cookie")?.contains("sid=$session") == true
                fun authed(body: () -> MockResponse): MockResponse {
                    if (forbidOnce) {
                        forbidOnce = false
                        return MockResponse().setResponseCode(403)
                    }
                    return if (hasSession) body() else MockResponse().setResponseCode(403)
                }
                return when (path) {
                    "login_app.cgi" -> {
                        val form = request.body.clone().readUtf8()
                        when {
                            form == "action=Reboot" -> authed { MockResponse().setBody("{}") }
                            form.contains("pswd=pw") -> MockResponse()
                                .addHeader("Set-Cookie", "sid=$session; path=/; HttpOnly")
                                .setBody("""{"result":0}""")
                            else -> MockResponse().setResponseCode(401)
                        }
                    }
                    "dashboard_device_status_web_app.cgi" -> MockResponse().setBody("{}")
                    "dashboard_device_info_status_web_app.cgi" -> MockResponse().setBody(fixture("nokia_device_info.json"))
                    "cell_status_app.cgi" -> MockResponse().setBody(fixture("nokia_cell_status.json"))
                    "fastmile_radio_status_web_app.cgi" -> MockResponse().setBody(fixture("nokia_radio_status.json"))
                    "fastmile_statistics_status_web_app.cgi" -> authed { MockResponse().setBody(fixture("nokia_statistics.json")) }
                    "wlan_list_app.cgi" -> authed { MockResponse().setBody(fixture("nokia_wlan_list.json")) }
                    "service_function_app.cgi" -> authed { MockResponse().setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = NokiaGatewayClient("127.0.0.1", baseUrlOverride = server.url("/").toString(), wifiSettleMs = 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login sends typed credentials as form`() = runTest {
        client.login("admin", "pw")
        assertEquals("name=admin&pswd=pw", requests.first().body.readUtf8())
        assertTrue(client.isLoggedIn)
    }

    @Test
    fun `status maps lte and hides disconnected 5g`() = runTest {
        val main = client.mainData()
        assertEquals("Nokia 5G21", main.device?.displayName)
        assertEquals(-98, main.signal?.fourG?.rsrp)
        assertEquals(listOf("B66"), main.signal?.fourG?.bands)
        assertNull("5G radio reports 0xFFFFFFFF = not connected", main.signal?.fiveG)
        assertEquals(false, main.signal?.generic?.roaming)
        assertEquals(true, main.signal?.generic?.hasIPv6)
        assertEquals(86400L, main.time?.upTime)
    }

    @Test
    fun `cookie session is reused and renewed after 403`() = runTest {
        client.login("admin", "pw")
        client.simData()
        assertTrue(requests.last().getHeader("Cookie")!!.contains("sid=s1"))
        session = "s2"
        val sim = client.simData()
        assertEquals("8901260000000000001", sim.sim?.iccId)
        assertEquals(2, requests.count { it.path == "/login_app.cgi" })
    }

    @Test
    fun `sim status valid maps to true`() = runTest {
        client.login("admin", "pw")
        assertEquals(true, client.simData().sim?.status)
    }

    @Test
    fun `clients split by interface`() = runTest {
        client.login("admin", "pw")
        val list = client.clients().flatten()
        assertEquals(3, list.size)
        assertFalse(list.first { it.name == "Plug" }.connected)
    }

    @Test
    fun `wifi round trip keeps ids and converts security names`() = runTest {
        client.login("admin", "pw")
        val wifi = client.wifiConfig()
        assertFalse(wifi.canAddAndRemove)
        val five = wifi.ssids!!.first { it.ssidName == "Nokia-5" }
        assertEquals("WPA2", five.encryptionVersion)
        assertEquals(false, five.isBroadcastEnabled)
        assertEquals("WPA/WPA2", wifi.ssids!!.first().encryptionVersion)

        val edited = wifi.copy(ssids = wifi.ssids!!.map { if (it.ssidId == 5) it.copy(isBroadcastEnabled = true) else it })
        client.setWifiConfig(edited)
        val body = Json.parseToJsonElement(requests.last { it.path == "/service_function_app.cgi" }.body.readUtf8()).jsonObject
        assertEquals("WLANConfig", body["action"]!!.jsonPrimitive.content)
        val list = body["paralist"] as JsonArray
        val second = list[1].jsonObject
        assertEquals("11i", second["BeaconType"]!!.jsonPrimitive.content)
        assertEquals("AESEncryption", second["WPAEncryptionModes"]!!.jsonPrimitive.content)
        assertTrue(second["SSIDAdvertisementEnabled"]!!.jsonPrimitive.boolean)
        assertEquals("5", (second["id"] as JsonArray).single().jsonPrimitive.content)
    }

    @Test
    fun `reboot sends action and form`() = runTest {
        client.login("admin", "pw")
        assertEquals(RebootResult.Accepted, client.reboot())
        val service = requests.last { it.path == "/service_function_app.cgi" }
        assertEquals("""{"action":"Reboot"}""", service.body.readUtf8())
        assertEquals("action=Reboot", requests.last().body.readUtf8())
    }

    @Test
    fun `change login is not supported`() = runTest {
        try {
            client.setLogin("a", "b")
            org.junit.Assert.fail()
        } catch (_: GatewayException.NotSupported) {
        }
    }

    @Test
    fun `security name conversion`() {
        assertEquals("WPA/WPA2", NokiaMapper.encryptionToTmi("WPAand11i"))
        assertEquals("WPAand11i", NokiaMapper.encryptionToNokia("WPA/WPA2"))
        assertEquals("11i", NokiaMapper.encryptionToNokia("WPA2"))
    }
}
