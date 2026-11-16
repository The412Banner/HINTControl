package com.banner.hintcontrol.gateway

import com.banner.hintcontrol.gateway.model.CellData5G
import com.banner.hintcontrol.gateway.model.Client
import com.banner.hintcontrol.gateway.model.ClientLink
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.WifiRules
import com.banner.hintcontrol.gateway.signal.BandClass
import com.banner.hintcontrol.gateway.signal.Bands
import com.banner.hintcontrol.gateway.signal.CellMath
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.Quality
import com.banner.hintcontrol.gateway.signal.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalAndRulesTest {
    @Test
    fun ratings() {
        assertEquals(Quality.Excellent, SignalQuality.rate(Metric.RSRP, -79))
        assertEquals(Quality.Good, SignalQuality.rate(Metric.RSRP, -90))
        assertEquals(Quality.Fair, SignalQuality.rate(Metric.RSRP, -100))
        assertEquals(Quality.Poor, SignalQuality.rate(Metric.RSRP, -101))
        assertEquals(Quality.Good, SignalQuality.rate(Metric.SINR, 13))
        assertEquals(Quality.Poor, SignalQuality.rate(Metric.SINR, -1))
        assertNull(SignalQuality.rate(Metric.RSRQ, null))
    }

    @Test
    fun `overall takes the worse reading`() {
        val strongButNoisy = CellData5G(rsrp = -75, sinr = -3)
        assertEquals(Quality.Poor, SignalQuality.overall(strongButNoisy))
    }

    @Test
    fun scores() {
        assertEquals(100, SignalQuality.score(-70, 30, -3))
        assertEquals(0, SignalQuality.score(-130, -10, -25))
        assertTrue(SignalQuality.score(-97, null, null)!! in 49..53)
        assertNull(SignalQuality.score(null, null, null))
        val mid = SignalQuality.score(-95, 10, -12)!!
        assertTrue(mid in 40..60)
    }

    @Test
    fun gauge() {
        assertEquals(0f, SignalQuality.fraction(Metric.RSRP, -200))
        assertEquals(1f, SignalQuality.fraction(Metric.RSRP, -10))
        assertEquals(0.5f, SignalQuality.fraction(Metric.SINR, 10), 0.001f)
    }

    @Test
    fun bands() {
        val n41 = Bands.describe("n41", nrHint = false)
        assertTrue(n41.isNr)
        assertEquals("2.5 GHz", n41.frequency)
        assertEquals(BandClass.Mid, n41.bandClass)
        val b71 = Bands.describe("B71", nrHint = true)
        assertFalse(b71.isNr)
        assertEquals("b71", b71.id)
        assertEquals(BandClass.Low, b71.bandClass)
        assertEquals("n71", Bands.describe("71", nrHint = true).id)
        assertEquals("b2", Bands.describe("LTE BAND 2", nrHint = true).id)
        assertNull(Bands.describe("n999", nrHint = true).frequency)
    }

    @Test
    fun `enb from ecgi`() {
        assertEquals(310463L to 12, CellMath.lteFromEcgi("31026079478540", "310", "260"))
        assertEquals(0L to 0, CellMath.lteFromEcgi("3102600", "310", "260"))
        assertNull(CellMath.lteFromEcgi("abc", "310", "260"))
        assertNull(CellMath.lteFromEcgi(null, null, null))
    }

    @Test
    fun `wifi rules`() {
        assertTrue(WifiRules.isValidWpaKey("12345678"))
        assertFalse(WifiRules.isValidWpaKey("1234567"))
        assertFalse(WifiRules.isValidWpaKey("a".repeat(64)))
        assertFalse(WifiRules.isValidWpaKey("pässword1"))
        assertTrue(WifiRules.isValidSsid("Home"))
        assertFalse(WifiRules.isValidSsid(" "))
        assertFalse(WifiRules.isValidSsid("x".repeat(33)))
        assertEquals(59, WifiBand.SixGig.channels.size)
        assertEquals("233", WifiBand.SixGig.channels.last())
    }

    @Test
    fun `private mac detection`() {
        fun c(mac: String) = Client(ClientLink.Wired, true, null, null, emptyList(), mac, null)
        assertTrue(c("DA:A1:19:3E:47:5B").isPrivateMac)
        assertTrue(c("5e-0b-2d-88-91-c4").isPrivateMac)
        assertFalse(c("A4:83:E7:1C:22:10").isPrivateMac)
    }

    @Test
    fun addresses() {
        assertEquals("192.168.12.1", GatewayAddress.normalize(" http://192.168.12.1/web/ "))
        assertEquals("192.168.12.1:8080", GatewayAddress.normalize("192.168.12.1:8080"))
        assertEquals(GatewayAddress.DEFAULT_HOST, GatewayAddress.normalize(""))
        assertTrue(GatewayAddress.hasPort("10.0.0.1:81"))
        assertFalse(GatewayAddress.isPlausible("not a host!"))
    }

    @Test
    fun redaction() {
        val json = """{"username":"admin","password":"hunter2","wpaKey":"secret123"}"""
        val out = Redact.text(json)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("secret123"))
        assertTrue(out.contains("admin"))
        assertFalse(Redact.text("name=admin&pswd=abc").contains("abc"))
        assertFalse(Redact.text("Authorization: Bearer abc.def").contains("abc.def"))
    }
}
