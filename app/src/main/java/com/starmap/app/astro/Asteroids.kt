package com.starmap.app.astro

import com.starmap.app.astro.AstroMath.DEG2RAD
import com.starmap.app.astro.AstroMath.RAD2DEG
import com.starmap.app.astro.AstroMath.norm360
import org.json.JSONArray
import java.io.InputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geocentric positions of bright minor planets from osculating orbital elements
 * (bundled in assets/asteroids.json, sourced from Stellarium's data). Two-body
 * Keplerian propagation from the element epoch — accurate to a few arc-minutes
 * for a year or so either side of the epoch, which is plenty for pointing.
 */
object Asteroids {

    class Element(
        val name: String,
        val a: Double, val e: Double, val i: Double,
        val om: Double, val w: Double, val ma: Double,
        val n: Double, val epoch: Double, val h: Double,
    )

    data class Asteroid(
        val name: String,
        val raDeg: Double,
        val decDeg: Double,
        val distanceAu: Double,
        val eclipticLatDeg: Double,
        val magnitude: Double,
        val sizeDp: Float,
    )

    fun parse(input: InputStream): List<Element> {
        val arr = JSONArray(input.bufferedReader().use { it.readText() })
        val out = ArrayList<Element>(arr.length())
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            out.add(
                Element(
                    o.getString("name"), o.getDouble("a"), o.getDouble("e"), o.getDouble("i"),
                    o.getDouble("om"), o.getDouble("w"), o.getDouble("ma"), o.getDouble("n"),
                    o.getDouble("epoch"), o.optDouble("H", 10.0),
                ),
            )
        }
        return out
    }

    fun positions(elements: List<Element>, jd: Double): List<Asteroid> {
        val d = AstroMath.schlyterDay(jd)
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD
        val sun = sunRectEcliptic(d)
        return elements.map { el ->
            val (xg, yg, zg, r, lat) = heliocentricGeocentric(el, jd, sun)
            val xe = xg
            val ye = yg * cos(oblecl) - zg * sin(oblecl)
            val ze = yg * sin(oblecl) + zg * cos(oblecl)
            val ra = norm360(atan2(ye, xe) * RAD2DEG)
            val dec = atan2(ze, hypot(xe, ye)) * RAD2DEG
            val delta = sqrt(xg * xg + yg * yg + zg * zg)
            val mag = el.h + 5.0 * log10((r * delta).coerceAtLeast(1e-6))
            val size = (3.2 + (9.0 - mag) * 0.45).coerceIn(2.0, 6.0).toFloat()
            Asteroid(el.name, ra, dec, delta, lat, mag, size)
        }
    }

    /** RA/Dec only (degrees) for one asteroid at [jd] — used to plot its path. */
    fun raDec(el: Element, jd: Double): DoubleArray {
        val d = AstroMath.schlyterDay(jd)
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD
        val sun = sunRectEcliptic(d)
        val (xg, yg, zg) = heliocentricGeocentric(el, jd, sun)
        val xe = xg
        val ye = yg * cos(oblecl) - zg * sin(oblecl)
        val ze = yg * sin(oblecl) + zg * cos(oblecl)
        return doubleArrayOf(norm360(atan2(ye, xe) * RAD2DEG), atan2(ze, hypot(xe, ye)) * RAD2DEG)
    }

    private data class Geo(
        val xg: Double, val yg: Double, val zg: Double, val r: Double, val eclLat: Double,
    )

    private fun heliocentricGeocentric(el: Element, jd: Double, sun: DoubleArray): Geo {
        val m = norm360(el.ma + el.n * (jd - el.epoch))
        val mr = m * DEG2RAD
        var ea = m + RAD2DEG * el.e * sin(mr) * (1.0 + el.e * cos(mr))
        repeat(8) {
            val er = ea * DEG2RAD
            ea -= (ea - RAD2DEG * el.e * sin(er) - m) / (1.0 - el.e * cos(er))
        }
        val er = ea * DEG2RAD
        val xv = el.a * (cos(er) - el.e)
        val yv = el.a * (sqrt(1.0 - el.e * el.e) * sin(er))
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)

        val nr = el.om * DEG2RAD
        val vwr = (v + el.w) * DEG2RAD
        val ir = el.i * DEG2RAD
        val xh = r * (cos(nr) * cos(vwr) - sin(nr) * sin(vwr) * cos(ir))
        val yh = r * (sin(nr) * cos(vwr) + cos(nr) * sin(vwr) * cos(ir))
        val zh = r * (sin(vwr) * sin(ir))
        val eclLat = atan2(zh, hypot(xh, yh)) * RAD2DEG
        return Geo(xh + sun[0], yh + sun[1], zh, r, eclLat)
    }

    private fun sunRectEcliptic(d: Double): DoubleArray {
        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val m = norm360(356.0470 + 0.9856002585 * d)
        val ea = m + RAD2DEG * e * sin(m * DEG2RAD) * (1.0 + e * cos(m * DEG2RAD))
        val er = ea * DEG2RAD
        val xv = cos(er) - e
        val yv = sqrt(1.0 - e * e) * sin(er)
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)
        val lon = (v + w) * DEG2RAD
        return doubleArrayOf(r * cos(lon), r * sin(lon))
    }
}
