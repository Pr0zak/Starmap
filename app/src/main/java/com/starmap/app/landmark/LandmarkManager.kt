package com.starmap.app.landmark

import com.starmap.app.update.DiagLog
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
 *
 * Public Overpass instances are flaky: some are busy, some sit behind Cloudflare
 * that rejects non-browser clients with a 403. So we try several mirrors, prefer
 * a plain GET (CDN-friendly) with a POST fallback, and pull the real reason out
 * of any error body so the UI can report something useful instead of a bare code.
 * Runs on the phone, which has open network access.
 */
class LandmarkManager {

    sealed interface Result {
        data class Ok(val landmarks: List<Landmark>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(lat: Double, lon: Double, radiusMeters: Int = RADIUS_M): Result =
        withContext(Dispatchers.IO) {
            val r = radiusMeters
            val query = """
                [out:json][timeout:25];
                (
                  node["place"~"city|town|village|borough"](around:$r,$lat,$lon);
                  node["aeroway"="aerodrome"]["name"](around:$r,$lat,$lon);
                  way["aeroway"="aerodrome"]["name"](around:$r,$lat,$lon);
                  node["man_made"~"tower|mast"]["name"](around:$r,$lat,$lon);
                );
                out center 120;
            """.trimIndent()
            val encoded = URLEncoder.encode(query, "UTF-8")
            DiagLog.log("Landmarks: fetch lat=%.4f lon=%.4f r=%dm".format(lat, lon, r))
            val errors = ArrayList<String>()
            var sawEmpty = false
            for (endpoint in ENDPOINTS) {
                val host = runCatching { URL(endpoint).host }.getOrDefault(endpoint)
                when (val a = attempt(host, endpoint, encoded)) {
                    is Attempt.Body -> {
                        val json = runCatching { JSONObject(a.text) }.getOrNull()
                        if (json == null) {
                            DiagLog.log("Landmarks: $host -> 200 but unreadable (${a.text.length}B)")
                            errors.add("$host: unreadable response")
                        } else {
                            val raw = json.optJSONArray("elements")?.length() ?: 0
                            val items = parse(json)
                            val remark = json.optString("remark")
                            DiagLog.log(
                                "Landmarks: $host -> 200, ${a.text.length}B, raw=$raw parsed=${items.size}" +
                                    if (remark.isNotBlank()) " remark='${hint(remark)}'" else "",
                            )
                            if (items.isNotEmpty()) {
                                DiagLog.log("Landmarks: using ${items.size} from $host")
                                return@withContext Result.Ok(items)
                            }
                            // Overpass answers 200 with empty elements + a "remark" when it
                            // times out or runs short of memory. An empty result with no
                            // remark can still be a regional mirror that lacks this area, so
                            // keep trying the others and only trust "empty" if they all agree.
                            if (remark.isNotBlank()) {
                                errors.add("$host: ${hint(remark)}")
                            } else {
                                DiagLog.log("Landmarks: $host returned empty — trying other servers")
                                sawEmpty = true
                            }
                        }
                    }
                    is Attempt.Error -> errors.add("$host: ${a.reason}")
                }
            }
            if (sawEmpty) {
                DiagLog.log("Landmarks: every reachable server reports the area empty")
                return@withContext Result.Ok(emptyList())
            }
            DiagLog.log("Landmarks: all endpoints failed (${errors.firstOrNull() ?: "no response"})")
            Result.Failed(errors.firstOrNull() ?: "no response")
        }

    /**
     * One endpoint: POST (Overpass's canonical method) first, with a GET fallback.
     * overpass-api.de returns 406 to our GET regardless of the Accept header, so
     * POST has to lead. Each failed method is logged so a stubborn server is
     * obvious in the diagnostics.
     */
    private fun attempt(host: String, endpoint: String, encoded: String): Attempt {
        val post = request(endpoint, encoded, "POST")
        if (post is Attempt.Body) return post
        DiagLog.log("Landmarks: $host POST -> ${(post as Attempt.Error).reason}")
        val get = request(endpoint, encoded, "GET")
        if (get is Attempt.Body) return get
        DiagLog.log("Landmarks: $host GET -> ${(get as Attempt.Error).reason}")
        return post
    }

    private sealed interface Attempt {
        data class Body(val text: String) : Attempt
        data class Error(val reason: String) : Attempt
    }

    private fun request(endpoint: String, encodedQuery: String, method: String): Attempt {
        return try {
            val url = if (method == "GET") URL("$endpoint?data=$encodedQuery") else URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("User-Agent", USER_AGENT)
                // Keep the request as plain as a canonical Overpass client's: a fake
                // browser User-Agent, an explicit Accept, or gzip negotiation all make
                // overpass-api.de's Apache answer 406 Not Acceptable. So: honest UA, no
                // Accept header at all, and no compression.
                setRequestProperty("Accept-Encoding", "identity")
                connectTimeout = 10_000
                readTimeout = 25_000
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (method == "POST") {
                conn.outputStream.use { it.write(("data=$encodedQuery").toByteArray()) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                Attempt.Body(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                val detail = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { hint(it) }.orEmpty()
                Attempt.Error("HTTP $code${if (detail.isNotBlank()) " ($detail)" else ""}")
            }
        } catch (e: Exception) {
            Attempt.Error(e.message ?: "network error")
        }
    }

    /** A short, human-readable hint pulled from an Overpass HTML/text error page. */
    private fun hint(body: String): String {
        val clean = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return clean.take(80)
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
        /** Search radius around the observer, in metres. */
        const val RADIUS_M = 60000

        // Honest, descriptive UA as Overpass asks for. A fake browser UA makes
        // overpass-api.de answer 406, and naming the app is the documented etiquette.
        const val USER_AGENT = "Starmap/1.0 (+https://github.com/pr0zak/starmap)"

        // Planet-wide instances only (a regional mirror like overpass.osm.ch covers
        // just its own country and would wrongly report everywhere else as empty).
        // Non-Cloudflare first, the Cloudflare-fronted mirrors as last resorts.
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
    }
}
