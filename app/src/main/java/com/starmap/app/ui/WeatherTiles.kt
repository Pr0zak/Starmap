package com.starmap.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Weather radar/cloud tiles from RainViewer (free, no API key). Provides a timeline
 * of past frames so the radar can animate precipitation / clouds over the last ~2h.
 * Frames are pre-rendered to bitmaps so playback is smooth (osmdroid's tile cache is
 * keyed by coordinate, not frame, so swapping sources live would thrash and tear).
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

    /** Tile grid (Web-Mercator zoom [z]) covering the scope, shared by every frame. */
    data class Grid(val z: Int, val txMin: Int, val txMax: Int, val tyMin: Int, val tyMax: Int)

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
     * Download every tile of one frame over [grid] and composite them into a single
     * bitmap (transparent where there's no precipitation). [rain] picks a precipitation
     * colour scheme; otherwise an infrared-cloud scheme.
     */
    suspend fun loadFrameBitmap(host: String, path: String, rain: Boolean, grid: Grid): Bitmap? {
        val wTiles = grid.txMax - grid.txMin + 1
        val hTiles = grid.tyMax - grid.tyMin + 1
        if (wTiles <= 0 || hTiles <= 0 || wTiles * hTiles > 80) return null
        val suffix = if (rain) "/2/1_1.png" else "/0/0_0.png"
        val n = 1 shl grid.z
        return withContext(Dispatchers.IO) {
            val bmp = Bitmap.createBitmap(wTiles * 256, hTiles * 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            coroutineScope {
                val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
                for (tx in grid.txMin..grid.txMax) {
                    for (ty in grid.tyMin..grid.tyMax) {
                        if (ty < 0 || ty >= n) continue
                        val wx = ((tx % n) + n) % n
                        val dx = ((tx - grid.txMin) * 256).toFloat()
                        val dy = ((ty - grid.tyMin) * 256).toFloat()
                        jobs += async {
                            try {
                                val url = "$host$path/256/${grid.z}/$wx/$ty$suffix"
                                val bytes = URL(url).openStream().use { it.readBytes() }
                                val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (tile != null) synchronized(canvas) { canvas.drawBitmap(tile, dx, dy, null) }
                            } catch (e: Exception) {
                                // missing tile → leave transparent
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
            bmp
        }
    }
}
