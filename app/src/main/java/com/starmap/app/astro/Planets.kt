package com.starmap.app.astro

import com.starmap.app.astro.AstroMath.DEG2RAD
import com.starmap.app.astro.AstroMath.RAD2DEG
import com.starmap.app.astro.AstroMath.norm360
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-precision geocentric planet positions using Paul Schlyter's orbital
 * elements (the same source as [SunMoon]). Accuracy is ~1–2 arc-minutes for the
 * inner planets; Jupiter and Saturn include their main mutual perturbations.
 * More than good enough to point a phone at the right dot of light.
 */
object Planets {

    data class Planet(
        val name: String,
        val raDeg: Double,
        val decDeg: Double,
        /** Geocentric distance in AU. */
        val distanceAu: Double,
        /** Heliocentric ecliptic latitude (deg) — handy for sanity checks. */
        val eclipticLatDeg: Double,
        val colorArgb: Long,
        val sizeDp: Float,
    )

    private class Elements(
        val name: String,
        val n: (Double) -> Double,
        val i: (Double) -> Double,
        val w: (Double) -> Double,
        val a: (Double) -> Double,
        val e: (Double) -> Double,
        val m: (Double) -> Double,
        val color: Long,
        val size: Float,
    )

    private val bodies = listOf(
        Elements("Mercury",
            { 48.3313 + 3.24587e-5 * it }, { 7.0047 + 5.00e-8 * it },
            { 29.1241 + 1.01444e-5 * it }, { 0.387098 }, { 0.205635 + 5.59e-10 * it },
            { 168.6562 + 4.0923344368 * it }, 0xFFB8A98F, 4.5f),
        Elements("Venus",
            { 76.6799 + 2.46590e-5 * it }, { 3.3946 + 2.75e-8 * it },
            { 54.8910 + 1.38374e-5 * it }, { 0.723330 }, { 0.006773 - 1.302e-9 * it },
            { 48.0052 + 1.6021302244 * it }, 0xFFFFF3D0, 7.5f),
        Elements("Mars",
            { 49.5574 + 2.11081e-5 * it }, { 1.8497 - 1.78e-8 * it },
            { 286.5016 + 2.92961e-5 * it }, { 1.523688 }, { 0.093405 + 2.516e-9 * it },
            { 18.6021 + 0.5240207766 * it }, 0xFFE0623B, 5.5f),
        Elements("Jupiter",
            { 100.4542 + 2.76854e-5 * it }, { 1.3030 - 1.557e-7 * it },
            { 273.8777 + 1.64505e-5 * it }, { 5.20256 }, { 0.048498 + 4.469e-9 * it },
            { 19.8950 + 0.0830853001 * it }, 0xFFEAD6B8, 7.0f),
        Elements("Saturn",
            { 113.6634 + 2.38980e-5 * it }, { 2.4886 - 1.081e-7 * it },
            { 339.3939 + 2.97661e-5 * it }, { 9.55475 }, { 0.055546 - 9.499e-9 * it },
            { 316.9670 + 0.0334442282 * it }, 0xFFE8D8A0, 6.5f),
        Elements("Uranus",
            { 74.0005 + 1.3978e-5 * it }, { 0.7733 + 1.9e-8 * it },
            { 96.6612 + 3.0565e-5 * it }, { 19.18171 - 1.55e-8 * it }, { 0.047318 + 7.45e-9 * it },
            { 142.5905 + 0.011725806 * it }, 0xFFB6E6E6, 4.0f),
        Elements("Neptune",
            { 131.7806 + 3.0173e-5 * it }, { 1.7700 - 2.55e-7 * it },
            { 272.8461 - 6.027e-6 * it }, { 30.05826 + 3.313e-8 * it }, { 0.008606 + 2.15e-9 * it },
            { 260.2471 + 0.005995147 * it }, 0xFF8FA9FF, 4.0f),
    )

