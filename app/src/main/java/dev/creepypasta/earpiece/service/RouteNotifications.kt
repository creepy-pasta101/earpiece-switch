package dev.creepypasta.earpiece.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.creepypasta.earpiece.MainActivity
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute

/**
 * Builds the single ongoing notification that keeps [AudioRouteService] in the foreground.
 *
 * The channel is created once from the service's onCreate; the builder is cheap and is rebuilt
 * whenever the requested route changes so the title always reflects the current route.
 */
class RouteNotifications(private val context: Context) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** Idempotent: creating an existing channel again only updates its metadata. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            // Holding an audio route is a silent, persistent state; no sound, no vibration, no badge.
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    fun build(route: AudioRoute): Notification {
        val routeLabel = context.getString(labelResFor(route))

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // getForegroundService, not getService: the release intent starts a service from a
        // notification action while the app is in the background, which a background
        // startService() is not allowed to do on API 26+.
        val stopIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_STOP,
            Intent(context, AudioRouteService::class.java).setAction(AudioRouteService.ACTION_RELEASE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(context.getString(R.string.notification_title, routeLabel))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action(
                    0,
                    context.getString(R.string.notification_action_stop),
                    stopIntent,
                ),
            )
            .build()
    }

    private fun labelResFor(route: AudioRoute): Int = when (route) {
        AudioRoute.EARPIECE -> R.string.route_earpiece
        AudioRoute.SPEAKER -> R.string.route_speaker
        AudioRoute.BLUETOOTH -> R.string.route_bluetooth
        AudioRoute.WIRED -> R.string.route_wired
    }

    private companion object {
        const val CHANNEL_ID = "audio_route"
        const val REQUEST_CONTENT = 10
        const val REQUEST_STOP = 11
    }
}
