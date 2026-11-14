package dev.creepypasta.earpiece.state

import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why an apply attempt did not end on a verified route. */
enum class RouteFailure { NONE, NOT_AVAILABLE, PERMISSION, PLATFORM, SERVICE }

/**
 * The result of one apply attempt, published by the service for the UI to surface.
 *
 * [failure] is NONE exactly when [applied] is non-null, so the UI can decide on one field alone.
 */
data class RouteOutcome(
    val id: Long,                 // System.nanoTime() at creation; the UI keys its snackbar on it
    val requested: AudioRoute,
    val applied: AudioRoute?,     // the VERIFIED route, or null when the switch failed
    val failure: RouteFailure,
)

/**
 * Process-wide, single-source-of-truth state shared by the activity, the foreground service, the
 * quick-settings tile and the ViewModel.
 *
 * The service writes through the `publish*` functions; everything else observes the read-only
 * flows. A `MutableStateFlow` is safe to write from any thread and always holds a value, so
 * collectors never miss the current state, and the four flows start at the contract defaults so the
 * UI has something sane to render before the platform has been queried.
 *
 * [outcome] is the exception to "always holds a value": it starts at null, holds the last
 * unconsumed apply result, and goes back to null once the UI has consumed it.
 */
object RouteState {

    private val requestedFlow = MutableStateFlow(AudioRoute.SPEAKER)
    private val activeFlow = MutableStateFlow(AudioRoute.SPEAKER)
    private val serviceRunningFlow = MutableStateFlow(false)
    private val capabilitiesFlow = MutableStateFlow(RouteCapabilities.UNKNOWN)
    private val outcomeFlow = MutableStateFlow<RouteOutcome?>(null)

    /** The route the user last asked for. */
    val requested: StateFlow<AudioRoute> = requestedFlow.asStateFlow()

    /** The route the platform is verified to be on. */
    val active: StateFlow<AudioRoute> = activeFlow.asStateFlow()

    /** True while the foreground service is holding a route. */
    val serviceRunning: StateFlow<Boolean> = serviceRunningFlow.asStateFlow()

    /** Last known device capabilities. */
    val capabilities: StateFlow<RouteCapabilities> = capabilitiesFlow.asStateFlow()

    /** The last apply result that has not been shown to the user yet, or null. */
    val outcome: StateFlow<RouteOutcome?> = outcomeFlow.asStateFlow()

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

    fun publishOutcome(outcome: RouteOutcome) {
        outcomeFlow.value = outcome
    }

    /** Clears the pending outcome once the UI has shown it. */
    fun consumeOutcome() {
        outcomeFlow.value = null
    }
}
