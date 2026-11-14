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
 * Two platform generations are handled:
 *  - API 31+ (S): public device-selection API ([AudioManager.setCommunicationDevice]).
 *  - API 26..30: the legacy audio-mode / speakerphone / SCO flags.
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
     * Resolves [route] against the live capabilities, applies the effective
     * route and returns the *verified* active route read back from the platform.
     *
     * @return the verified route, or null when the switch was refused/failed.
     */
    fun apply(route: AudioRoute): AudioRoute? {
        val am = audioManager ?: return null

        // An unavailable request is downgraded through the resolver, never forced.
        val effective = RouteResolver.resolve(route, capabilities()).effective

        captureStateOnce(am)

        val applied = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applyModern(am, effective)
            } else {
                applyLegacy(am, effective)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "apply($route) denied", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "apply($route) failed", e)
            false
        }

        if (!applied) return null
        return currentRoute()
    }

    /** Best read-back of the platform's actual output state. */
    fun currentRoute(): AudioRoute {
        val am = audioManager ?: return AudioRoute.SPEAKER
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mapped = mapCommunicationDevice(am)
                if (mapped != null) return mapped
                // communicationDevice is null -> no explicit selection; fall through.
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
     * Restores the audio mode (and, below API 31, the speakerphone flag) that the
     * platform had before the first [apply], then forgets it so a later [apply]
     * captures fresh values.
     */
    fun release() {
        stopObserving()
        val am = audioManager
        if (am != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                }
                savedMode?.let { am.mode = it }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    savedSpeakerphoneOn?.let { am.isSpeakerphoneOn = it }
                }
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyModern(am: AudioManager, route: AudioRoute): Boolean = when (route) {
        AudioRoute.EARPIECE -> {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val earpiece = findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            val selected = earpiece != null && am.setCommunicationDevice(earpiece)
            if (!selected) {
                // No earpiece device, or the platform refused the selection.
                am.isSpeakerphoneOn = false
                false
            } else {
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
                false
            } else if (sco != null && am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                am.setCommunicationDevice(sco)
            } else {
                // A2DP is routed automatically; drop any explicit selection.
                am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
            }
            true
        }

        AudioRoute.WIRED -> {
            // A wired headset is auto-routed for media; just drop the selection.
            am.clearCommunicationDevice()
            stopSco(am)
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                savedSpeakerphoneOn = am.isSpeakerphoneOn
            }
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
    }
}
