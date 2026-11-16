package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.flatten
import com.banner.hintcontrol.gateway.tmi.TmiGatewayClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

class TmiGatewayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TmiGatewayClient
    private val requests = mutableListOf<RecordedRequest>()
    private var validToken = "token-1"
    private var wifi408Left = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().removePrefix("/TMI/v1/")
                val auth = request.getHeader("Authorization")
                fun authed(body: () -> MockResponse) =
                    if (auth == "Bearer $validToken") body() else MockResponse().setResponseCode(401)
                return when {
                    path == "auth/login" -> {
                        val sent = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                        when (sent["password"]?.jsonPrimitive?.content) {
                            "pw" -> MockResponse().setBody("""{"auth":{"expiration":1,"refreshCountLeft":4,"refreshCountMax":4,"token":"$validToken"}}""")
                            "locked" -> MockResponse().setBody("""{"result":{"error":"locked","message":"Too many failed attempts","statusCode":200}}""")
                            else -> MockResponse().setResponseCode(401)
                        }
                    }
                    path == "gateway/?get=all" -> MockResponse().setBody(fixture("tmi_gateway.json"))
                    path == "network/configuration/v2?get=ap" -> authed {
                        if (wifi408Left > 0) {
                            wifi408Left--
                            MockResponse().setResponseCode(408)
                        } else {
                            MockResponse().setBody(fixture("tmi_wifi.json"))
                        }
                    }
                    path == "network/configuration/v2?set=ap" -> authed { MockResponse().setBody("") }
                    path == "network/telemetry/?get=clients" -> authed { MockResponse().setBody(fixture("tmi_clients.json")) }
                    path == "network/telemetry/?get=cell" -> authed { MockResponse().setBody(fixture("tmi_cell.json")) }
                    path == "network/telemetry/?get=sim" -> authed { MockResponse().setBody(fixture("tmi_sim.json")) }
                    path == "gateway/reset?set=reboot" -> authed { MockResponse().setBody("") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = TmiGatewayClient("127.0.0.1", baseUrlOverride = server.url("/TMI/v1/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `status works without login`() = runTest {
        val data = client.mainData()
        assertEquals("Arcadyan KVD21", data.device?.displayName)
        assertEquals(-88, data.signal?.fiveG?.rsrp)
        assertEquals(listOf("n41"), data.signal?.fiveG?.bands)
        assertEquals(310463L, data.signal?.fourG?.nbid)
        assertEquals(30996L, data.time?.upTime)
        assertNull(requests.last().getHeader("Authorization"))
    }

    @Test
    fun `login sends credentials and uses bearer token`() = runTest {
        client.login("admin", "pw")
        assertTrue(client.isLoggedIn)
        val loginBody = Json.parseToJsonElement(requests.first().body.readUtf8()).jsonObject
        assertEquals("admin", loginBody["username"]?.jsonPrimitive?.content)
        client.simData()
        val last = requests.last()
        assertEquals("Bearer token-1", last.getHeader("Authorization"))
        assertEquals(TmiGatewayClient.USER_AGENT, last.getHeader("User-Agent"))
    }

    @Test
    fun `expired token is renewed once`() = runTest {
        client.login("admin", "pw")
        validToken = "token-2"
        val cell = client.cellData()
        assertEquals("31026079478540", cell.cell?.fourG?.ecgi)
        assertEquals(39.9526, cell.cell?.gps?.latitude!!, 0.0001)
        assertEquals(2, requests.count { it.path!!.endsWith("auth/login") })
    }

    @Test
    fun `wrong password is unauthorized`() = runTest {
        try {
            client.login("admin", "nope")
            fail()
        } catch (e: GatewayException.Unauthorized) {
            assertFalse(client.isLoggedIn)
        }
    }

    @Test
    fun `login without token reports lockout message`() = runTest {
        try {
            client.login("admin", "locked")
            fail()
        } catch (e: GatewayException.TooManyAttempts) {
            assertEquals("Too many failed attempts", e.message)
        }
    }

    @Test
    fun `wifi read retries while gateway answers 408`() = runTest {
        client.login("admin", "pw")
        wifi408Left = 2
        val wifi = client.wifiConfig()
        assertEquals("WIFI_SSID", wifi.ssids?.single()?.ssidName)
        assertNull(wifi.sixGig)
        assertEquals(3, requests.count { it.path!!.endsWith("get=ap") })
    }

    @Test
    fun `wifi save body matches the old app`() = runTest {
        client.login("admin", "pw")
        val wifi = client.wifiConfig()
        val changed = wifi.withBand(WifiBand.FiveGig, wifi.fiveGig?.copy(channel = "149"))
        client.setWifiConfig(changed)
        val body = Json.parseToJsonElement(requests.last { it.path!!.endsWith("set=ap") }.body.readUtf8()).jsonObject
        assertEquals("149", body["5.0ghz"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
        assertTrue(body["canAddAndRemove"]!!.jsonPrimitive.boolean)
        assertFalse("6 GHz must be left out when absent", body.containsKey("6.0ghz"))
        val ssid = body["ssids"]!!.let { it as kotlinx.serialization.json.JsonArray }.first().jsonObject
        assertEquals(JsonNull, ssid["ssidId"])
        assertFalse(ssid.containsKey("6.0ghzSsid"))
        assertTrue(ssid["canEditFrequencyAndGuest"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `six ghz is sent when present`() = runTest {
        client.login("admin", "pw")
        val wifi = client.wifiConfig().copy(sixGig = BandConfig(channel = "37", channelBandwidth = "160MHz"))
        client.setWifiConfig(wifi)
        val body = Json.parseToJsonElement(requests.last().body.readUtf8()).jsonObject
        assertEquals("37", body["6.0ghz"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
        assertEquals("80MHz", body["5.0ghz"]!!.jsonObject["channelBandwidth"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clients parse with trailing comma`() = runTest {
        client.login("admin", "pw")
        val list = client.clients().flatten()
        assertEquals(2, list.size)
        val wired = list.first { it.mac == "D2:B5:49:86:71:DE" }
        assertNull(wired.name)
        assertEquals(listOf("fe80::7a45:58ff:fee6:72c6"), wired.ipv6)
        assertTrue(wired.isPrivateMac)
        assertEquals(-61, list.first { it.name == "Thermostat" }.signal)
    }

    @Test
    fun `reboot posts to reset endpoint`() = runTest {
        client.login("admin", "pw")
        assertEquals(RebootResult.Accepted, client.reboot())
        val last = requests.last()
        assertEquals("POST", last.method)
        assertTrue(last.path!!.endsWith("gateway/reset?set=reboot"))
    }

    @Test
    fun `calls needing login fail cleanly before login`() = runTest {
        try {
            client.clients()
            fail()
        } catch (e: GatewayException.Unauthorized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `exists distinguishes gateway from other servers`() = runTest {
        assertTrue(client.exists())
        val other = TmiGatewayClient("127.0.0.1", baseUrlOverride = server.url("/nothing/").toString())
        assertFalse(other.exists())
    }

    @Test
    fun `offline gateway is unreachable`() = runTest {
        server.shutdown()
        try {
            client.mainData()
            fail()
        } catch (e: GatewayException.Unreachable) {
            assertEquals("127.0.0.1", e.host)
        }
    }
}
