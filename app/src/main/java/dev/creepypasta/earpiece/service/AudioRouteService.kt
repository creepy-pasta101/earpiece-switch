package dev.creepypasta.earpiece.service

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.creepypasta.earpiece.audio.AudioRouteManager
import dev.creepypasta.earpiece.audio.SilentKeepAlive
import dev.creepypasta.earpiece.data.RoutePreferences
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import dev.creepypasta.earpiece.model.RouteResolver
import dev.creepypasta.earpiece.state.RouteFailure
import dev.creepypasta.earpiece.state.RouteOutcome
import dev.creepypasta.earpiece.state.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the chosen audio output route while running as a media-playback foreground service.
 *
 * All platform audio work happens on [audioScope] (Dispatchers.Default) because applying a route
 * touches AudioManager/AudioTrack and must never block the main thread. Results are published to
 * [RouteState] so the UI and the Quick Settings tile stay in sync: every apply attempt publishes a
 * [RouteOutcome], success or failure, and a watchdog re-asserts the route while we are foreground.
 */
class AudioRouteService : Service() {

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var manager: AudioRouteManager
    private lateinit var keepAlive: SilentKeepAlive
    private lateinit var prefs: RoutePreferences
    private lateinit var notifications: RouteNotifications

    private var foregroundStarted = false
    private var noisyReceiverRegistered = false

    /** The service-start failure is reported once per instance, not on every onStartCommand. */
    private var serviceFailurePublished = false

    /** Only one watchdog loop per service instance. */
    private var watchdogStarted = false

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
        // Created here so onDestroy always has an instance to stop, even if no route was applied.
        keepAlive = SilentKeepAlive(this)
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
                // The service is going away: hand the communication path back before stopping, so
                // the silent stream is not left holding it until onDestroy runs.
                keepAlive.stop()
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

