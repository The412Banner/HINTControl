package com.banner.hintcontrol

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.banner.hintcontrol.data.FirmwareUploader
import com.banner.hintcontrol.data.FirmwarePhase
import com.banner.hintcontrol.data.FirmwareInfo
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.RebootSchedule
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.db.HistoryDatabase
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.model.BandSteeringConfig
import com.banner.hintcontrol.gateway.model.flatten
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real app core (DataStore, Room, session, poller, history) against the demo gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContainerTest {
    @Test
    fun demoGatewayEndToEnd() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(ctx, HistoryDatabase.inMemory(ctx))
        try {
            val profile = GatewayProfile(name = "Demo gateway", host = "demo", kind = GatewayKind.Demo)
            c.profiles.save(profile)
            withTimeout(10_000) { c.profiles.active.first { it?.id == profile.id } }
            withTimeout(10_000) { c.session.access.first { it == Access.LoggedIn } }

            val live = withTimeout(15_000) { c.status.state.first { it.main != null } }
            assertNotNull(live.main?.signal?.fiveG?.rsrp)

            // Automatic reads update silently; the spinner is only for a refresh the user asked for.
            c.status.setFastMode(true)
            val polls = HashSet<Long>()
            var spinner = false
            withTimeout(15_000) {
                c.status.state.first { s ->
                    if (s.refreshing) spinner = true
                    s.updatedAt?.let { polls += it }
                    polls.size >= 3
                }
            }
            c.status.setFastMode(false)
            assertFalse("spinner shown for an automatic refresh", spinner)
            val before = c.status.state.value.updatedAt ?: 0L
            c.status.refresh()
            assertTrue(c.status.state.value.refreshing)
            withTimeout(15_000) { c.status.state.first { !it.refreshing && (it.updatedAt ?: 0L) > before } }
            // The poller records a sample on its own, even though this collector has already stopped.
            assertTrue(withTimeout(10_000) { c.history.sampleCount(profile.id).first { it > 0 } } > 0)
            val obs = c.history.observe(profile.id, live.main!!, record = true, minIntervalMs = 0)
            assertEquals(live.main.signal?.fiveG?.rsrp, obs.sample.nrRsrp)
            val stored = c.history.samplesOnce(profile.id, 0)
            assertTrue("rows=${stored.size} pollerError=${c.status.state.value.error}", stored.isNotEmpty())
            assertTrue(withTimeout(10_000) { c.history.sampleCount(profile.id).first { it > 0 } } > 0)
            assertNotNull(c.summaries.load()?.nrRsrp)
            // Unreachable while the phone isn't on the gateway's network → "away", not "offline".
            c.summaries.markOffline("Home", host = "192.0.2.1")
            assertEquals(true, c.summaries.load()?.away)
            c.summaries.markOffline("Home")
            assertEquals(false, c.summaries.load()?.away)
            assertEquals(false, c.summaries.load()?.online)

            val wifi = c.session.authed { it.wifiConfig() }
            c.session.authed { it.setWifiConfig(wifi.copy(bandSteering = BandSteeringConfig(false))) }
            assertEquals(false, c.session.authed { it.wifiConfig() }.bandSteering?.isEnabled)

            val clients = c.session.authed { it.clients() }.flatten()
            assertEquals(emptyList<Any>(), c.history.updateDevices(profile.id, clients))
            c.history.setAlias(profile.id, clients.first().key, "My TV")
            assertEquals("My TV", c.history.devices(profile.id).first().first { it.deviceKey == clients.first().key }.alias)

            // Firmware tracking: a new version after a known one is logged once.
            val fw1 = live.main.copy(device = live.main.device?.copy(softwareVersion = "1.00.06"))
            assertEquals(null, c.history.observe("fw", fw1, record = false, minIntervalMs = 0).firmwareChanged)
            val fw2 = fw1.copy(device = fw1.device?.copy(softwareVersion = "1.00.07"))
            assertEquals("1.00.06" to "1.00.07", c.history.observe("fw", fw2, record = false, minIntervalMs = 0).firmwareChanged)
            assertEquals(EventType.FirmwareUpdated, c.history.events("fw").first().first().type)
            assertEquals(null, c.history.observe("fw", fw2, record = false, minIntervalMs = 0).firmwareChanged)

            c.history.addEvent(profile.id, EventType.WifiSaved, "test")
            assertEquals(EventType.WifiSaved, c.history.events(profile.id).first().first().type)

            c.settings.update { it.copy(refreshSeconds = 10, rebootSchedule = RebootSchedule(enabled = true, hour = 3, minute = 30)) }
            val s = c.settings.current()
            assertEquals(10, s.refreshSeconds)
            assertEquals(3, s.rebootSchedule.hour)
            assertNotNull(c.scheduler.nextReboot(s.rebootSchedule))

            val second = GatewayProfile(name = "Second", host = "192.0.2.1", kind = GatewayKind.Tmi, createdAt = profile.createdAt + 1)
            c.profiles.save(second, makeActive = false)
            assertEquals(2, c.profiles.state.first { it.profiles.size == 2 }.profiles.size)
            c.profiles.delete(second.id)
            assertEquals(profile.id, c.profiles.state.first { it.profiles.size == 1 }.active?.id)

            // Firmware upload through the real session against the demo gateway: send, restart, new version, events.
            // Kept in this test: a second AppContainer in the same run shares the process-wide DataStore.
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            val uploader = FirmwareUploader(c.scope, c.session, c.history, onObservation = { _, _ -> }, pollMs = 1_000, waitMs = 90_000)
            uploader.start(FirmwareInfo("content://test/fw.bin", "fw.bin", bytes.size.toLong(), "ab".repeat(32))) { java.io.ByteArrayInputStream(bytes) }
            val done = withTimeout(100_000) { uploader.job.first { it != null && !it.active } }!!
            assertEquals(done.message, FirmwarePhase.Done, done.phase)
            assertEquals(bytes.size.toLong(), done.sent)
            assertEquals("1.2403.0.12", done.before)
            assertEquals("1.2403.0.13", done.after)
            assertTrue(done.sawOffline)
            val types = c.history.events(profile.id).first().map { it.type }
            assertTrue(types.toString(), EventType.FirmwareSent in types)
            assertTrue(types.toString(), EventType.FirmwareUpdated in types)
        } finally {
            c.scope.cancel()
        }
    }
}
