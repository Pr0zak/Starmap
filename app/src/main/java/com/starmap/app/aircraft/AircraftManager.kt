package com.starmap.app.aircraft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A live aircraft position from ADS-B. */
class Aircraft(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
)

/**
 * Fetches nearby aircraft from the free, key-less adsb.lol ADS-B aggregator.
 * (Runs on the phone, which has open network access.)
 */
class AircraftManager {

    sealed interface Result {
        data class Ok(val aircraft: List<Aircraft>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(latitude: Double, longitude: Double, distanceNm: Int = 120): Result =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.adsb.lol/v2/lat/%.4f/lon/%.4f/dist/%d"
                        .format(Locale.US, latitude, longitude, distanceNm),
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Starmap-Android")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 12_000
                    readTimeout = 12_000
                }
                val code = conn.responseCode
                if (code !in 200..299) return@withContext Result.Failed("HTTP $code")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = json.optJSONArray("ac") ?: json.optJSONArray("aircraft")
                val out = ArrayList<Aircraft>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val lat = o.optDouble("lat", Double.NaN)
                        val lon = o.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        // Altitude: alt_baro/alt_geom are feet, or the string "ground".
                        val altFt = numberOrNull(o.opt("alt_baro"))
                            ?: numberOrNull(o.opt("alt_geom")) ?: continue
                        if (altFt <= 0) continue // on the ground or invalid
                        val callsign = o.optString("flight").trim()
                            .ifBlank { o.optString("hex", "?") }
                        out.add(Aircraft(callsign, lat, lon, altFt * 0.3048))
                    }
                }
                Result.Ok(out)
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Network error")
            }
        }

    private fun numberOrNull(v: Any?): Double? = (v as? Number)?.toDouble()
}
