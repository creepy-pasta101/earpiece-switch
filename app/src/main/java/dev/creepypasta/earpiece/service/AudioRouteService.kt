package dev.creepypasta.earpiece.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.creepypasta.earpiece.audio.AudioRouteManager
import dev.creepypasta.earpiece.data.RoutePreferences
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import dev.creepypasta.earpiece.model.RouteResolver
import dev.creepypasta.earpiece.state.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the chosen audio output route while running as a media-playback foreground service.
 *
 * All platform audio work happens on [audioScope] (Dispatchers.Default) because applying a route
 * touches AudioManager/AudioTrack and must never block the main thread. Results are published to
 * [RouteState] so the UI and the Quick Settings tile stay in sync.
 */
class AudioRouteService : Service() {

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var manager: AudioRouteManager
    private lateinit var prefs: RoutePreferences
    private lateinit var notifications: RouteNotifications

    private var foregroundStarted = false
    private var noisyReceiverRegistered = false

    /**
     * Headphones were unplugged / a Bluetooth device dropped: the platform has already moved
     * output on its own, so re-assert what the user asked for.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!foregroundStarted) return
            val requested = prefs.requestedRoute
            Log.i(TAG, "Audio becoming noisy, re-asserting $requested")
            applyRoute(requested)
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager = AudioRouteManager(this)
        prefs = RoutePreferences(this)
        notifications = RouteNotifications(this)
        notifications.ensureChannel()

        // Device changes arrive on the main looper; publish them and re-assert the requested route
        // so a freshly connected headset cannot silently steal (or lose) the held route.
        manager.observeDevices { caps: RouteCapabilities ->
            RouteState.publishCapabilities(caps)
            if (foregroundStarted) {
                applyRoute(prefs.requestedRoute)
            }
        }

        try {
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to register noisy receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification must exist before any other work: a foreground service has only a few
        // seconds to post it, and the title reflects the route the user last requested.
        val requestedAtEntry = prefs.requestedRoute
        startForegroundSafely(requestedAtEntry)

        when (intent?.action) {
            ACTION_APPLY -> {
                // Null-safe: a malformed/empty extra falls back to the persisted request.
                val route = AudioRoute.fromKey(intent.getStringExtra(EXTRA_ROUTE)) ?: requestedAtEntry
                prefs.requestedRoute = route
                RouteState.publishRequested(route)
                startForegroundSafely(route)
                applyRoute(route)
            }

            ACTION_CYCLE -> {
                val next = nextSupportedRoute(requestedAtEntry)
                prefs.requestedRoute = next
                RouteState.publishRequested(next)
                startForegroundSafely(next)
                applyRoute(next)
            }

            ACTION_RELEASE -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // Null action means the system restarted us: restore the persisted request.
                applyRoute(requestedAtEntry)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        foregroundStarted = false
        RouteState.publishServiceRunning(false)

        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to unregister noisy receiver", e)
            }
            noisyReceiverRegistered = false
        }

        manager.stopObserving()
        manager.release()
        audioScope.cancel()
        super.onDestroy()
    }

    /**
     * Posts the ongoing notification. POST_NOTIFICATIONS may be denied on API 33+; the call still
     * succeeds (the notification is simply not shown) and the service stays foreground.
     */
    private fun startForegroundSafely(route: AudioRoute) {
        val notification = notifications.build(route)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
            if (!foregroundStarted) {
                foregroundStarted = true
                RouteState.publishServiceRunning(true)
            }
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException and friends: never crash the process over it.
            Log.w(TAG, "startForeground rejected", e)
        }
    }

    /**
     * Resolves and applies [route] off the main thread.
     *
     * On failure (apply returned null, e.g. denied Bluetooth permission) we deliberately leave
     * [RouteState.active] untouched — the UI reports the failure from the unchanged value and the
     * previous route is still the honest one.
     */
    private fun applyRoute(route: AudioRoute) {
        audioScope.launch {
            val resolved = try {
                RouteResolver.resolve(route, manager.capabilities())
            } catch (e: RuntimeException) {
                Log.w(TAG, "Capability read failed for $route", e)
                null
            }

            val applied: AudioRoute? = try {
                manager.apply(route)
            } catch (e: SecurityException) {
                Log.w(TAG, "apply($route) denied", e)
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "apply($route) failed", e)
                null
            }

            if (applied != null) {
                RouteState.publishActive(applied)
                // The resolver's requested value is what the user actually asked for; publishing it
                // lets the UI show the downgrade note when effective != requested.
                if (resolved != null) {
                    RouteState.publishRequested(resolved.requested)
                }
                Log.i(TAG, "Route applied: requested=$route active=$applied")
            } else {
                if (resolved != null) {
                    RouteState.publishRequested(resolved.requested)
                }
                Log.w(TAG, "Route apply returned null for $route; keeping previous active route")
            }
        }
    }

    /** Next route in [AudioRoute.all] that the current capabilities support, wrapping around. */
    private fun nextSupportedRoute(current: AudioRoute): AudioRoute {
        val caps: RouteCapabilities = try {
            manager.capabilities()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Capability read failed while cycling", e)
            RouteCapabilities.UNKNOWN
        }

        val available = AudioRoute.all.filter { isAvailable(it, caps) }
        if (available.isEmpty()) return AudioRoute.SPEAKER

        val index = available.indexOf(current)
        return if (index < 0) available.first() else available[(index + 1) % available.size]
    }

    private fun isAvailable(route: AudioRoute, caps: RouteCapabilities): Boolean =
        caps.supports(route) && (route != AudioRoute.EARPIECE || caps.sdkInt >= 26)

    companion object {
        private const val TAG = "Earpiece"

        const val ACTION_APPLY = "dev.creepypasta.earpiece.action.APPLY"
        const val ACTION_RELEASE = "dev.creepypasta.earpiece.action.RELEASE"
        const val ACTION_CYCLE = "dev.creepypasta.earpiece.action.CYCLE"
        const val EXTRA_ROUTE = "dev.creepypasta.earpiece.extra.ROUTE"
        const val NOTIFICATION_ID = 1001

        fun apply(context: Context, route: AudioRoute) {
            val intent = Intent(context, AudioRouteService::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_ROUTE, route.key)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cycle(context: Context) {
            val intent = Intent(context, AudioRouteService::class.java).apply {
                action = ACTION_CYCLE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun release(context: Context) {
            if (!RouteState.serviceRunning.value) {
                // Nothing is holding the route: settle the published state from preferences instead
                // of waking a service just to stop it.
                val prefs = RoutePreferences(context)
                val requested = prefs.requestedRoute
                RouteState.publishRequested(requested)
                RouteState.publishActive(requested)
                RouteState.publishServiceRunning(false)
                return
            }
            val intent = Intent(context, AudioRouteService::class.java).apply {
                action = ACTION_RELEASE
            }
            // A plain startService() throws IllegalStateException when the app is in the
            // background on API 26+, which is exactly the case for a Quick Settings tap. The
            // service posts its notification first and stops immediately, so the foreground
            // start contract is satisfied.
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "release() could not start the service", e)
            }
        }
    }
}
