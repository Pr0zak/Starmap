package com.starmap.app.astro

import com.starmap.app.astro.AstroMath.DEG2RAD
import com.starmap.app.astro.AstroMath.RAD2DEG
import com.starmap.app.astro.AstroMath.norm360
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-precision Sun and Moon positions based on Paul Schlyter's
 * "How to compute planetary positions". Accuracy is ~1 arc-minute for the Sun
 * and a few arc-minutes for the Moon (with the main perturbation terms and a
 * topocentric parallax correction), which is far better than a phone's compass.
 */
object SunMoon {

    /** Geocentric equatorial position (RA/Dec in degrees) plus distance. */
    data class Equatorial(val raDeg: Double, val decDeg: Double, val distance: Double)

    /** Apparent solar position. */
    fun sun(jd: Double): Equatorial {
        val d = AstroMath.daysSinceJ2000(jd)
        val w = 282.9404 + 4.70935e-5 * d      // longitude of perihelion
        val e = 0.016709 - 1.151e-9 * d        // eccentricity
        val m = norm360(356.0470 + 0.9856002585 * d) // mean anomaly
        val oblecl = 23.4393 - 3.563e-7 * d    // obliquity of the ecliptic

        val mr = m * DEG2RAD
        // Eccentric anomaly (one Newton iteration is plenty for the Sun).
        var eAnom = m + RAD2DEG * e * sin(mr) * (1.0 + e * cos(mr))
        val ear = eAnom * DEG2RAD
        val xv = cos(ear) - e
        val yv = sqrt(1.0 - e * e) * sin(ear)
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)
        val lon = norm360(v + w)               // true ecliptic longitude

