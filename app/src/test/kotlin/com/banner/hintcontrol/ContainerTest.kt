package com.banner.hintcontrol

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
            val obs = c.history.observe(profile.id, live.main!!, record = true, minIntervalMs = 0)
            assertEquals(live.main?.signal?.fiveG?.rsrp, obs.sample.nrRsrp)
            val stored = c.history.samplesOnce(profile.id, 0)
            assertTrue("rows=${stored.size} pollerError=${c.status.state.value.error}", stored.isNotEmpty())
            assertTrue(withTimeout(10_000) { c.history.sampleCount(profile.id).first { it > 0 } } > 0)
            assertNotNull(c.summaries.load()?.nrRsrp)

            val wifi = c.session.authed { it.wifiConfig() }
            c.session.authed { it.setWifiConfig(wifi.copy(bandSteering = BandSteeringConfig(false))) }
            assertEquals(false, c.session.authed { it.wifiConfig() }.bandSteering?.isEnabled)

            val clients = c.session.authed { it.clients() }.flatten()
            assertEquals(emptyList<Any>(), c.history.updateDevices(profile.id, clients))
            c.history.setAlias(profile.id, clients.first().key, "My TV")
            assertEquals("My TV", c.history.devices(profile.id).first().first { it.deviceKey == clients.first().key }.alias)

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
        } finally {
            c.scope.cancel()
        }
    }
}
