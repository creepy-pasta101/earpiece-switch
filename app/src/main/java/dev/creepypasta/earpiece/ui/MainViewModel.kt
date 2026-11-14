package dev.creepypasta.earpiece.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.audio.AudioRouteManager
import dev.creepypasta.earpiece.audio.TestTonePlayer
import dev.creepypasta.earpiece.data.RoutePreferences
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import dev.creepypasta.earpiece.model.RouteResolver
import dev.creepypasta.earpiece.service.AudioRouteService
import dev.creepypasta.earpiece.state.RouteFailure
import dev.creepypasta.earpiece.state.RouteOutcome
import dev.creepypasta.earpiece.state.RouteState
import dev.creepypasta.earpiece.ui.components.labelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Immutable snapshot the UI renders. */
data class MainUiState(
    val requested: AudioRoute,
    val active: AudioRoute,
    val capabilities: RouteCapabilities,
    val serviceRunning: Boolean,
    val tonePlaying: Boolean,
    val restoreOnBoot: Boolean,
    val message: UiMessage?,
    val outcome: RouteOutcome?,
)

/**
 * One-shot user message. [id] exists so the UI can tell a repeated message with
 * the same text apart from the previous one; [textRes] and [args] are resolved
 * with `stringResource` at the call site.
 */
data class UiMessage(
    val id: Long,
    val textRes: Int,
    val args: List<Any> = emptyList(),
)

