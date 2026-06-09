package com.starmap.app.aircraft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A live aircraft position from ADS-B. */
class Aircraft(
    val id: String,
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val isHelicopter: Boolean,
    val typeCode: String,
    val groundSpeedKts: Double,
    val trackDeg: Double,
    val registration: String,
    val verticalRateFpm: Double,
    val squawk: String,
    val isEmergency: Boolean,
    val emergencyText: String,
)

/** An aircraft plus its recent geodetic trail ([lat, lon, altMeters] points, oldest→newest). */
class AircraftTrack(
    val icaoHex: String,
    val callsign: String,
    val isHelicopter: Boolean,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val typeCode: String,
    val groundSpeedKts: Double,
    val trackDeg: Double,
    val registration: String,
    val verticalRateFpm: Double,
    val squawk: String,
    val isEmergency: Boolean,
    val emergencyText: String,
    val trail: List<DoubleArray>,
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
                        .format(Locale.US, latitude, longitude, distanceNm.coerceIn(1, 250)),
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
                        // ADS-B emitter category A7 = rotorcraft; fall back to type code.
                        val category = o.optString("category", "")
                        val typeCode = o.optString("t", "").uppercase()
                        val isHeli = category.equals("A7", ignoreCase = true) ||
                            HELI_TYPES.contains(typeCode)
                        val id = o.optString("hex", callsign)
                        val gs = numberOrNull(o.opt("gs")) ?: 0.0
                        val track = numberOrNull(o.opt("track")) ?: numberOrNull(o.opt("true_heading")) ?: 0.0
                        val registration = o.optString("r", "")
                        val vrate = numberOrNull(o.opt("baro_rate")) ?: numberOrNull(o.opt("geom_rate")) ?: 0.0
                        val squawk = o.optString("squawk", "")
                        val emergencyText = o.optString("emergency", "")
                        val isEmergency = squawk in EMERGENCY_SQUAWKS ||
                            (emergencyText.isNotBlank() && !emergencyText.equals("none", ignoreCase = true))
                        out.add(
                            Aircraft(
                                id, callsign, lat, lon, altFt * 0.3048, isHeli, typeCode, gs, track,
                                registration, vrate, squawk, isEmergency, emergencyText,
                            ),
                        )
                    }
                }
                Result.Ok(out)
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Network error")
            }
        }

    /** Flight route (origin → destination, with airline) for a callsign, from adsbdb. */
    data class Route(val origin: String, val destination: String, val airline: String)

    suspend fun fetchRoute(callsign: String): Route? = withContext(Dispatchers.IO) {
        val cs = callsign.trim()
        if (cs.isEmpty() || cs == "?") return@withContext null
        try {
            val conn = (URL("https://api.adsbdb.com/v0/callsign/$cs").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Starmap-Android")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val fr = resp.optJSONObject("response")?.optJSONObject("flightroute") ?: return@withContext null
            fun airport(key: String): String {
                val a = fr.optJSONObject(key) ?: return "?"
                return a.optString("iata_code").ifBlank { a.optString("icao_code") }
                    .ifBlank { "?" }
            }
            val airline = fr.optJSONObject("airline")?.optString("name", "").orEmpty()
            Route(airport("origin"), airport("destination"), airline)
        } catch (e: Exception) {
            null
        }
    }

    /** A photo of the aircraft (thumbnail URL + credit), from planespotters.net. */
    data class Photo(val thumbnailUrl: String, val link: String, val photographer: String)

    /** Outcome of a photo lookup, so the UI can explain a missing photo. */
    sealed interface PhotoResult {
        data class Ok(val photo: Photo) : PhotoResult
        object None : PhotoResult
        data class Error(val message: String) : PhotoResult
    }

    /**
     * Looks up a photo from planespotters.net. Tries the ICAO hex first (it is
     * always present in ADS-B) and falls back to the registration — many feeds
     * omit the registration, so the hex lookup is what makes photos reliable.
     */
    suspend fun fetchPhoto(icaoHex: String, registration: String): PhotoResult = withContext(Dispatchers.IO) {
        val byHex = photoFrom("hex", icaoHex.trim())
        if (byHex is PhotoResult.Ok) return@withContext byHex
        val byReg = photoFrom("reg", registration.trim())
        when {
            byReg is PhotoResult.Ok -> byReg
            byReg is PhotoResult.Error -> byReg
            byHex is PhotoResult.Error -> byHex
            else -> PhotoResult.None
        }
    }

    private fun photoFrom(kind: String, key: String): PhotoResult {
        if (key.isEmpty() || key == "?") return PhotoResult.None
        return try {
            val conn = (URL("https://api.planespotters.net/pub/photos/$kind/$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Starmap/1.0 (Android; +https://github.com/pr0zak/starmap)")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code !in 200..299) return PhotoResult.Error("HTTP $code")
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val photos = resp.optJSONArray("photos") ?: return PhotoResult.None
            if (photos.length() == 0) return PhotoResult.None
            val ph = photos.getJSONObject(0)
            val thumb = ph.optJSONObject("thumbnail_large") ?: ph.optJSONObject("thumbnail")
            var url = thumb?.optString("src").orEmpty()
            if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
            if (url.isBlank()) return PhotoResult.None
            PhotoResult.Ok(Photo(url, ph.optString("link"), ph.optString("photographer")))
        } catch (e: Exception) {
            PhotoResult.Error(e.message ?: "network error")
        }
    }

    private fun numberOrNull(v: Any?): Double? = (v as? Number)?.toDouble()

    private companion object {
        val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

        // Common ICAO helicopter type codes, for feeds that omit the emitter category.
        val HELI_TYPES = setOf(
            "EC35", "EC45", "EC30", "EC20", "EC55", "EC75", "H135", "H145", "H125", "H155",
            "H160", "H175", "AS50", "AS55", "AS65", "A109", "A119", "A139", "A169", "A189",
            "R22", "R44", "R66", "B06", "B06T", "B407", "B412", "B429", "B430", "B505", "B47G",
            "S76", "S92", "S61", "UH1", "H60", "H64", "EH10", "AW09", "AW39", "AW89", "GAZL",
            "EXPL", "EXEC", "EN28", "EN48", "K126", "MD52", "MD60", "MI8", "PUMA", "LYNX",
        )
    }
}
