package dev.creepypasta.earpiece.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.View
import android.widget.RemoteViews
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
 * Quick Settings tile: one tap toggles the earpiece route on or off.
 *
 * On Samsung (One UI) SystemUI additionally offers a detail panel inside the shade, opened by
 * tapping the tile's label — the same gesture that expands Wi-Fi. That panel is not part of public
 * Android: it is driven by hidden `sem*` methods which Samsung's SystemUI looks up **by name** on
 * this TileService subclass, and by a [RemoteViews] layout it renders in its own process. AOSP's
 * SystemUI knows nothing about those hooks and never calls them, so on other devices the tile is a
 * plain on/off switch and nothing here runs. The method names below are therefore part of the
 * contract: renaming one silently disables the panel without any error.
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
            // Tap is a plain toggle: hold the earpiece, or release back to the speaker. The
            // per-route choice lives in the One UI detail panel below.
            if (RouteState.serviceRunning.value) {
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

    // ---------------------------------------------------------------------------------------
    // Samsung (One UI) hidden-API hooks. Found and invoked by name through reflection by
    // Samsung's SystemUI; AOSP never calls them. Signatures must not change.
    // ---------------------------------------------------------------------------------------

    /**
     * Title shown at the top of the One UI detail panel.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semGetDetailViewTitle(): CharSequence = getString(R.string.tile_prefs_title)

    /**
     * Tells Samsung's SystemUI to draw the master switch at the top of the detail panel.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semIsToggleButtonExists(): Boolean = true

    /**
     * State of the switch declared by [semIsToggleButtonExists]: whether a route is currently held.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semIsToggleButtonChecked(): Boolean = RouteState.serviceRunning.value

    /**
     * The switch was flipped in the detail panel: hold the earpiece when checked, release otherwise.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semSetToggleButtonChecked(checked: Boolean) {
        try {
            if (checked) {
                AudioRouteService.apply(this, AudioRoute.EARPIECE)
            } else {
                AudioRouteService.release(this)
            }
        } catch (e: RuntimeException) {
            // Same background-start restriction as onClick: ignore rather than crash SystemUI's
            // binder call into this service.
            Log.w(TAG, "Detail view toggle failed", e)
        }
        refreshTile()
    }

    /**
     * Returning null hides the "Details" button the panel would otherwise show; the panel is the
     * whole UI for this tile, there is no settings screen behind it.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semGetDetailViewSettingButtonName(): CharSequence? = null

    /**
     * Returning null pairs with [semGetDetailViewSettingButtonName]: no settings activity to open.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semGetSettingsIntent(): Intent? = null

    /**
     * Builds the panel content shown inside the shade: one row per route, each row a tap target
     * that starts [AudioRouteService] for that route, with a check mark on the held one.
     *
     * The layout is a RemoteViews, so it is inflated in SystemUI's process and can only use plain
     * Android views. Everything is pushed into it here because the remote layout has no access to
     * this service's state.
     *
     * Samsung (One UI) hidden-API hook, not public Android — AOSP never calls it.
     */
    fun semGetDetailView(): RemoteViews {
        val views = RemoteViews(packageName, R.layout.qs_route_detail)
        try {
            // Snapshot the state once so every row describes the same moment.
            val held = RouteState.serviceRunning.value
            val active = RouteState.active.value
            for (route in AudioRoute.all) {
                views.setTextViewText(labelIdFor(route), getString(labelResFor(route)))
                views.setOnClickPendingIntent(rowIdFor(route), rowPendingIntent(route))
                // A check only makes sense while a route is actually held.
                views.setViewVisibility(
                    checkIdFor(route),
                    if (held && active == route) View.VISIBLE else View.GONE,
                )
            }
        } catch (e: RuntimeException) {
            // Never let a bad state propagate into SystemUI: fall back to the bare layout, which
            // at least inflates and leaves the panel usable via the switch.
            Log.w(TAG, "Unable to build detail view", e)
            return RemoteViews(packageName, R.layout.qs_route_detail)
        }
        return views
    }

    /**
     * Best-effort refresh of an open detail panel after the tile changes.
     *
     * Samsung's SystemUI exposes `semUpdateDetailView()` on the TileService it is already hosting,
     * and calling it is the documented way to make it re-read [semGetDetailView]. The method does
     * not exist on AOSP — or on Samsung builds that dropped the hook — so the lookup throws and is
     * deliberately ignored: there is nothing to refresh there.
     */
    private fun notifyDetailViewChanged() {
        try {
            javaClass.getMethod("semUpdateDetailView").invoke(this)
        } catch (e: ReflectiveOperationException) {
            // AOSP: no such method, nothing to refresh.
        }
    }

    /** PendingIntent that starts the route service for one route, so a row tap holds that route. */
    private fun rowPendingIntent(route: AudioRoute): PendingIntent = PendingIntent.getForegroundService(
        this,
        // Ordinals are stable for the four routes and keep the request codes distinct; 100 leaves
        // the low codes free for anything else the app may request.
        route.ordinal + 100,
        Intent(this, AudioRouteService::class.java)
            .setAction(AudioRouteService.ACTION_APPLY)
            .putExtra(AudioRouteService.EXTRA_ROUTE, route.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Id of the tappable row for [route]; kept here so the wiring lives in one place. */
    private fun rowIdFor(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> R.id.row_earpiece
        AudioRoute.SPEAKER -> R.id.row_speaker
        AudioRoute.BLUETOOTH -> R.id.row_bluetooth
        AudioRoute.WIRED -> R.id.row_wired
    }

    /** Id of the label TextView for [route]; kept here so the wiring lives in one place. */
    private fun labelIdFor(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> R.id.label_earpiece
        AudioRoute.SPEAKER -> R.id.label_speaker
        AudioRoute.BLUETOOTH -> R.id.label_bluetooth
        AudioRoute.WIRED -> R.id.label_wired
    }

    /** Id of the check-mark TextView for [route]; kept here so the wiring lives in one place. */
    private fun checkIdFor(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> R.id.check_earpiece
        AudioRoute.SPEAKER -> R.id.check_speaker
        AudioRoute.BLUETOOTH -> R.id.check_bluetooth
        AudioRoute.WIRED -> R.id.check_wired
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
        // Keep an open One UI detail panel in step with the tile. No-op off Samsung.
        notifyDetailViewChanged()
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
