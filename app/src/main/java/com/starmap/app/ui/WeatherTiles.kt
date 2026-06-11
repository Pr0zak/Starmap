package com.starmap.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.net.URL

/**
 * Weather radar/cloud tiles from RainViewer (free, no API key). Provides a timeline
 * of past frames so the radar can animate precipitation / clouds over the last ~2h.
 * See https://www.rainviewer.com/api.html
 */
object WeatherTiles {

    data class Frame(val timeSec: Long, val path: String)

    data class Maps(val host: String, val rain: List<Frame>, val clouds: List<Frame>) {
        fun frames(mode: Int): List<Frame> = when (mode) {
            1 -> rain
            2 -> clouds
            else -> emptyList()
        }
    }

    suspend fun fetch(): Maps? = withContext(Dispatchers.IO) {
        try {
            val text = URL("https://api.rainviewer.com/public/weather-maps.json")
                .openStream().bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val host = root.getString("host")
            val radar = root.optJSONObject("radar")
            val rain = parse(radar?.optJSONArray("past")) + parse(radar?.optJSONArray("nowcast"))
            val clouds = parse(root.optJSONObject("satellite")?.optJSONArray("infrared"))
            Maps(host, rain, clouds)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(arr: JSONArray?): List<Frame> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            Frame(f.getLong("time"), f.getString("path"))
        }
    }

    /**
     * osmdroid tile source for one weather frame. The source name embeds the frame
     * path so different frames cache independently and the animation replays smoothly.
     * [rain] picks a precipitation colour scheme; otherwise an infrared-cloud scheme.
     */
    fun tileSource(host: String, path: String, rain: Boolean): OnlineTileSourceBase {
        val base = "$host$path/256/"
        val suffix = if (rain) "/2/1_1.png" else "/0/0_0.png"
        // RainViewer radar/satellite data only exists up to ~zoom 7; higher zooms
        // return empty tiles. Cap here so osmdroid upscales z7 tiles to fill the
        // scope instead of requesting blank ones.
        return object : OnlineTileSourceBase("rv$path", 1, 7, 256, "", arrayOf(base), "RainViewer") {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return base + z + "/" + x + "/" + y + suffix
            }
        }
    }
}