    /** Geocentric RA/Dec for all planets at the given Julian Day. */
    fun positions(jd: Double): List<Planet> {
        val d = AstroMath.schlyterDay(jd)
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD

        // Sun's geocentric ecliptic rectangular coordinates (Earth->Sun).
        val sun = sunRectEcliptic(d)

        // Mean anomalies needed for the giant-planet perturbations.
        val mj = 19.8950 + 0.0830853001 * d
        val ms = 316.9670 + 0.0334442282 * d
        val mu = 142.5905 + 0.011725806 * d

        return bodies.map { b ->
            val helio = heliocentric(b, d)
            var lon = helio[0]; var lat = helio[1]; val r = helio[2]
            when (b.name) {
                "Jupiter" -> lon += jupiterPerturbation(mj, ms)
                "Saturn" -> {
                    lon += saturnLonPerturbation(mj, ms)
                    lat += saturnLatPerturbation(mj, ms)
                }
                "Uranus" -> lon += uranusPerturbation(mj, ms, mu)
            }

            // Heliocentric ecliptic (lon/lat/r) -> rectangular, add the Sun vector.
            val lonr = lon * DEG2RAD
            val latr = lat * DEG2RAD
            val xh = r * cos(lonr) * cos(latr)
            val yh = r * sin(lonr) * cos(latr)
            val zh = r * sin(latr)
            val xg = xh + sun[0]
            val yg = yh + sun[1]
            val zg = zh

            // Ecliptic -> equatorial.
            val xe = xg
            val ye = yg * cos(oblecl) - zg * sin(oblecl)
            val ze = yg * sin(oblecl) + zg * cos(oblecl)
            val ra = norm360(atan2(ye, xe) * RAD2DEG)
            val dec = atan2(ze, hypot(xe, ye)) * RAD2DEG
            val dist = sqrt(xg * xg + yg * yg + zg * zg)
            Planet(b.name, ra, dec, dist, lat, b.color, b.size)
        }
    }

    /** Returns heliocentric ecliptic [lonDeg, latDeg, r] for a planet. */
    private fun heliocentric(b: Elements, d: Double): DoubleArray {
        val n = b.n(d); val i = b.i(d); val w = b.w(d)
        val a = b.a(d); val e = b.e(d); val m = norm360(b.m(d))

        var ea = m + RAD2DEG * e * sin(m * DEG2RAD) * (1.0 + e * cos(m * DEG2RAD))
        repeat(8) {
            val er = ea * DEG2RAD
            ea -= (ea - RAD2DEG * e * sin(er) - m) / (1.0 - e * cos(er))
        }
        val er = ea * DEG2RAD
        val xv = a * (cos(er) - e)
        val yv = a * (sqrt(1.0 - e * e) * sin(er))
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)

        val nr = n * DEG2RAD
        val vwr = (v + w) * DEG2RAD
        val ir = i * DEG2RAD
        val xh = r * (cos(nr) * cos(vwr) - sin(nr) * sin(vwr) * cos(ir))
        val yh = r * (sin(nr) * cos(vwr) + cos(nr) * sin(vwr) * cos(ir))
        val zh = r * (sin(vwr) * sin(ir))
        val lon = norm360(atan2(yh, xh) * RAD2DEG)
        val lat = atan2(zh, hypot(xh, yh)) * RAD2DEG
        return doubleArrayOf(lon, lat, r)
    }

    private fun sunRectEcliptic(d: Double): DoubleArray {
        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val m = norm360(356.0470 + 0.9856002585 * d)
        var ea = m + RAD2DEG * e * sin(m * DEG2RAD) * (1.0 + e * cos(m * DEG2RAD))
        val er = ea * DEG2RAD
        val xv = cos(er) - e
        val yv = sqrt(1.0 - e * e) * sin(er)
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)
        val lon = (v + w) * DEG2RAD
        return doubleArrayOf(r * cos(lon), r * sin(lon))
    }

    private fun s(x: Double) = sin(x * DEG2RAD)
    private fun c(x: Double) = cos(x * DEG2RAD)

    private fun jupiterPerturbation(mj: Double, ms: Double): Double =
        (-0.332 * s(2 * mj - 5 * ms - 67.6)
            - 0.056 * s(2 * mj - 2 * ms + 21.0)
            + 0.042 * s(3 * mj - 5 * ms + 21.0)
            - 0.036 * s(mj - 2 * ms)
            + 0.022 * c(mj - ms)
            + 0.023 * s(2 * mj - 3 * ms + 52.0)
            - 0.016 * s(mj - 5 * ms - 69.0))

    private fun saturnLonPerturbation(mj: Double, ms: Double): Double =
        (0.812 * s(2 * mj - 5 * ms - 67.6)
            - 0.229 * c(2 * mj - 4 * ms - 2.0)
            + 0.119 * s(mj - 2 * ms - 3.0)
            + 0.046 * s(2 * mj - 6 * ms - 69.0)
            + 0.014 * s(mj - 3 * ms + 32.0))

    private fun saturnLatPerturbation(mj: Double, ms: Double): Double =
        (-0.020 * c(2 * mj - 4 * ms - 2.0)
            + 0.018 * s(2 * mj - 6 * ms - 49.0))

    private fun uranusPerturbation(mj: Double, ms: Double, mu: Double): Double =
        (0.040 * s(ms - 2 * mu + 6.0)
            + 0.035 * s(ms - 3 * mu + 33.0)
            - 0.015 * s(mj - mu + 20.0))
}
