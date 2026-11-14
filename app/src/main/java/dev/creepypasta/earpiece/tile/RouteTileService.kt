package dev.creepypasta.earpiece.tile

import android.app.AlertDialog
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
 * Quick Settings tile: one tap opens the output picker, where any of the four routes can be held
 * or the current hold released ("Off").
 *
 * The picker is shown with [TileService.showDialog], which renders it in the Quick Settings dialog
 * style and lets SystemUI collapse the panel around it. A third-party tile cannot draw its own
 * content inside the shade and cannot intercept the long-press (the platform always routes that to
 * the QS_TILE_PREFERENCES activity), so the tap is the only supported entry point.
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
            if (isLocked) {
                // A dialog shown while the device is locked is not visible, so unlock first and
                // open the picker from the unlock callback.
                unlockAndRun { showRoutePicker() }
            } else {
                showRoutePicker()
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

    /**
     * Single-choice picker over the four routes plus "Off" as the last entry.
     *
     * Shown through [TileService.showDialog] rather than [AlertDialog.show]: only the former gives
     * the dialog the Quick Settings window type and lets SystemUI collapse the panel correctly.
     */
    private fun showRoutePicker() {
        try {
            // One source of truth for the order, so an index means the same route in both
            // directions and a new route cannot be silently left out of the picker.
            val routes = AudioRoute.all
            val offIndex = routes.size
            val items = routes.map { getString(labelResFor(it)) } + getString(R.string.tile_state_off)

            // Tick the held route, or "Off" when nothing is held.
            val checkedIndex = if (RouteState.serviceRunning.value) {
                val activeIndex = routes.indexOf(RouteState.active.value)
                // A held route the list does not cover falls back to "Off" rather than ticking a
                // row that is not the held one.
                if (activeIndex >= 0) activeIndex else offIndex
            } else {
                offIndex
            }

            // The listener needs a handle on the dialog to dismiss it. Build it first into a
            // non-null local (showDialog requires a non-null Dialog), then publish it to the
            // nullable holder the listener closes over.
            var dialog: AlertDialog? = null
            val built = AlertDialog.Builder(this)
                .setTitle(R.string.tile_prefs_title)
                .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { _, which ->
                    if (which == offIndex) {
                        // Returns the device to the speaker.
                        AudioRouteService.release(this)
                    } else {
                        AudioRouteService.apply(this, routes[which])
                    }
                    dialog?.dismiss()
                    refreshTile()
                }
                .create()
            dialog = built
            showDialog(built)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to show route picker", e)
        }
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
