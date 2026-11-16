package com.banner.hintcontrol.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.data.StatusSummary
import com.banner.hintcontrol.data.toSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile with the current 5G (or LTE) band and readings. Tap to refresh. */
class SignalTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        show(HintApp.container(this).summaries.load())
        refresh()
    }

    override fun onClick() {
        super.onClick()
        refresh()
    }

    private fun refresh() {
        job?.cancel()
        job = scope.launch {
            val c = HintApp.container(this@SignalTileService)
            val profile = c.profiles.active.value ?: return@launch
            runCatching { c.session.status { it.mainData() } }
                .onSuccess { c.summaries.save(it.toSummary(profile.name)) }
                .onFailure { c.summaries.markOffline(profile.name, profile.host.takeUnless { profile.isDemo }) }
            show(c.summaries.load())
        }
    }

    private fun show(summary: StatusSummary?) {
        val tile = qsTile ?: return
        when {
            summary == null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Gateway"
                setSubtitle(tile, "Not set up")
            }
            !summary.online -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = summary.gatewayName
                setSubtitle(tile, if (summary.away) "Not on its network" else "Offline")
            }
            summary.nrRsrp != null -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "5G ${summary.nrBand.orEmpty()}".trim()
                setSubtitle(tile, "${summary.nrRsrp} dBm · ${summary.nrSinr ?: "—"} dB")
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "LTE ${summary.lteBand.orEmpty()}".trim()
                setSubtitle(tile, "${summary.lteRsrp ?: "—"} dBm · ${summary.lteSinr ?: "—"} dB")
            }
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
