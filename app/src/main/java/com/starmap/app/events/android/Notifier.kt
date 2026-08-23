package com.starmap.app.events.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starmap.app.R
import com.starmap.app.events.EventIds
import com.starmap.app.events.SkyEvent

/** Puts a sky event on the user's screen. */
class Notifier(private val context: Context) {

    /**
     * Whether a post would actually reach anyone. Both halves matter: on API 33+ the
     * runtime permission can be missing, and on every version the user can switch the
     * app's notifications off in system settings.
     */
    fun canPost(): Boolean {
        val runtimeOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(event: SkyEvent) {
        if (!canPost()) return
        NotificationChannels.ensure(context)
        val channel = NotificationChannels.channelFor(event.kind)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_starmap)
            .setColor(HUD_GOLD)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setContentIntent(DeepLink.pending(context, event))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The body always carries the event's own local time, so the notification's
            // "when" would only be a second, less useful timestamp.
            .setShowWhen(false)
            .setGroup(channel)
            .setSortKey(event.peakMillis.toString())
            .setOnlyAlertOnce(true)
            .build()

        // The permission check lives in canPost() above; repeating the annotation here is
        // what lets lint see it, and the catch covers a revoke between check and post.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(EventIds.notificationId(event.id), notification)
        }
    }

    fun cancel(eventId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(EventIds.notificationId(eventId))
        }
    }

    private companion object {
        /** Hud.Gold, so a notification reads as coming from this app. */
        const val HUD_GOLD = 0xFFFFD54F.toInt()
    }
}
