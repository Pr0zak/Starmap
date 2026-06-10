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
 * Tries a few public mirrors in turn, since any one can be busy. Runs on the
 * phone, which has open network access.
 */
class LandmarkManager {

    sealed interface Result {
        data class Ok(val landmarks: List<Landmark>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(lat: Double, lon: Double, radiusMeters: Int = 60000): Result =
        withContext(Dispatchers.IO) {
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
            val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()
            var lastError = "no response"
            for (endpoint in ENDPOINTS) {
                try {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("User-Agent", "Starmap-Android (+https://github.com/pr0zak/starmap)")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        lastError = "HTTP $code"
                        continue
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    return@withContext Result.Ok(parse(json))
                } catch (e: Exception) {
                    lastError = e.message ?: "network error"
                }
            }
            Result.Failed(lastError)
        }

    private fun parse(json: JSONObject): List<Landmark> {
        val els = json.optJSONArray("elements") ?: return emptyList()
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
        return out
    }

    private companion object {
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
    }
}
