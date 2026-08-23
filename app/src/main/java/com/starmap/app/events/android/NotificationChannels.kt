package com.starmap.app.events.android

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.starmap.app.events.SkyEventKind

/**
 * The two notification channels sky alerts use.
 *
 * Channel settings become the user's the moment a channel is created — after that, only
 * the name, description and group can be changed from code, and deleting and recreating
 * a channel does not reset what the user chose. So the ids carry a version suffix, the
 * importances below are chosen as things we can live with forever, and nothing here sets
 * a sound or vibration pattern that we would later regret locking in.
 */
object NotificationChannels {

    const val GROUP = "starmap_sky"

    /** Meteor peaks, pairings, moon phases. Silent: informational, and often at night. */
    const val TONIGHT = "sky_tonight_v1"

    /** Eclipses only — once or twice a year, and worth interrupting for. */
    const val RARE = "sky_rare_v1"

    fun channelFor(kind: SkyEventKind): String = when (kind) {
        SkyEventKind.LunarEclipse, SkyEventKind.SolarEclipse -> RARE
        else -> TONIGHT
    }

    /**
     * Created lazily rather than at app start: someone who never turns alerts on should
     * not find Starmap channels sitting in their system settings. Safe to call repeatedly.
     */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Sky alerts"))

        // Starting quiet is the recoverable direction: a user can raise the importance,
        // but the app can never lower it once they have seen it interrupt them.
        val tonight = NotificationChannel(
            TONIGHT, "Tonight's sky", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Meteor peaks, close pairings and other things worth stepping outside for."
            group = GROUP
            setShowBadge(false)
        }
        val rare = NotificationChannel(
            RARE, "Eclipses", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Lunar and solar eclipses. A couple of times a year at most."
            group = GROUP
            setShowBadge(false)
        }
        manager.createNotificationChannel(tonight)
        manager.createNotificationChannel(rare)
    }

    /** True when the user has muted this particular channel in Android's settings. */
    fun blocked(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
