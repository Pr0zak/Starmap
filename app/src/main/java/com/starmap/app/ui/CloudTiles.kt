package com.starmap.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.starmap.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs

/**
 * Cloud cover for the radar, from NASA GIBS (free, no key): the latest geostationary
 * infrared image (GOES-East, GOES-West or Himawari, whichever sees the observer best).
 *
 * GIBS serves the infrared as a false-colour temperature map. Each pixel is matched
 * back to a temperature through GIBS's own colour map, and anything colder than about
 * −2 °C (cloud tops) is redrawn as translucent white, colder being more opaque. Warm
 * ground drops out, so the layer sits on the dark scope like real cloud.
 */
object CloudTiles {
    /** GIBS only goes to zoom 6 for these layers (about 2.4 km per pixel). */
    const val MAX_ZOOM = 6

    private class Sat(val layer: String, val lonDeg: Double, val label: String)

    private val SATS = listOf(
        Sat("GOES-East_ABI_Band13_Clean_Infrared", -75.2, "GOES-East"),
        Sat("GOES-West_ABI_Band13_Clean_Infrared", -137.2, "GOES-West"),
        Sat("Himawari_AHI_Band13_Clean_Infrared", 140.7, "Himawari"),
    )

    private const val COLORMAP =
        "https://gibs.earthdata.nasa.gov/colormaps/v1.3/Clean_Longwave_Infrared_Window_Band.xml"

    /** The satellite that sees [lonDeg] best, or null where none of them does (Europe, Africa, India). */
    fun satelliteFor(lonDeg: Double): String? = satFor(lonDeg)?.label

    private fun satFor(lonDeg: Double): Sat? = SATS.minByOrNull { lonDiff(it.lonDeg, lonDeg) }
        ?.takeIf { lonDiff(it.lonDeg, lonDeg) <= 65.0 }

    private fun lonDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    // Colour-map entries as packed RGB + the temperature (°C) at the middle of its band.
    @Volatile private var palette: Pair<IntArray, FloatArray>? = null

    private suspend fun palette(): Pair<IntArray, FloatArray>? {
        palette?.let { return it }
        val xml = Http.getText(COLORMAP) ?: return null
        val rgb = ArrayList<Int>()
        val temp = ArrayList<Float>()
        val re = Regex("""rgb="(\d+),(\d+),(\d+)"[^>]*?value="[\[(]?(-?[\d.]+),(-?[\d.]+)""")
        for (m in re.findAll(xml)) {
            val (r, g, b, lo, hi) = m.destructured
            rgb += (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            temp += ((lo.toFloat() + hi.toFloat()) / 2f)
        }
        if (rgb.isEmpty()) return null
        return (rgb.toIntArray() to temp.toFloatArray()).also { palette = it }
    }

    /** Temperature for a pixel colour: the nearest colour-map entry. */
    private fun tempOf(c: Int, pal: Pair<IntArray, FloatArray>, memo: HashMap<Int, Float>): Float =
        memo.getOrPut(c and 0xFFFFFF) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            var best = 0
            var bestD = Int.MAX_VALUE
            val cols = pal.first
            for (i in cols.indices) {
                val dr = ((cols[i] shr 16) and 0xFF) - r
                val dg = ((cols[i] shr 8) and 0xFF) - g
                val db = (cols[i] and 0xFF) - b
                val d = dr * dr + dg * dg + db * db
                if (d < bestD) { bestD = d; best = i }
            }
            pal.second[best]
        }

    /**
     * Download the latest cloud image over [grid] (zoom ≤ [MAX_ZOOM]) for an observer
     * at [lonDeg], converted to white cloud on transparent. Null when no satellite
     * covers the area or nothing could be fetched.
     */
    suspend fun loadBitmap(grid: WeatherTiles.Grid, outPx: Int, lonDeg: Double): Bitmap? {
        val sat = satFor(lonDeg) ?: return null
        val pal = palette() ?: return null
        val wTiles = grid.txMax - grid.txMin + 1
        val hTiles = grid.tyMax - grid.tyMin + 1
        if (wTiles <= 0 || hTiles <= 0 || wTiles * hTiles > 60) return null
        val n = 1 shl grid.z
        return withContext(Dispatchers.IO) {
            val bmp = Bitmap.createBitmap(wTiles * outPx, hTiles * outPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            val memo = HashMap<Int, Float>(4096)
            coroutineScope {
                val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
                for (tx in grid.txMin..grid.txMax) for (ty in grid.tyMin..grid.tyMax) {
                    if (ty < 0 || ty >= n) continue
                    val wx = ((tx % n) + n) % n
                    jobs += async {
                        runCatching {
                            val url = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/${sat.layer}" +
                                "/default/default/GoogleMapsCompatible_Level6/${grid.z}/$ty/$wx.png"
                            val bytes = URL(url).openStream().use { it.readBytes() }
                            val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                            val w = tile.width
                            val h = tile.height
                            val px = IntArray(w * h)
                            tile.getPixels(px, 0, w, 0, 0, w, h)
                            tile.recycle()
                            synchronized(memo) {
                                for (i in px.indices) {
                                    val c = px[i]
                                    if ((c ushr 24) < 16) { px[i] = 0; continue }
                                    // Colder than about −2 °C reads as cloud; fully opaque by about −45 °C,
                                    // so thin or mid-level cloud stays a light veil.
                                    val a = ((-2f - tempOf(c, pal, memo)) / 43f).coerceIn(0f, 1f)
                                    px[i] = if (a <= 0.02f) 0 else ((a * 235).toInt() shl 24) or 0xF2F5FA
                                }
                            }
                            val cloud = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                            val dst = android.graphics.Rect(
                                (tx - grid.txMin) * outPx, (ty - grid.tyMin) * outPx,
                                (tx - grid.txMin + 1) * outPx, (ty - grid.tyMin + 1) * outPx,
                            )
                            synchronized(canvas) { canvas.drawBitmap(cloud, null, dst, paint) }
                            cloud.recycle()
                        }
                        Unit
                    }
                }
                jobs.awaitAll()
            }
            bmp
        }
    }
}
