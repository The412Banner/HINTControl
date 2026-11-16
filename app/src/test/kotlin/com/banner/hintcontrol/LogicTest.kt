package com.banner.hintcontrol

import com.banner.hintcontrol.data.RebootSchedule
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.SSIDConfig
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.WifiConfig
import com.banner.hintcontrol.ui.components.ChartPoint
import com.banner.hintcontrol.ui.components.nearestTime
import com.banner.hintcontrol.ui.components.wifiQrText
import com.banner.hintcontrol.ui.wifi.WifiChanges
import com.banner.hintcontrol.ui.wifi.WifiDraft
import com.banner.hintcontrol.update.AppVersion
import com.banner.hintcontrol.update.GhAsset
import com.banner.hintcontrol.update.GhRelease
import com.banner.hintcontrol.update.UpdateRepository
import com.banner.hintcontrol.work.RebootTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun `chart cursor snaps to the nearest reading`() {
        // Plot 100 px wide after a 0 px label column; readings at t = 0, 30, 100 (of 0..100).
        val pts = listOf(ChartPoint(0, 1f), ChartPoint(30, 2f), ChartPoint(100, 3f))
        assertEquals(30L, nearestTime(pts, 0, 100, 0f, 100, 40f))
        assertEquals(100L, nearestTime(pts, 0, 100, 0f, 100, 70f))
        assertEquals(0L, nearestTime(pts, 0, 100, 0f, 100, -20f))
        assertNull(nearestTime(emptyList(), 0, 100, 0f, 100, 50f))
    }

    @Test
    fun `version ordering`() {
        assertTrue(v("1.0.0-pre1") < v("1.0.0-pre2"))
        assertTrue(v("1.0.0-pre2") < v("1.0.0"))
        assertTrue(v("v1.0.0") < v("1.0.1-pre1"))
        assertTrue(v("1.2") == v("1.2.0") || v("1.2").compareTo(v("1.2.0")) == 0)
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0-beta.2") > v("2.0.0-beta.1"))
        assertEquals("1.0.0-pre1", v("v1.0.0-pre1").toString())
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun `update pick respects channel and needs an apk`() {
        fun rel(tag: String, pre: Boolean, apk: Boolean = true, draft: Boolean = false) = GhRelease(
            tag = tag,
            prerelease = pre,
            draft = draft,
            assets = if (apk) listOf(GhAsset("HintPlus-$tag.apk", "https://x/$tag.apk", 1), GhAsset("HintPlus-$tag.apk.sha256", "https://x/$tag.sha", 1)) else emptyList(),
        )
        val list = listOf(rel("v1.0.0", false), rel("v1.1.0-pre1", true), rel("v1.2.0", false, apk = false), rel("v2.0.0", false, draft = true))
        assertEquals("v1.0.0", UpdateRepository.pick(list, includePrereleases = false)?.tag)
        val pre = UpdateRepository.pick(list, includePrereleases = true)!!
        assertEquals("v1.1.0-pre1", pre.tag)
        assertEquals("HintPlus-v1.1.0-pre1.apk.sha256", pre.checksum?.name)
    }

    @Test
    fun `next reboot time`() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 16, 3, 30, 0, 0, zone).toInstant() // Monday
        val daily = RebootSchedule(enabled = true, hour = 4, minute = 0)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 4, 0, 0, 0, zone).toInstant(), RebootTimes.next(daily, now, zone))
        val later = ZonedDateTime.of(2026, 11, 16, 5, 0, 0, 0, zone).toInstant()
        assertEquals(ZonedDateTime.of(2026, 11, 17, 4, 0, 0, 0, zone).toInstant(), RebootTimes.next(daily, later, zone))
        val sundays = daily.copy(days = setOf(7))
        assertEquals(ZonedDateTime.of(2026, 11, 22, 4, 0, 0, 0, zone).toInstant(), RebootTimes.next(sundays, later, zone))
        assertNull(RebootTimes.next(daily.copy(enabled = false), now, zone))
        assertNull(RebootTimes.next(daily.copy(days = emptySet()), now, zone))
        assertEquals("Weekdays at 04:00", daily.copy(days = setOf(1, 2, 3, 4, 5)).summary())
        assertEquals("Off", RebootSchedule().summary())
    }

    @Test
    fun `reboot time across daylight saving change`() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone).toInstant()
        val next = RebootTimes.next(RebootSchedule(enabled = true, hour = 4, minute = 0), before, zone)!!
        assertEquals(4, next.atZone(zone).hour)
        assertTrue(next.isAfter(Instant.from(before)))
    }

    private val base = WifiConfig(
        twoGig = BandConfig(channel = "Auto", channelBandwidth = "Auto", isRadioEnabled = true, transmissionPower = "100%"),
        fiveGig = BandConfig(channel = "Auto", channelBandwidth = "80MHz", isRadioEnabled = true, transmissionPower = "100%"),
        ssids = listOf(
            SSIDConfig(twoGigSsid = true, fiveGigSsid = true, ssidName = "Home", wpaKey = "password1", encryptionVersion = "WPA2/WPA3", isBroadcastEnabled = true),
            SSIDConfig(twoGigSsid = true, fiveGigSsid = false, ssidName = "Guest", wpaKey = "password2", encryptionVersion = "WPA2", isBroadcastEnabled = true, guest = true),
        ),
    )

    @Test
    fun `wifi change list`() {
        val d = WifiDraft.of(base)
            .updateBand(WifiBand.FiveGig) { it.copy(channel = "149") }
            .updateSsid(0, base.ssids!![0].copy(isBroadcastEnabled = false))
            .removeSsid(1)
            .addSsid(SSIDConfig(ssidName = "IoT", wpaKey = "password3"))
        val changes = WifiChanges.describe(base, d)
        assertTrue(changes.toString(), "5 GHz channel Auto → 149" in changes)
        assertTrue(changes.toString(), "\"Home\" hidden" in changes)
        assertTrue(changes.toString(), "Remove network \"Guest\"" in changes)
        assertTrue(changes.toString(), "New network \"IoT\"" in changes)
        assertEquals(emptyList<String>(), WifiChanges.describe(base, WifiDraft.of(base)))
    }

    @Test
    fun `wifi warnings`() {
        val allOff = WifiDraft.of(base).updateBand(WifiBand.TwoGig) { it.copy(isRadioEnabled = false) }.updateBand(WifiBand.FiveGig) { it.copy(isRadioEnabled = false) }
        val w = WifiChanges.warnings(base, allOff, GatewayKind.Tmi)
        assertTrue(WifiChanges.Warning.AllRadiosOff in w)
        assertTrue(WifiChanges.Warning.FiveGhzOff in w)
        assertTrue(WifiChanges.Warning.FiveGhzOff !in WifiChanges.warnings(base, allOff, GatewayKind.Nokia))
        val renamed = WifiDraft.of(base).updateSsid(0, base.ssids!![0].copy(ssidName = "Home2"))
        assertEquals(listOf(WifiChanges.Warning.Disconnect), WifiChanges.warnings(base, renamed, GatewayKind.Tmi))
    }

    @Test
    fun `wifi qr text`() {
        assertEquals("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss;;", wifiQrText("My;Net", "pa:ss", "WPA2/WPA3", hidden = false))
        assertEquals("WIFI:T:SAE;S:A;P:b;H:true;;", wifiQrText("A", "b", "WPA3", hidden = true))
        assertEquals("WIFI:T:nopass;S:Open;;", wifiQrText("Open", null, "", hidden = false))
    }
}
