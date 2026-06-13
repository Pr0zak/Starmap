package com.starmap.app.sky

import android.util.Log
import com.starmap.app.aircraft.AircraftManager
import com.starmap.app.aircraft.AircraftTrack
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls nearby aircraft from ADS-B while the layer (or radar) is on, keeping a short
 * geodetic trail per aircraft so the sky build can draw motion. Owned by [SkyViewModel]
 * and driven on its scope. The rebuild loop reads [currentTracks]; the detail card's
 * route/photo lookups go through the exposed [manager].
 */
class AircraftController(
    private val settings: StateFlow<Settings>,
    private val location: StateFlow<LocationProvider.Fix?>,
    scope: CoroutineScope,
) {
    val manager = AircraftManager()

    private var tracks: List<AircraftTrack> = emptyList()
    private val history = HashMap<String, ArrayDeque<DoubleArray>>()

    fun currentTracks(): List<AircraftTrack> = tracks

    init {
        scope.launch {
            while (isActive) {
                val s = settings.value
                val fix = location.value
                if ((s.showAircraft || s.radarMode) && fix != null) {
                    val acRange = if (s.radarMode) s.radarRangeNm.toInt() else s.aircraftRangeNm.toInt()
                    when (val r = manager.fetch(fix.latitude, fix.longitude, acRange)) {
                        is AircraftManager.Result.Ok -> {
                            val seen = HashSet<String>()
                            val now = System.currentTimeMillis()
                            tracks = r.aircraft.map { ac ->
                                seen.add(ac.id)
                                val dq = history.getOrPut(ac.id) { ArrayDeque() }
                                dq.addLast(doubleArrayOf(ac.latitude, ac.longitude, ac.altitudeMeters))
                                while (dq.size > 30) dq.removeFirst()
                                AircraftTrack(
                                    ac.id, ac.callsign, ac.isHelicopter, ac.latitude, ac.longitude,
                                    ac.altitudeMeters, ac.typeCode, ac.groundSpeedKts, ac.trackDeg,
                                    ac.registration, ac.verticalRateFpm, ac.squawk, ac.isEmergency,
                                    ac.emergencyText, dq.dropLast(1).toList(), now,
                                )
                            }
                            history.keys.retainAll(seen)
                        }
                        is AircraftManager.Result.Failed -> Log.w(TAG, "Aircraft fetch: ${r.message}")
                    }
                    delay(12_000)
                } else {
                    if (tracks.isNotEmpty()) {
                        tracks = emptyList()
                        history.clear()
                    }
                    delay(2_000)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Starmap"
    }
}
