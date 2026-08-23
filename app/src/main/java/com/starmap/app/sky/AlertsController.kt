package com.starmap.app.sky

import android.app.Application
import android.util.Log
import com.starmap.app.events.AlertPrefs
import com.starmap.app.events.AlertType
import com.starmap.app.events.Observer
import com.starmap.app.events.SkyEvent
import com.starmap.app.events.SkyEventCalculator
import com.starmap.app.events.android.AlertScheduling
import com.starmap.app.events.android.NotificationChannels
import com.starmap.app.events.android.Notifier
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.settings.NotificationSettingsRepository
import com.starmap.app.settings.NotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sky-alerts feature as the UI sees it — one of the focused controllers the view
 * model composes, alongside the satellite, aircraft and update controllers.
 *
 * It also does the one thing that keeps the background scan honest: mirroring the last
 * foreground location into storage, so the worker knows where "here" is without the app
 * ever asking for background-location permission.
 */
@OptIn(FlowPreview::class)
class AlertsController(
    private val app: Application,
    effectiveLocation: StateFlow<LocationProvider.Fix?>,
    private val scope: CoroutineScope,
) {
    private val repo = NotificationSettingsRepository(app)

    val prefs: StateFlow<AlertPrefs> =
        repo.prefs.stateIn(scope, SharingStarted.Eagerly, AlertPrefs())

    val state: StateFlow<NotificationState> =
        repo.state.stateIn(scope, SharingStarted.Eagerly, NotificationState())

    /** What the next month would bring, shown in the settings screen. */
    sealed interface PreviewState {
        data object Loading : PreviewState
        data object NoLocation : PreviewState
        data class Ready(val events: List<SkyEvent>) : PreviewState
    }

    private val _preview = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    private val latestFix = MutableStateFlow<LocationProvider.Fix?>(null)

    init {
        // Re-assert the background schedule on every launch. The collector below skips its
        // first emission by design, so a toggle whose process died inside the debounce would
        // otherwise leave alerts switched on with no scan ever enqueued, and nothing later
        // would notice. UPDATE keeps any existing period, so this is idempotent.
        scope.launch {
            if (repo.snapshot().enabled) {
                AlertScheduling.ensurePeriodicScan(app)
            } else {
                AlertScheduling.cancelEverything(app)
            }
        }
        scope.launch {
            effectiveLocation.filterNotNull().collect { fix ->
                latestFix.value = fix
                maybeCacheLocation(fix)
            }
        }
        // Rescan when the settings change, debounced so dragging a slider does not
        // enqueue a scan on every frame.
        scope.launch {
            repo.prefs.distinctUntilChanged().drop(1).debounce(1_000).collect { p ->
                if (p.enabled) {
                    AlertScheduling.ensurePeriodicScan(app)
                    AlertScheduling.rescanNow(app)
                } else {
                    AlertScheduling.cancelEverything(app)
                }
                refreshPreview()
            }
        }
    }

    /**
     * Only written when it is worth writing: the location provider pushes an update
     * every thirty seconds, and none of these events cares about five kilometres.
     */
    private suspend fun maybeCacheLocation(fix: LocationProvider.Fix) {
        val s = repo.stateSnapshot()
        val now = System.currentTimeMillis()
        val moved = s.hasLocation &&
            distanceKm(s.cachedLat, s.cachedLon, fix.latitude, fix.longitude) > 5.0
        val stale = now - s.cachedAtMillis > 6 * 3_600_000L
        if (!s.hasLocation || moved || stale) {
            repo.cacheLocation(fix.latitude, fix.longitude, fix.altitude, now)
        }
    }

    fun setEnabled(value: Boolean) = scope.launch {
        repo.setEnabled(value)
        if (value) {
            NotificationChannels.ensure(app)
            // The one moment a fresh fix is nearly guaranteed and the user has just said
            // yes, so grab it rather than waiting for the throttle.
            latestFix.value?.let {
                repo.cacheLocation(it.latitude, it.longitude, it.altitude, System.currentTimeMillis())
            }
        }
    }

    fun setType(type: AlertType, value: Boolean) = scope.launch { repo.setType(type, value) }
    fun setBool(selector: NotificationSettingsRepository.AlertBool, value: Boolean) =
        scope.launch { repo.setBool(selector, value) }
    fun setInt(selector: NotificationSettingsRepository.AlertInt, value: Int) =
        scope.launch { repo.setInt(selector, value) }
    fun setFloat(selector: NotificationSettingsRepository.AlertFloat, value: Float) =
        scope.launch { repo.setFloat(selector, value) }

    fun markPermissionRequested() = scope.launch { repo.markPermissionRequested() }

    /** Recompute the "coming up" list for the settings screen. */
    fun refreshPreview() = scope.launch {
        _preview.value = PreviewState.Loading
        val s = repo.stateSnapshot()
        val p = repo.snapshot()
        val now = System.currentTimeMillis()
        val observer = when {
            s.hasLocation -> Observer(
                s.cachedLat, s.cachedLon, s.cachedAltM, ZoneId.systemDefault(), now - s.cachedAtMillis,
            )
            latestFix.value != null -> latestFix.value!!.let {
                Observer(it.latitude, it.longitude, it.altitude, ZoneId.systemDefault(), 0L)
            }
            else -> null
        }
        if (observer == null) {
            _preview.value = PreviewState.NoLocation
            return@launch
        }
        val events = withContext(Dispatchers.Default) {
            runCatching {
                SkyEventCalculator().events(observer, now, now + PREVIEW_DAYS * 86_400_000L, p)
            }.getOrElse {
                Log.e(TAG, "Preview failed", it)
                emptyList()
            }
        }
        _preview.value = PreviewState.Ready(events)
    }

    /**
     * Send one now so the user can see what an alert looks like. Uses a real upcoming
     * event when there is one, so the preview is honest rather than a lorem-ipsum card.
     */
    fun sendTest() = scope.launch {
        val notifier = Notifier(app)
        if (!notifier.canPost()) {
            _testStatus.value = "Android is blocking Starmap's notifications."
            return@launch
        }
        NotificationChannels.ensure(app)
        val upcoming = (preview.value as? PreviewState.Ready)?.events?.firstOrNull()
        val event = upcoming ?: sampleEvent()
        notifier.post(event)
        _testStatus.value = "Sent — check your notification shade."
    }

    fun clearTestStatus() { _testStatus.value = null }

    private fun sampleEvent(): SkyEvent {
        val now = System.currentTimeMillis()
        return SkyEvent(
            id = "test:sample",
            kind = com.starmap.app.events.SkyEventKind.MeteorPeak,
            subject = "Sample",
            peakMillis = now,
            windowStartMillis = now,
            windowEndMillis = now + 3_600_000L,
            deliverAtMillis = now,
            validUntilMillis = now + 3_600_000L,
            priority = 50,
            title = "This is what a sky alert looks like",
            body = "Nothing is happening right now — this is a test. Real alerts tell you what " +
                "is up, when to look, which way to face and whether the Moon will be in the way.",
            confidence = com.starmap.app.events.Confidence.Firm,
            requiresLocation = false,
        )
    }

    /** Great-circle distance in km, for the "has the user actually moved" check. */
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        const val TAG = "Starmap"
        const val PREVIEW_DAYS = 30L
    }
}
