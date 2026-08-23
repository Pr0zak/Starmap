package com.starmap.app.astro

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Core astronomical coordinate math.
 *
 * Frames used throughout the app:
 *  - Equatorial (EQ): X -> (RA=0, Dec=0), Y -> (RA=90, Dec=0), Z -> north celestial pole.
 *    A star's catalog position (RA/Dec) is a fixed unit vector in this frame.
 *  - Topocentric horizontal, expressed as ENU: X=East, Y=North, Z=Up(zenith), true north.
 *
 * Per sky update we build the ENU basis (expressed in EQ coordinates) from the local
 * sidereal time and latitude, then convert each star's EQ vector into ENU once. The
 * per-frame projection then only needs the (cheap) device orientation.
 */
object AstroMath {

    const val DEG2RAD = PI / 180.0
    const val RAD2DEG = 180.0 / PI

    /** Julian Day from a Unix epoch millisecond timestamp (UT). */
    fun julianDay(timeMillis: Long): Double =
        timeMillis / 86_400_000.0 + 2_440_587.5

    /** Days since the J2000.0 epoch. */
    fun daysSinceJ2000(jd: Double): Double = jd - 2_451_545.0

    /**
     * Schlyter's day number: d = 0 at "2000 Jan 0.0" UT, i.e. 1999-12-31 00:00 UT
     * (JD 2451543.5). His own definition is the integer expression
     * `d = 367y - 7(y + (m+9)/12)/4 + 275m/9 + D - 730530` plus UT/24, which is zero
     * on that date.
     *
     * This is NOT [daysSinceJ2000], which is 1.5 days larger. Every Schlyter
     * mean-element polynomial — in SunMoon, Planets, Comets and Asteroids — must use
     * this function; feeding it [daysSinceJ2000] puts every body 1.5 days behind
     * (about 20 degrees for the Moon). [gmstDegrees] is genuinely J2000-referenced
     * and must keep using [daysSinceJ2000].
     */
    fun schlyterDay(jd: Double): Double = jd - 2_451_543.5

    /** Obliquity of the ecliptic in degrees at [jd] (Schlyter's linear term). */
    fun obliquityDeg(jd: Double): Double = 23.4393 - 3.563e-7 * schlyterDay(jd)

    /** Ecliptic longitude in degrees [0,360) of an equatorial position. */
    fun eclipticLongitude(raDeg: Double, decDeg: Double, jd: Double): Double {
        val v = equatorialToVec(raDeg, decDeg)
        val e = obliquityDeg(jd) * DEG2RAD
        return norm360(atan2(v[1] * cos(e) + v[2] * sin(e), v[0]) * RAD2DEG)
    }

    /** Ecliptic latitude in degrees [-90,90] of an equatorial position. */
    fun eclipticLatitude(raDeg: Double, decDeg: Double, jd: Double): Double {
        val v = equatorialToVec(raDeg, decDeg)
        val e = obliquityDeg(jd) * DEG2RAD
        return asin((v[2] * cos(e) - v[1] * sin(e)).coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    /** Great-circle separation between two equatorial positions, in degrees [0,180]. */
    fun angularSepDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val a = equatorialToVec(ra1, dec1)
        val b = equatorialToVec(ra2, dec2)
        return acos(dot(a, b).coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    /** Greenwich Mean Sidereal Time in degrees [0,360). */
    fun gmstDegrees(jd: Double): Double {
        val d = daysSinceJ2000(jd)
        return norm360(280.46061837 + 360.98564736629 * d)
    }

    /** Local (apparent) Mean Sidereal Time in degrees, given east-positive longitude. */
    fun lstDegrees(jd: Double, longitudeEastDeg: Double): Double =
        norm360(gmstDegrees(jd) + longitudeEastDeg)

    /** Unit vector in the equatorial frame for a right ascension / declination (degrees). */
    fun equatorialToVec(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = raDeg * DEG2RAD
        val dec = decDeg * DEG2RAD
        val cd = cos(dec)
        return doubleArrayOf(cd * cos(ra), cd * sin(ra), sin(dec))
    }

    /**
     * Bends a local ENU **unit** vector (stored at [arr]\[base..base+2]) upward to
     * account for atmospheric refraction, which lifts objects near the horizon (up
     * to ~34′ at the horizon). Uses Saemundsson's true→apparent altitude formula and
     * only touches objects below ~15° altitude, where refraction is visible.
     */
    fun refract(arr: FloatArray, base: Int) {
        val u = arr[base + 2]
        if (u < -0.035f || u > 0.26f) return // skip well-below-horizon / high-altitude
        val h = asin(u.toDouble().coerceIn(-1.0, 1.0)) * RAD2DEG
        if (h < -2.0) return
        val rDeg = 1.02 / tan((h + 10.3 / (h + 5.11)) * DEG2RAD) / 60.0
        val hApp = (h + rDeg) * DEG2RAD
        val e = arr[base]
        val n = arr[base + 1]
        val horiz = hypot(e, n)
        if (horiz < 1e-6f) return
        val scale = (cos(hApp) / horiz).toFloat()
        arr[base] = e * scale
        arr[base + 1] = n * scale
        arr[base + 2] = sin(hApp).toFloat()
    }

    /**
     * Build the East/North/Up basis vectors expressed in the equatorial frame for an
     * observer at the given latitude, with the meridian at local sidereal time [lstDeg].
     */
    fun enuBasis(lstDeg: Double, latitudeDeg: Double): EnuBasis {
        val t = lstDeg * DEG2RAD
        val phi = latitudeDeg * DEG2RAD
        val ct = cos(t); val st = sin(t)
        val cp = cos(phi); val sp = sin(phi)
        val east = doubleArrayOf(-st, ct, 0.0)
        val north = doubleArrayOf(-sp * ct, -sp * st, cp)
        val up = doubleArrayOf(cp * ct, cp * st, sp)
        return EnuBasis(east, north, up)
    }

    /** Convert an equatorial vector into ENU components using a precomputed basis. */
    fun toEnu(vecEq: DoubleArray, basis: EnuBasis): DoubleArray = doubleArrayOf(
        dot(vecEq, basis.east),
        dot(vecEq, basis.north),
        dot(vecEq, basis.up),
    )

    /** Convert an equatorial vector directly to azimuth/altitude (degrees). */
    fun toHorizontal(vecEq: DoubleArray, basis: EnuBasis): Horizontal {
        val e = toEnu(vecEq, basis)
        val alt = Math.toDegrees(atan2(e[2], Math.hypot(e[0], e[1])))
        val az = norm360(Math.toDegrees(atan2(e[0], e[1]))) // from north, east positive
        return Horizontal(az, alt)
    }

    fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun norm360(v: Double): Double {
        var x = v % 360.0
        if (x < 0) x += 360.0
        return x
    }

    fun norm360f(v: Float): Float {
        var x = v % 360f
        if (x < 0) x += 360f
        return x
    }

    data class EnuBasis(val east: DoubleArray, val north: DoubleArray, val up: DoubleArray)

    data class Horizontal(val azimuthDeg: Double, val altitudeDeg: Double)
}
