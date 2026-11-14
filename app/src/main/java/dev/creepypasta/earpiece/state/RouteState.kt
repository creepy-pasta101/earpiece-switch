package dev.creepypasta.earpiece.state

import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, single-source-of-truth state shared by the activity, the foreground service, the
 * quick-settings tile and the ViewModel.
 *
 * The service writes through the `publish*` functions; everything else observes the read-only
 * flows. A `MutableStateFlow` is safe to write from any thread and always holds a value, so
 * collectors never miss the current state, and the four flows start at the contract defaults so the
 * UI has something sane to render before the platform has been queried.
 */
object RouteState {

    private val requestedFlow = MutableStateFlow(AudioRoute.SPEAKER)
    private val activeFlow = MutableStateFlow(AudioRoute.SPEAKER)
    private val serviceRunningFlow = MutableStateFlow(false)
    private val capabilitiesFlow = MutableStateFlow(RouteCapabilities.UNKNOWN)

    /** The route the user last asked for. */
    val requested: StateFlow<AudioRoute> = requestedFlow.asStateFlow()

    /** The route the platform is verified to be on. */
    val active: StateFlow<AudioRoute> = activeFlow.asStateFlow()

    /** True while the foreground service is holding a route. */
    val serviceRunning: StateFlow<Boolean> = serviceRunningFlow.asStateFlow()

    /** Last known device capabilities. */
    val capabilities: StateFlow<RouteCapabilities> = capabilitiesFlow.asStateFlow()

    fun publishRequested(route: AudioRoute) {
        requestedFlow.value = route
    }

    fun publishActive(route: AudioRoute) {
        activeFlow.value = route
    }

    fun publishServiceRunning(running: Boolean) {
        serviceRunningFlow.value = running
    }

    fun publishCapabilities(caps: RouteCapabilities) {
        capabilitiesFlow.value = caps
    }
}
