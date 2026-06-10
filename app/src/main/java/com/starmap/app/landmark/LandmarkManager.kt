package com.starmap.app.landmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A ground landmark (city, airport or tower) near the observer. */
class Landmark(val name: String, val type: String, val latitude: Double, val longitude: Double)

/**
 * Fetches nearby landmarks from the free, key-less OpenStreetMap Overpass API.
 * (Runs on the phone, which has open network access.)
 */
class LandmarkManager {

    sealed interface Result {
        data class Ok(val landmarks: List<Landmark>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(lat: Double, lon: Double, radiusMeters: Int = 60000): Result =
        withContext(Dispatchers.IO) {
            try {
                val r = radiusMeters
                val query = """
                    [out:json][timeout:25];
                    (
                      node["place"="city"](around:$r,$lat,$lon);
                      node["place"="town"](around:$r,$lat,$lon);
                      node["aeroway"="aerodrome"]["name"](around:$r,$lat,$lon);
                      way["aeroway"="aerodrome"]["name"](around:$r,$lat,$lon);
                      node["man_made"~"tower|mast"]["name"](around:$r,$lat,$lon);
                    );
                    out center 90;
                """.trimIndent()
                val body = "data=" + URLEncoder.encode(query, "UTF-8")
                val conn = (URL("https://overpass-api.de/api/interpreter")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "Starmap-Android (+https://github.com/pr0zak/starmap)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) return@withContext Result.Failed("HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val els = json.optJSONArray("elements") ?: return@withContext Result.Ok(emptyList())
                val out = ArrayList<Landmark>(els.length())
                for (i in 0 until els.length()) {
                    val e = els.optJSONObject(i) ?: continue
                    val tags = e.optJSONObject("tags") ?: continue
                    val name = tags.optString("name")
                    if (name.isBlank()) continue
                    val center = e.optJSONObject("center")
                    val plat = when {
                        e.has("lat") -> e.optDouble("lat", Double.NaN)
                        center != null -> center.optDouble("lat", Double.NaN)
                        else -> continue
                    }
                    val plon = when {
                        e.has("lon") -> e.optDouble("lon", Double.NaN)
                        center != null -> center.optDouble("lon", Double.NaN)
                        else -> continue
                    }
                    if (plat.isNaN() || plon.isNaN()) continue
                    val type = when {
                        tags.has("aeroway") -> "airport"
                        tags.optString("man_made").isNotBlank() -> "tower"
                        else -> "city"
                    }
                    out.add(Landmark(name, type, plat, plon))
                }
                Result.Ok(out)
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Network error")
            }
        }
}
