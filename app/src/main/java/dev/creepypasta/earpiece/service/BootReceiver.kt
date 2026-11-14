package dev.creepypasta.earpiece.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.creepypasta.earpiece.data.RoutePreferences

/**
 * Restores the held audio route after a reboot when the user opted in.
 *
 * Only ACTION_BOOT_COMPLETED is handled; anything else is ignored silently, and no failure here
 * may take down the receiving process.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            val prefs = RoutePreferences(context)
            if (!prefs.restoreOnBoot) return
            // Boot-completed receivers may start a foreground service: this is an exempt case.
            AudioRouteService.apply(context, prefs.requestedRoute)
            Log.i(TAG, "Restored route after boot: ${prefs.requestedRoute}")
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException, missing permission, etc. Nothing to do.
            Log.w(TAG, "Boot restore failed", e)
        }
    }

    private companion object {
        const val TAG = "Earpiece"
    }
}
