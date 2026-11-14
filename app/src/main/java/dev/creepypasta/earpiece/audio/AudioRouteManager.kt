package dev.creepypasta.earpiece.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import dev.creepypasta.earpiece.model.RouteResolver

/**
 * Owns every interaction with [AudioManager].
 *
 * Two platform generations are handled, but both are always driven through the same
 * two mechanisms:
 *  - API 31+ (S): the public device-selection API ([AudioManager.setCommunicationDevice]).
 *  - API 26..30: the legacy audio-mode / speakerphone / SCO flags.
 *
 * The device-selection API alone is not enough: it selects the communication device for
 * *this* app's audio only. Moving media played by *other* apps is the whole purpose of
 * this app, and that still requires the legacy [AudioManager.setMode] +
 * [AudioManager.setSpeakerphoneOn] pair. So every branch applies both, unconditionally
 * — but only when the platform is not already in the state that branch would set.
 *
 * [apply] is idempotent: the service's watchdog re-applies the desired route every few
 * seconds, and each redundant platform call reconfigures the audio path (an audible gap)
 * and briefly bounces media to the loudspeaker. When [isAlreadyApplied] reports the
 * platform already matches, [apply] touches nothing at all.
 *
 * No exception is ever allowed to escape a public method: audio APIs throw
 * [SecurityException] when a permission (BLUETOOTH_CONNECT, MODIFY_AUDIO_SETTINGS)
 * is missing and [RuntimeException] subclasses when the platform refuses a
 * transition (e.g. startBluetoothSco without a SCO-capable device).
 */
class AudioRouteManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val audioManager: AudioManager? =
        try {
            appContext.getSystemService(AudioManager::class.java)
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioManager unavailable", e)
            null
        }

    /** Platform state captured before the first apply(), restored by [release]. */
    private var savedMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var stateCaptured = false

    private var deviceCallback: AudioDeviceCallback? = null

    // ---------------------------------------------------------------- queries

    /**
     * Enumerates the current outputs and reports which route families exist.
     * A missing AudioManager is treated as "speaker only", the safest default.
     */
    fun capabilities(): RouteCapabilities {
        val sdkInt = Build.VERSION.SDK_INT
        val am = audioManager ?: return unknownCapabilities(sdkInt)
        return try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var hasEarpiece = false
            var hasSpeaker = false
            var hasBluetooth = false
            var hasWired = false
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> hasEarpiece = true
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> hasBluetooth = true
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> hasWired = true
                }
            }
            RouteCapabilities(
                hasEarpiece = hasEarpiece,
                hasSpeaker = hasSpeaker,
                hasBluetooth = hasBluetooth,
                hasWired = hasWired,
                sdkInt = sdkInt,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "getDevices denied", e)
            unknownCapabilities(sdkInt)
        } catch (e: RuntimeException) {
            Log.w(TAG, "getDevices failed", e)
            unknownCapabilities(sdkInt)
        }
    }

    /**
     * The routes the current capabilities support, in [AudioRoute.all] order.
     * Backs the UI diagnostics line.
     */
    fun availableRoutes(): List<AudioRoute> {
        val caps = capabilities()
        return AudioRoute.all.filter { caps.supports(it) }
    }

    /**
     * The platform's built-in earpiece output, or null when it cannot be enumerated.
     * Backs the service's device pinning; a missing device is reported as null rather
     * than guessed at.
     */
    fun earpieceDevice(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        return try {
            findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        } catch (e: SecurityException) {
            Log.w(TAG, "earpieceDevice denied", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "earpieceDevice failed", e)
            null
        }
    }

    /**
     * True while the platform is in a telephony call.
     *
     * The route watchdog uses this to stand down: forcing an audio mode or a device while a
     * call is up would fight the telephony stack for the audio path, and the user's call is
     * more important than the route this app is holding.
     */
    fun isCallActive(): Boolean {
        val am = audioManager ?: return false
        return try {
            am.mode == AudioManager.MODE_IN_CALL
        } catch (e: RuntimeException) {
            Log.w(TAG, "isCallActive failed", e)
            false
        }
    }

    /**
     * Resolves [route] against the live capabilities, applies the effective
     * route and returns the *verified* active route read back from the platform.
     *
     * Idempotent: when the platform already matches [isAlreadyApplied], no platform call
     * is issued at all — nothing is reconfigured, nothing is slept on, nothing is retried.
     * The watchdog calls this every couple of seconds, and each redundant setMode /
     * setCommunicationDevice would otherwise cut media out for about a second and bounce it
     * to the loudspeaker.
     *
     * The platform's audio policy can lag a transition by a few hundred milliseconds,
     * so a single read-back is not proof. If the first read-back disagrees with the
     * requested route the branch is applied once more and read back again; the route is
     * only reported when it was actually observed.
     *
     * @return the verified route, or null when the switch was refused/failed.
     */
    fun apply(route: AudioRoute): AudioRoute? {
        val am = audioManager ?: return null

        // An unavailable request is downgraded through the resolver, never forced.
        val effective = RouteResolver.resolve(route, capabilities()).effective

        // Nothing to do: the platform is already in the state this route would set. Return
        // the effective route rather than a read-back, because no read-back was needed and
        // no platform call was made to verify.
        if (isAlreadyApplied(am, effective)) {
            Log.d(TAG, "apply($route) already applied as $effective, no platform change")
            return effective
        }

        captureStateOnce(am)

        if (!attemptApply(am, effective)) return null

        val first = currentRoute()
        if (first == effective) return first

        // The policy has not committed the change yet. Sleeping on this thread is
        // acceptable because apply() is always called from a background dispatcher.
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "apply($route) interrupted before retry", e)
            return null
        }

        if (!attemptApply(am, effective)) return null

        val second = currentRoute()
        if (second == effective) return second

        Log.w(TAG, "apply($route) unverified: wanted $effective, platform reports $second")
        return null
    }

    /**
     * True when the platform is already in the state [route] would set, so [apply] can skip
     * every platform call for it.
     *
     * Deliberately conservative: any exception reports false ("not yet applied"), which
     * falls through to applying the route — the safe direction, since a wrongly skipped
     * apply would leave the route unset while a wrongly issued apply only costs one glitch.
     */
    @Suppress("DEPRECATION")
    private fun isAlreadyApplied(am: AudioManager, route: AudioRoute): Boolean = try {
        when (route) {
            AudioRoute.EARPIECE -> {
                val earpiece = findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                if (earpiece == null) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The mode must be part of this test, not just the device pin. The pin
                    // survives another app reclaiming MODE_NORMAL, and that reclaim is exactly
                    // how the route gets lost — without the mode term the watchdog would detect
                    // the drift, call apply(), and be skipped as "already applied" while the
                    // audio sits on the loudspeaker.
                    am.mode == AudioManager.MODE_IN_COMMUNICATION &&
                        am.communicationDevice?.id == earpiece.id
                } else {
                    am.mode == AudioManager.MODE_IN_COMMUNICATION && !am.isSpeakerphoneOn
                }
            }

            // applyModern()/applyLegacy() clear the selection, drop SCO, raise the
            // speakerphone flag and return to MODE_NORMAL for this route.
            AudioRoute.SPEAKER -> {
                val noSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.communicationDevice == null
                } else {
                    true
                }
                noSelection &&
                    am.isSpeakerphoneOn &&
                    am.mode == AudioManager.MODE_NORMAL
            }

            // Either an SCO link is already up, or the A2DP path is already in charge:
            // nothing selected, no SCO device to move to, and the mode is normal.
            AudioRoute.BLUETOOTH -> {
                val noSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.communicationDevice == null
                } else {
                    true
                }
                val a2dpOnly =
                    findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) == null &&
                        findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) != null
                am.isBluetoothScoOn ||
                    (a2dpOnly && noSelection && am.mode == AudioManager.MODE_NORMAL)
            }

            // A wired headset is auto-routed for media, so "applied" is exactly: the
            // headset is present, nothing is selected and the mode is normal.
            AudioRoute.WIRED -> {
                val hasWired =
                    findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADSET) != null ||
                        findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADPHONES) != null ||
                        findOutputDevice(am, AudioDeviceInfo.TYPE_USB_HEADSET) != null
                val noSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.communicationDevice == null
                } else {
                    true
                }
                hasWired && noSelection && am.mode == AudioManager.MODE_NORMAL
            }
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "isAlreadyApplied($route) denied", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "isAlreadyApplied($route) failed", e)
        false
    }

    /** Runs one platform branch; never lets a platform exception escape. */
    private fun attemptApply(am: AudioManager, route: AudioRoute): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModern(am, route)
        } else {
            applyLegacy(am, route)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "apply($route) denied", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "apply($route) failed", e)
        false
    }

    /** Best read-back of the platform's actual output state. */
    fun currentRoute(): AudioRoute {
        val am = audioManager ?: return AudioRoute.SPEAKER
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mapped = mapCommunicationDevice(am)
                if (mapped != null) return mapped
                // communicationDevice is null -> no explicit selection was made. That is
                // the normal outcome of the legacy-flag path (MODE_IN_COMMUNICATION plus
                // isSpeakerphoneOn=false reroutes other apps' media without ever setting a
                // communication device), so the heuristic below must be able to report
                // EARPIECE from the mode and the flag alone.
            }
            legacyHeuristic(am)
        } catch (e: SecurityException) {
            Log.w(TAG, "currentRoute denied", e)
            AudioRoute.SPEAKER
        } catch (e: RuntimeException) {
            Log.w(TAG, "currentRoute failed", e)
            AudioRoute.SPEAKER
        }
    }

    /**
     * True when [route] is genuinely in effect right now, as opposed to merely requested.
     *
     * [currentRoute] cannot answer this: it reports whatever the communication device is
     * pinned to, and that pin survives an audio-mode takeover. The mode is the load-bearing
     * part, because the mode is what gets stolen - audio-mode priority belongs to the app
     * that set it most recently, so a media player resuming playback can reclaim
     * [AudioManager.MODE_NORMAL] and send media back to the loudspeaker while the
     * communication device still says EARPIECE. A watchdog that polls this can therefore
     * notice the takeover that [currentRoute] hides.
     */
    @Suppress("DEPRECATION")
    fun isRouteHeld(route: AudioRoute): Boolean {
        val am = audioManager ?: return false
        return try {
            when (route) {
                // The mode is the load-bearing half: another app reclaiming MODE_NORMAL is
                // exactly how this route gets lost. The device half is deliberately lenient,
                // because a speakerphone flag left over from an earlier SPEAKER apply is stale
                // here and would otherwise report a working earpiece route as lost.
                AudioRoute.EARPIECE -> {
                    val onEarpiece = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val device = am.communicationDevice
                        device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                            (device == null && !am.isSpeakerphoneOn)
                    } else {
                        !am.isSpeakerphoneOn
                    }
                    am.mode == AudioManager.MODE_IN_COMMUNICATION && onEarpiece
                }

                // Nobody is holding a communication mode: that is what "speaker" means.
                AudioRoute.SPEAKER -> am.mode != AudioManager.MODE_IN_COMMUNICATION

                AudioRoute.BLUETOOTH -> am.isBluetoothScoOn ||
                    findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) != null

                AudioRoute.WIRED ->
                    findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADSET) != null ||
                        findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADPHONES) != null ||
                        findOutputDevice(am, AudioDeviceInfo.TYPE_USB_HEADSET) != null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "isRouteHeld($route) denied", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "isRouteHeld($route) failed", e)
            false
        }
    }

    // ------------------------------------------------------------ observation

    /** Registers a device callback on the main looper; re-registration is a no-op. */
    fun observeDevices(onChange: (RouteCapabilities) -> Unit) {
        if (deviceCallback != null) return
        val am = audioManager ?: return

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onChange(capabilities())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onChange(capabilities())
            }
        }

        try {
            am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            deviceCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "registerAudioDeviceCallback denied", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerAudioDeviceCallback failed", e)
        }
    }

    fun stopObserving() {
        val callback = deviceCallback ?: return
        deviceCallback = null
        val am = audioManager ?: return
        try {
            am.unregisterAudioDeviceCallback(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "unregisterAudioDeviceCallback denied", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "unregisterAudioDeviceCallback failed", e)
        }
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Restores the audio mode and the speakerphone flag that the platform had before the
     * first [apply], then forgets them so a later [apply] captures fresh values.
     *
     * The speakerphone flag is restored on every API level: [applyModern] sets it on 31+
     * too, so leaving it set would keep the device in a state this app did not find it in.
     */
    @Suppress("DEPRECATION")
    fun release() {
        stopObserving()
        val am = audioManager
        if (am != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                }
                savedMode?.let { am.mode = it }
                // isSpeakerphoneOn is deprecated but still the flag that moves other apps'
                // media, so it has to be put back exactly as it was found.
                savedSpeakerphoneOn?.let { am.isSpeakerphoneOn = it }
            } catch (e: SecurityException) {
                Log.w(TAG, "release denied", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "release failed", e)
            }
        }
        savedMode = null
        savedSpeakerphoneOn = null
        stateCaptured = false
    }

    // ----------------------------------------------------------- API 31+ path

    /**
     * API 31+ branches use the modern device-selection API *and* the legacy flags, in a
     * deliberate order per route.
     *
     * EARPIECE is the exception to "apply both": the device selection runs first and the
     * legacy speakerphone flag is only a fallback. Forcing `isSpeakerphoneOn = false`
     * alongside a successful device selection pushes media onto the voice-call path on
     * some devices, which is silent — measured on hardware, so the order matters here.
     * The other branches set their flag unconditionally.
     *
     * isSpeakerphoneOn / isBluetoothScoOn / startBluetoothSco are deprecated on 31+ but
     * still required: on their own the device selection only affects this app's audio,
     * which is the bug this class exists to fix.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("DEPRECATION")
    private fun applyModern(am: AudioManager, route: AudioRoute): Boolean = when (route) {
        AudioRoute.EARPIECE -> {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val earpiece = findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            if (earpiece == null) {
                // No earpiece to route to. `when` is this function's body, so this has to be
                // a branch value rather than an early return.
                false
            } else {
                val selected = am.setCommunicationDevice(earpiece)
                // Legacy fallback ONLY: forcing this on devices where the device selection works
                // routes media into the voice-call path, which can be silent.
                if (!selected) am.isSpeakerphoneOn = false
                true
            }
        }

        AudioRoute.SPEAKER -> {
            am.clearCommunicationDevice()
            // SCO is sticky: if we came from the Bluetooth route and leave it running, the
            // platform keeps routing to the headset and the read-back below reports BLUETOOTH.
            stopSco(am)
            am.isSpeakerphoneOn = true
            am.mode = AudioManager.MODE_NORMAL
            true
        }

        AudioRoute.BLUETOOTH -> {
            val sco = findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            val anyBluetooth = sco != null ||
                findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) != null
            if (!anyBluetooth) {
                // Nothing to route to. `when` is the body of this function, so this has to
                // be a branch value rather than an early return.
                false
            } else {
                am.isSpeakerphoneOn = false
                if (sco != null && am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    am.setCommunicationDevice(sco)
                    am.isBluetoothScoOn = true
                    am.startBluetoothSco()
                } else {
                    // A2DP is routed automatically; drop any explicit selection.
                    am.clearCommunicationDevice()
                    am.mode = AudioManager.MODE_NORMAL
                }
                true
            }
        }

        AudioRoute.WIRED -> {
            // A wired headset is auto-routed for media; just drop the selection.
            am.clearCommunicationDevice()
            stopSco(am)
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
            true
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun mapCommunicationDevice(am: AudioManager): AudioRoute? {
        val device = am.communicationDevice ?: return null
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
            else -> null
        }
    }

    // -------------------------------------------------------- API 26..30 path

    /**
     * API 26..30 branches use only the legacy flags, but mirror the modern order so the
     * two paths behave identically.
     *
     * isSpeakerphoneOn / isBluetoothScoOn / startBluetoothSco are deprecated but are the
     * only routing mechanism that exists below API 31.
     */
    @Suppress("DEPRECATION")
    private fun applyLegacy(am: AudioManager, route: AudioRoute): Boolean = when (route) {
        AudioRoute.EARPIECE -> {
            // Leave SCO on and the headset keeps the audio: the mode change alone does not
            // reclaim it, and the read-back would then report BLUETOOTH instead of EARPIECE.
            stopSco(am)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            true
        }

        AudioRoute.SPEAKER -> {
            am.isSpeakerphoneOn = true
            am.mode = AudioManager.MODE_NORMAL
            true
        }

        AudioRoute.BLUETOOTH -> {
            // On 26..30 the legacy BLUETOOTH permission is granted at install, so no
            // runtime BLUETOOTH_CONNECT check is needed here.
            val anyBluetooth = findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) != null ||
                findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) != null
            if (!anyBluetooth) {
                false
            } else {
                am.isBluetoothScoOn = true
                am.startBluetoothSco()
                true
            }
        }

        AudioRoute.WIRED -> {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
            true
        }
    }

    // ------------------------------------------------------------------ utils

    private fun captureStateOnce(am: AudioManager) {
        if (stateCaptured) return
        stateCaptured = true
        try {
            savedMode = am.mode
            // Captured on every API level: applyModern() sets the flag on 31+ as well, so
            // release() must be able to put it back there too.
            savedSpeakerphoneOn = am.isSpeakerphoneOn
        } catch (e: SecurityException) {
            Log.w(TAG, "captureState denied", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "captureState failed", e)
        }
    }

    /**
     * Reads the output state back from the platform, in the order the platform itself
     * prioritises outputs: SCO, then wired, then the communication device (earpiece or
     * speaker), then A2DP media, then the speaker.
     *
     * The communication-device check has to come before the A2DP check: with SCO off and
     * the mode in communication, a connected A2DP sink does not take the audio, so
     * reporting BLUETOOTH there would be a false negative for a working earpiece route.
     */
    @Suppress("DEPRECATION")
    private fun legacyHeuristic(am: AudioManager): AudioRoute {
        if (am.isBluetoothScoOn) return AudioRoute.BLUETOOTH
        if (findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADSET) != null ||
            findOutputDevice(am, AudioDeviceInfo.TYPE_WIRED_HEADPHONES) != null ||
            findOutputDevice(am, AudioDeviceInfo.TYPE_USB_HEADSET) != null
        ) {
            return AudioRoute.WIRED
        }
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION && !am.isSpeakerphoneOn) {
            return AudioRoute.EARPIECE
        }
        if (findOutputDevice(am, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) != null) {
            return AudioRoute.BLUETOOTH
        }
        return AudioRoute.SPEAKER
    }

    /**
     * Ends any SCO link. Deprecated on API 31+ but still the only way to drop a legacy SCO
     * connection that a previous route left running.
     */
    @Suppress("DEPRECATION")
    private fun stopSco(am: AudioManager) {
        try {
            if (am.isBluetoothScoOn) {
                am.isBluetoothScoOn = false
            }
            am.stopBluetoothSco()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopSco failed", e)
        }
    }

    private fun findOutputDevice(am: AudioManager, type: Int): AudioDeviceInfo? {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == type) return device
        }
        return null
    }

    private fun unknownCapabilities(sdkInt: Int): RouteCapabilities =
        RouteCapabilities(
            hasEarpiece = false,
            hasSpeaker = true,
            hasBluetooth = false,
            hasWired = false,
            sdkInt = sdkInt,
        )

    private companion object {
        const val TAG = "Earpiece"

        /** Grace period given to the audio policy before the single re-apply. */
        const val RETRY_DELAY_MS = 250L
    }
}
