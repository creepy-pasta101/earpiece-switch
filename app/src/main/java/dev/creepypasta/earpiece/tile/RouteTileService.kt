package dev.creepypasta.earpiece.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.data.RoutePreferences
import dev.creepypasta.earpiece.service.AudioRouteService
import dev.creepypasta.earpiece.state.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap holds the requested route, the next tap releases it.
 *
 * The tile mirrors [RouteState.serviceRunning]; a listener is only alive between
 * onStartListening and onStopListening so no collection outlives the visible tile.
 */
class RouteTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Defensive: the platform can deliver two starts without an intervening stop, which
        // would otherwise leave the previous scope collecting for the life of the process.
        stopListeningInternal()
        refreshTile()

        // Re-create the listener each time; onStopListening always cancels it.
        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = listeningScope
        collectJob = listeningScope.launch {
            RouteState.serviceRunning.collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        stopListeningInternal()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val running = RouteState.serviceRunning.value
        try {
            if (running) {
                AudioRouteService.release(this)
            } else {
                AudioRouteService.apply(this, RoutePreferences(this).requestedRoute)
            }
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException when the app is in the background on
            // API 31+: the tap is simply ignored rather than crashing the tile process.
            Log.w(TAG, "Tile click failed", e)
        }
        refreshTile()
    }

    override fun onDestroy() {
        stopListeningInternal()
        super.onDestroy()
    }

    private fun stopListeningInternal() {
        collectJob?.cancel()
        collectJob = null
        scope?.cancel()
        scope = null
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = RouteState.serviceRunning.value
        try {
            tile.label = getString(R.string.tile_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_earpiece)
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to refresh tile", e)
        }
    }

    private companion object {
        const val TAG = "Earpiece"
    }
}