        // Ecliptic -> equatorial (Sun's ecliptic latitude is 0).
        val lonr = lon * DEG2RAD
        val xe = r * cos(lonr)
        val ye = r * sin(lonr) * cos(oblecl * DEG2RAD)
        val ze = r * sin(lonr) * sin(oblecl * DEG2RAD)
        val ra = norm360(atan2(ye, xe) * RAD2DEG)
        val dec = atan2(ze, hypot(xe, ye)) * RAD2DEG
        return Equatorial(ra, dec, r)
    }

    /**
     * Topocentric lunar position. Applies the main perturbation terms and corrects
     * for the observer's position on the Earth's surface (parallax up to ~1°).
     */
    fun moon(jd: Double, latitudeDeg: Double, lstDeg: Double): Equatorial {
        val d = AstroMath.daysSinceJ2000(jd)

        // Orbital elements of the Moon.
        val n = 125.1228 - 0.0529538083 * d
        val i = 5.1454
        val w = 318.0634 + 0.1643573223 * d
        val a = 60.2666 // Earth radii
        val e = 0.054900
        val m = norm360(115.3654 + 13.0649929509 * d)

        // Eccentric anomaly (iterate, the Moon's eccentricity needs it).
        val mr = m * DEG2RAD
        var ea = m + RAD2DEG * e * sin(mr) * (1.0 + e * cos(mr))
        repeat(5) {
            val ear = ea * DEG2RAD
            ea -= (ea - RAD2DEG * e * sin(ear) - m) / (1.0 - e * cos(ear))
        }
        val ear = ea * DEG2RAD

        val xv = a * (cos(ear) - e)
        val yv = a * (sqrt(1.0 - e * e) * sin(ear))
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)

        // Position in ecliptic coordinates.
        val nr = n * DEG2RAD
        val vwr = (v + w) * DEG2RAD
        val ir = i * DEG2RAD
        val xh = r * (cos(nr) * cos(vwr) - sin(nr) * sin(vwr) * cos(ir))
        val yh = r * (sin(nr) * cos(vwr) + cos(nr) * sin(vwr) * cos(ir))
        val zh = r * (sin(vwr) * sin(ir))

        var lon = norm360(atan2(yh, xh) * RAD2DEG)
        var lat = atan2(zh, hypot(xh, yh)) * RAD2DEG

        // Perturbations: need the Sun's mean longitude/anomaly.
        val ws = 282.9404 + 4.70935e-5 * d
        val ms = 356.0470 + 0.9856002585 * d
        val ls = ws + ms              // Sun's mean longitude
        val lm = n + w + m            // Moon's mean longitude
        val dd = lm - ls              // mean elongation
        val f = lm - n               // argument of latitude

        fun s(x: Double) = sin(x * DEG2RAD)
        fun c(x: Double) = cos(x * DEG2RAD)

        lon += (-1.274 * s(m - 2 * dd)
                + 0.658 * s(2 * dd)
                - 0.186 * s(ms)
                - 0.059 * s(2 * m - 2 * dd)
                - 0.057 * s(m - 2 * dd + ms)
                + 0.053 * s(m + 2 * dd)
                + 0.046 * s(2 * dd - ms)
                + 0.041 * s(m - ms)
                - 0.035 * s(dd)
                - 0.031 * s(m + ms)
                - 0.015 * s(2 * f - 2 * dd)
                + 0.011 * s(m - 4 * dd))
        lat += (-0.173 * s(f - 2 * dd)
                - 0.055 * s(m - f - 2 * dd)
                - 0.046 * s(m + f - 2 * dd)
                + 0.033 * s(f + 2 * dd)
                + 0.017 * s(2 * m + f))
        val rad = r + (-0.58 * c(m - 2 * dd) - 0.46 * c(2 * dd))

        lon = norm360(lon)

        // Geocentric ecliptic -> equatorial.
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD
        val lonr = lon * DEG2RAD
        val latr = lat * DEG2RAD
        val xg = rad * cos(lonr) * cos(latr)
        val yg = rad * sin(lonr) * cos(latr)
        val zg = rad * sin(latr)
        val xe = xg
        val ye = yg * cos(oblecl) - zg * sin(oblecl)
        val ze = yg * sin(oblecl) + zg * cos(oblecl)

        var ra = norm360(atan2(ye, xe) * RAD2DEG)
        var dec = atan2(ze, hypot(xe, ye)) * RAD2DEG

        // Topocentric correction (observer on the surface, not Earth's centre).
        val mpar = asin(1.0 / rad) * RAD2DEG               // horizontal parallax
        val gclat = latitudeDeg - 0.1924 * sin(2 * latitudeDeg * DEG2RAD)
        val rho = 0.99833 + 0.00167 * cos(2 * latitudeDeg * DEG2RAD)
        val ha = norm360(lstDeg - ra)
        val har = ha * DEG2RAD
        val gclatr = gclat * DEG2RAD
        val g = atan2(kotlin.math.tan(gclatr), cos(har))
        ra -= mpar * rho * cos(gclatr) * sin(har) / cos(dec * DEG2RAD)
        if (sin(g) != 0.0) {
            dec -= mpar * rho * sin(gclatr) * sin(g - dec * DEG2RAD) / sin(g)
        }
        return Equatorial(norm360(ra), dec, rad)
    }

    /**
     * Illuminated fraction of the Moon's disk (0=new, 1=full) and a waxing flag.
     * Uses the geocentric elongation between Sun and Moon.
     */
    fun moonPhase(jd: Double): Phase {
        val s = sun(jd)
        // Geocentric moon (no topocentric needed for phase) at the equator.
        val m = moon(jd, 0.0, 0.0)
        val sv = AstroMath.equatorialToVec(s.raDeg, s.decDeg)
        val mv = AstroMath.equatorialToVec(m.raDeg, m.decDeg)
        val cosElong = (AstroMath.dot(sv, mv)).coerceIn(-1.0, 1.0)
        val elong = kotlin.math.acos(cosElong)
        val illum = (1.0 - cos(elong)) / 2.0
        // Waxing if the Moon is east of the Sun in ecliptic longitude.
        val dRa = norm360(m.raDeg - s.raDeg)
        val waxing = dRa < 180.0
        return Phase(illum, waxing, elong * RAD2DEG)
    }

    data class Phase(val illuminatedFraction: Double, val waxing: Boolean, val elongationDeg: Double)
}
