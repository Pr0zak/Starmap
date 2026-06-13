package com.starmap.app.sky

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.starmap.app.landmark.Landmark
import com.starmap.app.landmark.LandmarkManager
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.settings.Settings
import com.starmap.app.update.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fetches nearby ground landmarks (cities, airports, towers) from Overpass while the
 * layer (or radar) is on, refreshing only when the observer moves a few km or the
 * range changes — with a short backoff so dragging the slider or a failing server
 * doesn't hammer the API. Owned by [SkyViewModel] and driven on its scope. The rebuild
 * loop reads [currentLandmarks]; the UI reads [message].
 */
class LandmarkController(
    private val settings: StateFlow<Settings>,
    private val location: StateFlow<LocationProvider.Fix?>,
    scope: CoroutineScope,
) {
    private val manager = LandmarkManager()
    private var landmarks: List<Landmark> = emptyList()
    private var fetchLat = Double.NaN
    private var fetchLon = Double.NaN
    private var fetchRangeKm = Float.NaN
    private var lastAttemptMs = 0L
    private var awaitLogged = false

    private val _message = mutableStateOf<String?>(null)
    val message: State<String?> = _message

    fun currentLandmarks(): List<Landmark> = landmarks

    init {
        scope.launch {
            while (isActive) {
                val s = settings.value
                val fix = location.value
                if ((s.showLandmarks || (s.radarMode && s.radarLandmarks)) && fix != null) {
                    awaitLogged = false
                    val rangeKm = s.landmarkRangeKm
                    val changed = fetchLat.isNaN() ||
                        haversineKm(fetchLat, fetchLon, fix.latitude, fix.longitude) > 5.0 ||
                        kotlin.math.abs(rangeKm - fetchRangeKm) > 0.5f
                    val sinceAttempt = System.currentTimeMillis() - lastAttemptMs
                    if (changed && sinceAttempt > 8_000) {
                        lastAttemptMs = System.currentTimeMillis()
                        DiagLog.log(
                            "Landmarks loop: fix=%.4f,%.4f %s range=%dkm — fetching".format(
                                fix.latitude, fix.longitude, if (fix.fromGps) "gps" else "manual", rangeKm.toInt(),
                            ),
                        )
                        val km = rangeKm.toInt()
                        when (val r = manager.fetch(fix.latitude, fix.longitude, (rangeKm * 1000).toInt())) {
                            is LandmarkManager.Result.Ok -> {
                                landmarks = r.landmarks
                                fetchLat = fix.latitude
                                fetchLon = fix.longitude
                                fetchRangeKm = rangeKm
                                _message.value = if (r.landmarks.isEmpty()) {
                                    "No mapped landmarks within $km km"
                                } else {
                                    "${r.landmarks.size} landmarks within $km km — look toward the horizon"
                                }
                            }
                            is LandmarkManager.Result.Failed ->
                                _message.value = "Landmarks unavailable · ${r.message}"
                        }
                    }
                    delay(3_000)
                } else {
                    if ((s.showLandmarks || (s.radarMode && s.radarLandmarks)) && fix == null && !awaitLogged) {
                        DiagLog.log("Landmarks loop: enabled but no location fix yet")
                        awaitLogged = true
                    }
                    if (landmarks.isNotEmpty() || _message.value != null) {
                        landmarks = emptyList()
                        fetchLat = Double.NaN
                        fetchRangeKm = Float.NaN
                        _message.value = null
                    }
                    delay(3_000)
                }
            }
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val sl = kotlin.math.sin(Math.toRadians(lat2 - lat1) / 2)
        val so = kotlin.math.sin(Math.toRadians(lon2 - lon1) / 2)
        val a = sl * sl + kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) * so * so
        return 2.0 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(a).coerceAtMost(1.0))
    }
}
