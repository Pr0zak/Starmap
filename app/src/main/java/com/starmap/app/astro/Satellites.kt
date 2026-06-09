package com.starmap.app.astro

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an SGP4 inertial (TEME) satellite position into the observer's local
 * East/North/Up frame, so a satellite can be drawn alongside the stars.
 */
object Satellites {

    private const val A = 6378.137          // WGS-84 semi-major axis, km
    private const val F = 1.0 / 298.257223563
    private val E2 = F * (2 - F)

    /** Observer geodetic position -> ECEF rectangular coordinates (km). */
    fun observerEcef(latDeg: Double, lonDeg: Double, altMeters: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val h = altMeters / 1000.0
        val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
        val x = (n + h) * cos(lat) * cos(lon)
        val y = (n + h) * cos(lat) * sin(lon)
        val z = (n * (1 - E2) + h) * sin(lat)
        return doubleArrayOf(x, y, z)
    }

    /**
     * East/North/Up vector (km) from observer to satellite. [satTeme] is the SGP4
     * TEME position; [gmstRad] rotates TEME to Earth-fixed.
     */
    fun lookEnu(
        satTeme: DoubleArray,
        obsEcef: DoubleArray,
        gmstRad: Double,
        latDeg: Double,
        lonDeg: Double,
    ): DoubleArray {
        val ce = cos(gmstRad); val se = sin(gmstRad)
        // TEME -> ECEF (rotation about the pole by GMST).
        val xe = ce * satTeme[0] + se * satTeme[1]
        val ye = -se * satTeme[0] + ce * satTeme[1]
        val ze = satTeme[2]
        return enuFromEcef(doubleArrayOf(xe, ye, ze), obsEcef, latDeg, lonDeg)
    }

    /** East/North/Up (km) from observer to a target, both already in ECEF. */
    fun enuFromEcef(
        targetEcef: DoubleArray,
        obsEcef: DoubleArray,
        latDeg: Double,
        lonDeg: Double,
    ): DoubleArray {
        val rx = targetEcef[0] - obsEcef[0]
        val ry = targetEcef[1] - obsEcef[1]
        val rz = targetEcef[2] - obsEcef[2]
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val sLon = sin(lon); val cLon = cos(lon)
        return doubleArrayOf(
            -sLon * rx + cLon * ry,
            -sLat * cLon * rx - sLat * sLon * ry + cLat * rz,
            cLat * cLon * rx + cLat * sLon * ry + sLat * rz,
        )
    }
}
