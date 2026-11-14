package dev.creepypasta.earpiece.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.service.AudioRouteService
import dev.creepypasta.earpiece.state.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap holds [AudioRoute.EARPIECE], the next tap releases it.
 *
 * The tap is deliberately independent of the persisted preference: the tile is a shortcut between
 * the two outputs people switch between most (earpiece and speaker), so it always does the same
 * thing instead of replaying whatever was last requested elsewhere in the app.
 *
 * The tile mirrors [RouteState.serviceRunning] and [RouteState.active] — the subtitle names the
 * route actually held. A listener is only alive between onStartListening and onStopListening so no
 * collection outlives the visible tile.
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

        // Re-create the listener each time; onStopListening always cancels it. Both flows are
        // collected so the subtitle follows the held route, not just whether one is held.
        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = listeningScope
        collectJob = listeningScope.launch {
            combine(
                RouteState.serviceRunning,
                RouteState.active,
            ) { _, _ ->
                // Neither value is read here: any emission can change what the tile shows.
                Unit
            }.collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        stopListeningInternal()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        try {
            if (RouteState.serviceRunning.value) {
                // Returns the device to the speaker.
                AudioRouteService.release(this)
            } else {
                AudioRouteService.apply(this, AudioRoute.EARPIECE)
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
        // The text shown while a route is held is the route itself; otherwise say nothing is held.
        val subtitle = if (running) {
            getString(labelResFor(RouteState.active.value))
        } else {
            getString(R.string.tile_state_off)
        }
        try {
            tile.label = getString(R.string.tile_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_earpiece)
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // Tile.setSubtitle() only exists from API 29; below that the label has to carry the
            // tile on its own.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(subtitle)
            }
            // Same text for accessibility, so a screen reader announces the route as well.
            tile.contentDescription = subtitle
            tile.updateTile()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to refresh tile", e)
        }
    }

    /** Mirrors the mapping in RouteNotifications so the tile shows the same words as the notification. */
    private fun labelResFor(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> R.string.route_earpiece
        AudioRoute.SPEAKER -> R.string.route_speaker
        AudioRoute.BLUETOOTH -> R.string.route_bluetooth
        AudioRoute.WIRED -> R.string.route_wired
    }

    private companion object {
        const val TAG = "Earpiece"
    }
}
