package com.starmap.app.astro

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
