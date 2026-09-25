package com.starmap.app.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Radar calculations kept free of Android so they can be unit-tested. */
object RadarMath {

    /**
     * Where a straight-line track comes nearest the observer: [minutes] from now,
     * [distanceKm] at that moment, and the point itself ([eastKm], [northKm]).
     */
    data class Approach(val minutes: Float, val distanceKm: Float, val eastKm: Float, val northKm: Float)

    /**
     * Closest point of approach for something at ([eastKm], [northKm]) moving at
     * [speedKts] along [trackDeg]. Null when it's already moving away, isn't moving,
     * or won't be nearest for more than [horizonMin] minutes (a straight line that far
     * out is a guess).
     */
    fun closestApproach(
        eastKm: Float,
        northKm: Float,
        speedKts: Double,
        trackDeg: Double,
        horizonMin: Float = 20f,
    ): Approach? {
        val kmPerMin = (speedKts * 1.852 / 60.0).toFloat()
        if (kmPerMin < 0.2f) return null
        val t = Math.toRadians(trackDeg)
        val ve = (sin(t) * kmPerMin).toFloat()
        val vn = (cos(t) * kmPerMin).toFloat()
        val tMin = -(eastKm * ve + northKm * vn) / (ve * ve + vn * vn)
        if (tMin <= 0f || tMin > horizonMin) return null
        val e = eastKm + ve * tMin
        val n = northKm + vn * tMin
        return Approach(tMin, hypot(e, n), e, n)
    }

    /** Elevation above the horizon and azimuth (degrees) of a unit east-north-up vector. */
    fun lookAngles(enu: FloatArray): Pair<Float, Float> {
        val el = Math.toDegrees(asin(enu[2].coerceIn(-1f, 1f).toDouble())).toFloat()
        val az = ((Math.toDegrees(atan2(enu[0].toDouble(), enu[1].toDouble())) + 360.0) % 360.0).toFloat()
        return el to az
    }

    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2.0 * 6371.0 * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    /** How far along a flight is, and when it should arrive at the current ground speed. */
    data class Progress(val fraction: Float, val remainingKm: Double, val etaMillis: Long?)

    fun routeProgress(
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double,
        lat: Double, lon: Double,
        speedKts: Double,
        nowMillis: Long,
    ): Progress? {
        if (listOf(originLat, originLon, destLat, destLon, lat, lon).any { it.isNaN() }) return null
        val flown = distanceKm(originLat, originLon, lat, lon)
        val left = distanceKm(lat, lon, destLat, destLon)
        if (flown + left < 1.0) return null
        val eta = if (speedKts > 50) nowMillis + (left / (speedKts * 1.852) * 3_600_000).toLong() else null
        return Progress((flown / (flown + left)).toFloat().coerceIn(0f, 1f), left, eta)
    }

    /** Radar distance units, matching the radarUnits setting (0 nm, 1 mi, 2 km). */
    enum class Unit(val label: String, val perKm: Double) {
        NauticalMiles("nm", 0.539957), Miles("mi", 0.621371), Kilometres("km", 1.0);

        fun fromKm(km: Double): Double = km * perKm
        fun toKm(v: Double): Double = v / perKm

        /** "0.8 nm", "12 nm": one decimal under 10, whole numbers above. */
        fun format(km: Double): String {
            val v = fromKm(km)
            return if (v < 10) "%.1f %s".format(v, label) else "${v.roundToInt()} $label"
        }

        companion object {
            fun of(setting: Int): Unit = entries.getOrElse(setting) { NauticalMiles }
        }
    }
}
