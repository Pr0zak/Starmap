package com.starmap.app.events.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.starmap.app.events.Observer
import com.starmap.app.events.QuietHours
import com.starmap.app.events.SkyEventCalculator
import com.starmap.app.events.SkyEventKind
import com.starmap.app.settings.NotificationSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Says the one thing it was queued to say — if it is still worth saying.
 *
 * The event is recomputed here rather than carried across from the scan: this worker can
 * run in a process started long after the scan finished, so anything held in memory
 * would be gone. Recomputing is also what lets the worker notice that the event has been
 * overtaken, which a stored copy could not.
 */
class EventDeliverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext
        val eventId = inputData.getString(AlertScheduling.KEY_EVENT_ID)
            ?: return@withContext Result.failure()

        val repo = NotificationSettingsRepository(app)
        val prefs = repo.snapshot()
        if (!prefs.enabled) return@withContext Result.success()

        val notifier = Notifier(app)
        if (!notifier.canPost()) return@withContext Result.success()

        val state = repo.stateSnapshot()
        // Said already. Can happen when a rescan and a queued delivery race.
        if (eventId in state.seenIds) return@withContext Result.success()

        val now = System.currentTimeMillis()
        val observer = if (state.hasLocation) {
            Observer(
                state.cachedLat, state.cachedLon, state.cachedAltM,
                ZoneId.systemDefault(), now - state.cachedAtMillis,
            )
        } else {
            null
        }
        val zone = observer?.zone ?: ZoneId.systemDefault()

        val event = runCatching {
            SkyEventCalculator().events(
                observer = observer,
                fromMillis = now - 2 * 86_400_000L,
                toMillis = now + 5 * 86_400_000L,
                prefs = prefs,
            ).firstOrNull { it.id == eventId }
        }.getOrElse {
            Log.e(TAG, "Could not rebuild event $eventId", it)
            return@withContext Result.retry()
        } ?: return@withContext Result.success() // no longer matches the user's settings

        // Doze can hold a worker well past its slot. An alert that has gone stale is
        // dropped rather than delivered late, because a sky alert that arrives after the
        // fact is not late — it is wrong.
        if (event.validUntilMillis < now) {
            Log.i(TAG, "Dropping $eventId: no longer current")
            return@withContext Result.success()
        }
        if (prefs.quietEnabled) {
            val exempt = prefs.quietAllowEclipses &&
                (event.kind == SkyEventKind.LunarEclipse || event.kind == SkyEventKind.SolarEclipse)
            if (!exempt && QuietHours.inQuietHours(now, prefs.quietStartHour, prefs.quietEndHour, zone)) {
                Log.i(TAG, "Dropping $eventId: delayed into quiet hours")
                return@withContext Result.success()
            }
        }

        // Record and post together, so a retry or a racing rescan cannot double up.
        deliveryLock.withLock {
            val fresh = repo.stateSnapshot()
            if (eventId in fresh.seenIds) return@withLock
            repo.recordRun(setOf(eventId), now)
            notifier.post(event)
        }
        Result.success()
    }

    private companion object {
        const val TAG = "Starmap"

        /** Guards the check-then-post so two workers cannot both decide it is unsent. */
        val deliveryLock = Mutex()
    }
}
