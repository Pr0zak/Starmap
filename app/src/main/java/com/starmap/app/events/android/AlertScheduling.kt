package com.starmap.app.events.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When the app looks for events, and when it says something about them.
 *
 * All of it runs on WorkManager, with no alarms of any kind. Every event that ships here
 * is evening-scale — "the Perseids peak tonight", "there's an eclipse this evening" —
 * and a quarter of an hour either way costs nothing. Buying minute precision would mean
 * exact alarms, which are denied by default from API 34, force-stop the app when
 * revoked, and drag in re-arming on boot and package replacement. WorkManager persists
 * its own queue and restores it across reboots, so there is nothing to re-arm.
 */
object AlertScheduling {

    private const val UNIQUE_SCAN = "starmap-alert-scan"
    private const val UNIQUE_RESCAN = "starmap-alert-rescan"
    const val KEY_EVENT_ID = "eventId"

    /** How far ahead the scan looks. */
    const val HORIZON_DAYS = 40L

    /** Only alerts inside this window get a scheduled delivery; the rest wait for the next scan. */
    const val SCHEDULE_AHEAD_MILLIS = 36L * 3_600_000

    fun ensurePeriodicScan(context: Context) {
        val request = PeriodicWorkRequestBuilder<EventScanWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            // No constraints on purpose. Requiring a network, a charger or a healthy
            // battery could hold the scan back past the very evening it was meant to
            // plan for, and the whole calculation is offline anyway.
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_SCAN,
            // UPDATE rather than KEEP: with KEEP, a later release that changes the
            // interval would never take effect on an existing install.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Recompute now — after a settings change, or when the user asks. */
    fun rescanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EventScanWorker>()
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_RESCAN, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Queue one alert. Named after the event, and replacing any earlier copy, so repeated
     * scans converge on a single delivery rather than stacking them.
     */
    fun scheduleDelivery(context: Context, eventId: String, deliverAtMillis: Long, nowMillis: Long) {
        val delay = (deliverAtMillis - nowMillis).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<EventDeliverWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_EVENT_ID, eventId).build())
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("deliver:$eventId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Stop everything — called when the user turns alerts off. */
    fun cancelEverything(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_SCAN)
        wm.cancelUniqueWork(UNIQUE_RESCAN)
        wm.cancelAllWorkByTag(EventDeliverWorker::class.java.name)
    }
}
