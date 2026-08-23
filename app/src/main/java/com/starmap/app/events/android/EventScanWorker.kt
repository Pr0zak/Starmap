package com.starmap.app.events.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.starmap.app.events.AlertPlanner
import com.starmap.app.events.AlertPrefs
import com.starmap.app.events.AlertType
import com.starmap.app.events.IssPasses
import com.starmap.app.events.Observer
import com.starmap.app.events.SkyEventCalculator
import com.starmap.app.satellite.SatelliteManager
import com.starmap.app.settings.NotificationSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Works out what is coming up and queues the alerts for it.
 *
 * Runs every twelve hours. Everything it needs is on the device — the ephemeris, the
 * settings, the last known location — so it never touches the network and works in
 * airplane mode.
 */
class EventScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext
        val repo = NotificationSettingsRepository(app)
        val prefs = repo.snapshot()

        // A blocked or switched-off app should stop burning battery, not retry forever,
        // so these return success rather than failure.
        if (!prefs.enabled) return@withContext Result.success()
        if (!Notifier(app).canPost()) return@withContext Result.success()

        val state = repo.stateSnapshot()
        val now = System.currentTimeMillis()
        val observer = if (state.hasLocation) {
            Observer(
                latDeg = state.cachedLat,
                lonDeg = state.cachedLon,
                elevationM = state.cachedAltM,
                zone = ZoneId.systemDefault(),
                fixAgeMillis = now - state.cachedAtMillis,
            )
        } else {
            null
        }

        val events = try {
            val horizon = now + AlertScheduling.HORIZON_DAYS * 86_400_000L
            SkyEventCalculator().events(
                observer = observer,
                fromMillis = now,
                toMillis = horizon,
                prefs = prefs,
                issPasses = issPasses(app, prefs, observer, now),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Sky event scan failed", t)
            return@withContext Result.retry()
        }

        if (isStopped) return@withContext Result.retry()

        val planned = AlertPlanner.plan(
            events, prefs, observer?.zone ?: ZoneId.systemDefault(), now, state.seenIds,
        )

        val notifier = Notifier(app)
        val fired = HashSet<String>()
        for (alert in planned) {
            when {
                // Due already — say it now rather than queueing a zero-delay worker.
                alert.deliverAtMillis <= now -> {
                    notifier.post(alert.event)
                    fired.add(alert.event.id)
                }
                // Near enough to be worth queueing. Anything further out is picked up by
                // a later scan, so the queue never fills with dozens of pending workers.
                alert.deliverAtMillis - now <= AlertScheduling.SCHEDULE_AHEAD_MILLIS ->
                    AlertScheduling.scheduleDelivery(app, alert.event.id, alert.deliverAtMillis, now)
            }
        }
        repo.recordRun(fired, now)
        Log.i(TAG, "Sky event scan: ${events.size} events, ${planned.size} planned, ${fired.size} sent")
        Result.success()
    }

    /**
     * ISS passes, but only when the orbital elements are actually there and fresh.
     * [IssPasses] returns nothing rather than something wrong when they are stale.
     */
    private suspend fun issPasses(
        context: Context,
        prefs: AlertPrefs,
        observer: Observer?,
        now: Long,
    ): List<com.starmap.app.events.IssPass> {
        if (observer == null || !prefs[AlertType.IssPass]) return emptyList()
        val manager = SatelliteManager(context)
        if (!manager.isIssDownloaded) return emptyList()
        val sat = manager.loadIss().firstOrNull() ?: return emptyList()
        return runCatching {
            IssPasses.find(
                sgp4 = sat.sgp4,
                tleAgeMillis = manager.issAgeMillis,
                observer = observer,
                fromMillis = now,
                // Elements drift, so there is no point predicting further than a couple
                // of nights out; the next scan will extend it.
                toMillis = now + 2 * 86_400_000L,
                minPeakAltitudeDeg = prefs.issMinPeakAltDeg.toDouble(),
            )
        }.getOrElse {
            Log.w(TAG, "ISS pass prediction failed", it)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "Starmap"
    }
}