/**
 * Holds the route manager, the persisted preferences and the test tone player,
 * and folds the shared [RouteState] flows into a single [MainUiState].
 *
 * The activity only ever talks to this class; nothing in the UI touches the
 * platform audio stack directly.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = AudioRouteManager(application)
    private val prefs = RoutePreferences(application)
    private val tonePlayer = TestTonePlayer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tonePlaying = MutableStateFlow(false)
    private val restoreOnBoot = MutableStateFlow(prefs.restoreOnBoot)
    private val message = MutableStateFlow<UiMessage?>(null)

    /**
     * The five shared route flows, folded once so the outer combine stays small.
     * The reported outcome belongs here rather than in the outer combine: it is
     * part of the same shared state and folding it keeps the outer combine at
     * four sources, which is all the typed `combine` overloads allow.
     */
    private val routeSnapshot = combine(
        RouteState.requested,
        RouteState.active,
        RouteState.capabilities,
        RouteState.serviceRunning,
        RouteState.outcome,
    ) { requested, active, capabilities, serviceRunning, outcome ->
        RouteSnapshot(requested, active, capabilities, serviceRunning, outcome)
    }

    val state: StateFlow<MainUiState> = combine(
        routeSnapshot,
        tonePlaying,
        restoreOnBoot,
        message,
    ) { routes, tone, restore, msg ->
        MainUiState(
            requested = routes.requested,
            active = routes.active,
            capabilities = routes.capabilities,
            serviceRunning = routes.serviceRunning,
            tonePlaying = tone,
            restoreOnBoot = restore,
            message = msg,
            outcome = routes.outcome,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MainUiState(
            requested = RouteState.requested.value,
            active = RouteState.active.value,
            capabilities = RouteState.capabilities.value,
            serviceRunning = RouteState.serviceRunning.value,
            tonePlaying = false,
            restoreOnBoot = prefs.restoreOnBoot,
            message = null,
            outcome = RouteState.outcome.value,
        ),
    )

    init {
        // Seed the shared state so the first frame shows the persisted choice
        // instead of the SPEAKER default.
        RouteState.publishRequested(prefs.requestedRoute)
        publishCapabilities()
        // Live device changes (headphones plugged in, headset paired) refresh the
        // availability of every card while the screen is open.
        try {
            manager.observeDevices { capabilities -> RouteState.publishCapabilities(capabilities) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to observe audio devices", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not observe audio devices", e)
        }
    }

    /**
     * Requests [route]: persists it, mirrors it into the shared state, stops any
     * running test tone, and hands the actual switch to the foreground service,
     * which is the component that owns the held route.
     *
     * The request is resolved against the current capabilities *first*. A route
     * this device cannot honour is reported back as an explicit
     * [RouteFailure.NOT_AVAILABLE] outcome and the service is never started, so
     * a tap on an unavailable card explains itself instead of doing nothing.
     */
    fun select(route: AudioRoute) {
        val resolution = RouteResolver.resolve(route, RouteState.capabilities.value)
        if (resolution.downgraded) {
            RouteState.publishOutcome(
                RouteOutcome(
                    id = System.nanoTime(),
                    requested = route,
                    applied = null,
                    failure = RouteFailure.NOT_AVAILABLE,
                ),
            )
            return
        }

        prefs.requestedRoute = route
        RouteState.publishRequested(route)
        stopToneIfPlaying()

        // A Bluetooth request that the platform cannot honour is almost always a
        // missing adapter or a denied permission, which deserves its own message.
        if (route == AudioRoute.BLUETOOTH && resolution.effective != AudioRoute.BLUETOOTH) {
            emit(R.string.msg_bluetooth_denied)
        }

        try {
            AudioRouteService.apply(getApplication<Application>(), route)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to start the route service", e)
            emit(R.string.msg_route_failed, routeName(route))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the route service", e)
            emit(R.string.msg_route_failed, routeName(route))
        }
    }

    /** Holds the requested route, or releases it if the service is already running. */
    fun toggleService() {
        val context = getApplication<Application>()
        try {
            if (RouteState.serviceRunning.value) {
                AudioRouteService.release(context)
            } else {
                AudioRouteService.apply(context, prefs.requestedRoute)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to toggle the route service", e)
            emit(R.string.msg_route_failed, routeName(prefs.requestedRoute))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not toggle the route service", e)
            emit(R.string.msg_route_failed, routeName(prefs.requestedRoute))
        }
    }

    /** Starts or stops the synthesised test tone through the current route. */
    fun toggleTestTone() {
        if (tonePlaying.value) {
            stopToneIfPlaying()
            return
        }
        val started = try {
            tonePlayer.start()
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to play the test tone", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not play the test tone", e)
            false
        }
        tonePlaying.value = started
        if (!started) {
            emit(R.string.msg_route_failed, routeName(RouteState.active.value))
        }
    }

    fun setRestoreOnBoot(enabled: Boolean) {
        prefs.restoreOnBoot = enabled
        restoreOnBoot.value = enabled
    }

    /** Called by the UI once the snackbar for the current message has been shown. */
    fun consumeMessage() {
        message.value = null
    }

    /** Called by the UI once the snackbar for the current route outcome has been shown. */
    fun consumeOutcome() {
        RouteState.consumeOutcome()
    }

    override fun onCleared() {
        tonePlaying.value = false
        try {
            // Only unregister here. AudioRouteManager.release() restores the audio mode that
            // was saved before the first apply, and it acts on the process-wide AudioManager —
            // calling it from the view model would tear down the route the foreground service
            // is still holding. The service owns that call, in its own onDestroy().
            manager.stopObserving()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop observing audio devices", e)
        }
        try {
            tonePlayer.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release the tone player cleanly", e)
        }
        scope.cancel()
        super.onCleared()
    }

    private fun publishCapabilities() {
        try {
            RouteState.publishCapabilities(manager.capabilities())
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to read audio capabilities", e)
            RouteState.publishCapabilities(RouteCapabilities.UNKNOWN)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read audio capabilities", e)
            RouteState.publishCapabilities(RouteCapabilities.UNKNOWN)
        }
    }

    private fun stopToneIfPlaying() {
        if (!tonePlaying.value) return
        try {
            tonePlayer.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop the test tone cleanly", e)
        }
        tonePlaying.value = false
    }

    private fun routeName(route: AudioRoute): String = getApplication<Application>().getString(route.labelRes())

    private fun emit(textRes: Int, vararg args: Any) {
        // nanoTime is monotonic and never repeats within a run, so the UI can key
        // its snackbar effect on it without risking a dropped repeat message.
        message.value = UiMessage(id = System.nanoTime(), textRes = textRes, args = args.toList())
    }

    /** The shared flows, folded into one value for the outer combine. */
    private data class RouteSnapshot(
        val requested: AudioRoute,
        val active: AudioRoute,
        val capabilities: RouteCapabilities,
        val serviceRunning: Boolean,
        val outcome: RouteOutcome?,
    )

    private companion object {
        const val TAG = "Earpiece"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
