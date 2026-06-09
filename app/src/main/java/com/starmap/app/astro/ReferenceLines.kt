package com.starmap.app.astro

import kotlin.math.cos
import kotlin.math.sin

/**
 * Fixed great circles / grid lines of the celestial sphere, as flattened
 * equatorial unit-vector polylines (x,y,z per point). Computed once; the sky
 * builder rotates them into the local ENU frame each update.
 */
object ReferenceLines {

    private const val D2R = Math.PI / 180.0
    private val obliquity = 23.4393 * D2R

    /** Celestial equator (declination 0). */
    val equator: FloatArray by lazy {
        polyline(0..360 step 3) { ra -> eq(ra.toDouble(), 0.0) }
    }

    /** Ecliptic (the Sun's yearly path). */
    val ecliptic: FloatArray by lazy {
        polyline(0..360 step 3) { lon ->
            val l = lon * D2R
            doubleArrayOf(cos(l), sin(l) * cos(obliquity), sin(l) * sin(obliquity))
        }
    }

    /** RA/Dec grid: meridians every 2h and parallels every 30° (equator excluded). */
    val grid: List<FloatArray> by lazy {
        val lines = ArrayList<FloatArray>()
        var ra = 0
        while (ra < 360) {
            val r = ra
            lines.add(polyline(-80..80 step 5) { dec -> eq(r.toDouble(), dec.toDouble()) })
            ra += 30
        }
        for (dec in intArrayOf(-60, -30, 30, 60)) {
            lines.add(polyline(0..360 step 5) { r -> eq(r.toDouble(), dec.toDouble()) })
        }
        lines
    }

    private fun eq(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = raDeg * D2R; val dec = decDeg * D2R
        val cd = cos(dec)
        return doubleArrayOf(cd * cos(ra), cd * sin(ra), sin(dec))
    }

    private inline fun polyline(range: IntProgression, point: (Int) -> DoubleArray): FloatArray {
        val pts = range.toList()
        val out = FloatArray(pts.size * 3)
        for ((i, v) in pts.withIndex()) {
            val p = point(v)
            out[i * 3] = p[0].toFloat(); out[i * 3 + 1] = p[1].toFloat(); out[i * 3 + 2] = p[2].toFloat()
        }
        return out
    }
}
