package dev.creepypasta.earpiece.data

import android.content.Context
import android.content.SharedPreferences
import dev.creepypasta.earpiece.model.AudioRoute

/**
 * Persists the user's route choice and the restore-on-boot flag.
 *
 * Plain [SharedPreferences] on purpose: DataStore is not a dependency of this app.
 */
class RoutePreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last route the user asked for; SPEAKER when nothing usable is stored. */
    var requestedRoute: AudioRoute
        get() = AudioRoute.fromKey(prefs.getString(KEY_REQUESTED_ROUTE, null))
            // Earpiece by default: that is the reason someone installs this app. It only
            // applies to a fresh install — once the user has chosen, their choice is stored.
            ?: AudioRoute.EARPIECE
        set(value) {
            prefs.edit().putString(KEY_REQUESTED_ROUTE, value.key).apply()
        }

    /** Whether the saved route is re-applied after a reboot. Defaults to false. */
    var restoreOnBoot: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_BOOT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RESTORE_ON_BOOT, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "route_prefs"
        const val KEY_REQUESTED_ROUTE = "requested_route"
        const val KEY_RESTORE_ON_BOOT = "restore_on_boot"
    }
}