        // Drop the silent stream before releasing the manager: the stream holds the very audio
        // path the manager is about to reset.
        keepAlive.stop()
        manager.stopObserving()
        manager.release()
        // Cancels the watchdog loop along with everything else this service started.
        audioScope.cancel()
        super.onDestroy()
    }

    /**
     * Posts the ongoing notification. POST_NOTIFICATIONS may be denied on API 33+; the call still
     * succeeds (the notification is simply not shown) and the service stays foreground.
     *
     * The notification is built inside the try as well: a throwing builder must degrade to the same
     * handled failure path instead of escaping onStartCommand. If the typed start is rejected (some
     * OEM builds do this even when the manifest declares the type), retry once with type 0.
     */
    private fun startForegroundSafely(route: AudioRoute) {
        try {
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
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException, InvalidForegroundServiceTypeException
                // and friends: never crash the process over it. Retrying with type 0 is only
                // worth attempting below API 34 — from 34 up the platform rejects "type none"
                // outright ("Starting FGS with type none targetSDK=34 has been prohibited"), so
                // the retry would fail for a second, different reason and hide the first.
                if (Build.VERSION.SDK_INT >= 34) {
                    Log.w(TAG, "startForeground rejected", e)
                    publishServiceFailureOnce(route)
                    return
                }
                Log.w(TAG, "Typed startForeground rejected; retrying with type 0", e)
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
                } catch (retry: RuntimeException) {
                    Log.w(TAG, "startForeground rejected on retry with type 0", retry)
                    publishServiceFailureOnce(route)
                    return
                }
            }
            if (!foregroundStarted) {
                foregroundStarted = true
                RouteState.publishServiceRunning(true)
                startWatchdog()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground failed", e)
            publishServiceFailureOnce(route)
        }
    }

    /**
     * Tells the UI once per service instance that the service could not go foreground, which is
     * the one failure the user cannot otherwise see: no notification, and no route applied.
     */
    private fun publishServiceFailureOnce(route: AudioRoute) {
        if (serviceFailurePublished) return
        serviceFailurePublished = true
        RouteState.publishOutcome(
            RouteOutcome(
                id = System.nanoTime(),
                requested = route,
                applied = null,
                failure = RouteFailure.SERVICE,
            ),
        )
    }

    /**
     * Resolves and applies [route] off the main thread, publishing the outcome of the attempt.
     *
     * On failure (apply returned null, e.g. denied Bluetooth permission) we deliberately leave
     * [RouteState.active] untouched — the previous route is still the honest one — and report why
     * the attempt failed instead.
     */
    private fun applyRoute(route: AudioRoute) {
        audioScope.launch {
            var caps: RouteCapabilities? = null
            val resolved = try {
                val live = manager.capabilities()
                caps = live
                RouteResolver.resolve(route, live)
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
                RouteState.publishOutcome(
                    RouteOutcome(
                        id = System.nanoTime(),
                        requested = route,
                        applied = applied,
                        failure = RouteFailure.NONE,
                    ),
                )
                RouteState.publishActive(applied)
                // The resolver's requested value is what the user actually asked for; publishing it
                // lets the UI show the downgrade note when effective != requested.
                if (resolved != null) {
                    RouteState.publishRequested(resolved.requested)
                }
                Log.i(TAG, "Route applied: requested=$route active=$applied")
                // Match the keep-alive to the route that was actually applied, not the requested
                // one: a downgrade to the speaker must not leave a stream holding the earpiece.
                syncKeepAlive(applied)
            } else {
                val failure = failureReason(route, caps)
                RouteState.publishOutcome(
                    RouteOutcome(
                        id = System.nanoTime(),
                        requested = route,
                        applied = null,
                        failure = failure,
                    ),
                )
                if (resolved != null) {
                    RouteState.publishRequested(resolved.requested)
                }
                Log.w(TAG, "Route apply returned null for $route ($failure); keeping previous active route")
            }
        }
    }

    /**
     * Why [route] could not be applied, in the order the UI cares about: unsupported hardware,
     * then a missing runtime permission, then everything else the platform did.
     */
    private fun failureReason(route: AudioRoute, caps: RouteCapabilities?): RouteFailure = when {
        caps != null && !caps.supports(route) -> RouteFailure.NOT_AVAILABLE
        route == AudioRoute.BLUETOOTH && !hasBluetoothConnectPermission() -> RouteFailure.PERMISSION
        else -> RouteFailure.PLATFORM
    }

    /**
     * BLUETOOTH_CONNECT only exists from API 31; on older platforms Bluetooth routing needs no
     * runtime permission, so the answer there is "we hold it".
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth permission check failed", e)
            false
        }
    }

    /**
     * Starts the drift watchdog, once per instance, on [audioScope] so onDestroy's cancel() stops it.
     * It never runs before the service is foreground: a route we cannot hold yet is not a drift.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        audioScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!foregroundStarted) continue
                reassertIfDrifted()
            }
        }
    }

    /**
     * Silently re-applies the requested route when the platform has moved output elsewhere (another
     * app grabbing focus, a device change, an OEM audio policy quirk). No outcome is published here:
     * the user did not ask for this, so a snackbar per drift would be noise.
     *
     * The check is mode-aware: [AudioRouteManager.currentRoute] keeps reporting the pinned route
     * even after another app reclaims MODE_NORMAL (which is what sends media back to the speaker),
     * so [AudioRouteManager.isRouteHeld] is what actually tells us whether the route is in effect.
     */
    private fun reassertIfDrifted() {
        // Never fight an active phone call for the audio path. The user's call wins over
        // whatever route this app is holding; the route is re-asserted once the call ends.
        if (manager.isCallActive()) return

        val requested = prefs.requestedRoute

        val expected = try {
            RouteResolver.resolve(requested, manager.capabilities()).effective
        } catch (e: RuntimeException) {
            Log.d(TAG, "Watchdog could not resolve $requested", e)
            requested
        }

        val held = try {
            manager.isRouteHeld(expected)
        } catch (e: RuntimeException) {
            Log.d(TAG, "Watchdog could not read whether $expected is held", e)
            return
        }

        if (held) return

        Log.d(TAG, "Watchdog: $expected is no longer in effect; re-asserting $requested")
        val applied = try {
            manager.apply(requested)
        } catch (e: SecurityException) {
            Log.d(TAG, "Watchdog re-apply($requested) denied", e)
            null
        } catch (e: RuntimeException) {
            Log.d(TAG, "Watchdog re-apply($requested) failed", e)
            null
        }
        // Keep the published active route honest, but stay silent: no outcome, no snackbar.
        if (applied != null) {
            RouteState.publishActive(applied)
            // The re-apply may have rebuilt the path (or lost it), so the keep-alive follows it.
            syncKeepAlive(applied)
        }
    }

    /**
     * Keeps the silent keep-alive stream in step with [applied]: only the earpiece needs the
     * communication path held open, every other route wants it handed back.
     *
     * Called from work already running on [audioScope], because building an AudioTrack is platform
     * audio work that must not touch the main thread. A keep-alive that refuses to start is logged
     * at warn and nothing else: the route itself still works, so no failure outcome is published
     * for it — the user never asked for a stream, and a snackbar here would be noise.
     */
    private fun syncKeepAlive(applied: AudioRoute) {
        if (applied == AudioRoute.EARPIECE) {
            val started = try {
                keepAlive.start(manager.earpieceDevice())
            } catch (e: SecurityException) {
                Log.w(TAG, "keep-alive start denied for $applied", e)
                false
            } catch (e: RuntimeException) {
                Log.w(TAG, "keep-alive start failed for $applied", e)
                false
            }
            if (!started) {
                Log.w(TAG, "Silent keep-alive did not start for $applied; route is still applied")
            }
        } else {
            keepAlive.stop()
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

        /**
         * How often the foreground watchdog checks that the route is still held. With the silent
         * keep-alive stream holding the communication path, the watchdog is a safety net rather
         * than the primary mechanism — the route is not ours to re-assert every couple of seconds
         * any more, and a slower 5 s tick means fewer needless re-applications (each of which
         * reconfigures the path and costs a dropout).
         */
        private const val WATCHDOG_INTERVAL_MS = 5_000L

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
