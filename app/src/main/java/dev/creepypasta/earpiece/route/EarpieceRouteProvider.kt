package dev.creepypasta.earpiece.route

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.service.AudioRouteService

/**
 * Publishes a single dummy media route so the earpiece can be chosen from the system's media
 * output switcher.
 *
 * This route exists only as a selector: it never carries, plays or receives any audio. A
 * non-system route provider cannot publish a system route type, so the platform lists it as a
 * generic output rather than as a built-in earpiece. Selecting it is used purely as a trigger —
 * we switch the device's audio output to the earpiece and then reject the routing request, so
 * the media app keeps playing locally instead of handing its playback to us.
 *
 * Available from API 30 (Android 11), when [MediaRoute2ProviderService] was introduced.
 */
@RequiresApi(Build.VERSION_CODES.R)
class EarpieceRouteProvider : MediaRoute2ProviderService() {

    /**
     * Abstract in API 30. The system calls this with the discovery preferences of the media app
     * that is looking for outputs; we ignore the contents and simply (re)publish our route so it
     * shows up in the switcher.
     */
    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        publishRoute()
    }

    /** Advertises the dummy route to the system router. */
    private fun publishRoute() {
        try {
            val route = MediaRoute2Info.Builder(ROUTE_ID, getString(R.string.route_provider_name))
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                .setDescription(getString(R.string.route_provider_description))
                .build()
            notifyRoutes(listOf(route))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not publish the earpiece route", e)
        }
    }

    /** Abstract in API 30. The media app asked for a session on our route. */
    override fun onCreateSession(
        requestId: Long,
        routeId: String,
        sessionId: String?,
        sessionHints: Bundle?,
    ) {
        triggerAndReject(requestId, routeId)
    }

    /**
     * Deprecated in API 31 but still abstract in API 30, so it must be overridden to compile
     * against minSdk 26 / targetSdk 36.
     */
    @Suppress("DEPRECATION")
    override fun onSelectRoute(requestId: Long, routeId: String) {
        triggerAndReject(requestId, routeId)
    }

    /** Abstract in API 30. The media app asked to move an existing session onto our route. */
    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        triggerAndReject(requestId, routeId)
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        // We never create a session, so there is nothing to release.
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        // The route carries no audio, so it has no volume of its own.
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        // We never create a session, so there is no session volume to set.
    }

    /**
     * Applies the earpiece route and then always fails the request.
     *
     * Rejecting is deliberate: accepting would hand the media app's playback over to this
     * provider, and we have no way to play it. Failing the request tells the system and the media
     * app that the transfer did not happen, so playback simply continues where it was — while the
     * device output has already been switched to the earpiece.
     */
    private fun triggerAndReject(requestId: Long, routeId: String) {
        if (routeId == ROUTE_ID) {
            try {
                AudioRouteService.apply(this, AudioRoute.EARPIECE)
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to switch the audio route", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not switch the audio route", e)
            }
        }

        // Always reject — see the KDoc above.
        try {
            // Qualified: Java static constants are not inherited into a Kotlin subclass scope.
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_REJECTED)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not reject the route request", e)
        }
    }

    private companion object {
        const val TAG = "Earpiece"
        const val ROUTE_ID = "dev.creepypasta.earpiece.route.EARPIECE"
    }
}
