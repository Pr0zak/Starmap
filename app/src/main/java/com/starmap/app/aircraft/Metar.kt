package com.starmap.app.aircraft

import com.starmap.app.net.Http
import org.json.JSONArray
import java.util.Locale

/**
 * Current surface wind at nearby airports, from the free, key-less METAR feed at
 * aviationweather.gov. Used by the radar to show why arrivals line up the way they do.
 */
object Metar {
    /** [dirDeg] is where the wind blows from (null when variable); speeds in knots. */
    class Station(
        val icao: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val dirDeg: Int?,
        val speedKt: Int,
        val gustKt: Int?,
    )

    suspend fun fetch(lat: Double, lon: Double, radiusKm: Double): List<Station>? {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val url = "https://aviationweather.gov/api/data/metar?format=json&bbox=%.3f,%.3f,%.3f,%.3f"
            .format(Locale.US, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        val text = Http.getText(url, accept = "application/json") ?: return null
        return runCatching { parse(JSONArray(text)) }.getOrNull()
    }

    internal fun parse(arr: JSONArray): List<Station> {
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val la = o.optDouble("lat", Double.NaN)
            val lo = o.optDouble("lon", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            // "wdir" is a number, or the string "VRB" for variable wind.
            val dir = o.opt("wdir").let { if (it is Number) it.toInt() else null }
            out.add(
                Station(
                    o.optString("icaoId"), o.optString("name"), la, lo, dir,
                    o.optInt("wspd", 0),
                    o.opt("wgst").let { if (it is Number) it.toInt() else null },
                ),
            )
        }
        return out
    }
}
