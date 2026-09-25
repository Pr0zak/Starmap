package com.starmap.app.sky

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.SunMoon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * When an object next rises, culminates and sets, for the object card's
 * "when to look" line.
 *
 * Objects are treated as fixed on the celestial sphere over the next day, which is
 * exact for stars and deep-sky objects and within a few minutes for the planets and
 * the Sun. The Moon moves about 13° a day, so its times are refined by recomputing
 * its position at each estimated event.
 */
object RiseSet {
    /** Sidereal day as a fraction of a solar day: the sky's own rotation period. */
    private const val SIDEREAL_DAY_MS = 86_164_091L

    /** Standard altitude of a point source at rise/set (refraction lifts it 34'). */
    const val H0_STAR = -0.5667

    /** Sun's upper limb (refraction + semi-diameter). */
    const val H0_SUN = -0.833

    /** Moon: refraction + semi-diameter − horizontal parallax, roughly. */
    const val H0_MOON = 0.125

    data class Times(
        /** Next rise after "now", or null when it never rises or never sets. */
        val riseMillis: Long?,
        /** Next set after "now", or null when it never rises or never sets. */
        val setMillis: Long?,
        /** Next upper culmination (highest point) after "now". */
        val transitMillis: Long,
        /** Altitude at culmination, degrees. */
        val transitAltDeg: Double,
        val circumpolar: Boolean,
        val neverRises: Boolean,
    )

    /** Equatorial RA/Dec (degrees) of a direction given as altitude/azimuth. */
    fun raDecFromAltAz(altDeg: Double, azDeg: Double, latDeg: Double, lstDeg: Double): Pair<Double, Double> {
        val b = AstroMath.enuBasis(lstDeg, latDeg)
        val a = Math.toRadians(altDeg)
        val z = Math.toRadians(azDeg)
        val e = cos(a) * sin(z)
        val n = cos(a) * cos(z)
        val u = sin(a)
        val x = e * b.east[0] + n * b.north[0] + u * b.up[0]
        val y = e * b.east[1] + n * b.north[1] + u * b.up[1]
        val w = e * b.east[2] + n * b.north[2] + u * b.up[2]
        val ra = AstroMath.norm360(Math.toDegrees(atan2(y, x)))
        val dec = Math.toDegrees(asin(w.coerceIn(-1.0, 1.0)))
        return ra to dec
    }

    /** Rise/transit/set for a fixed RA/Dec, as seen from lat/lon, after [nowMillis]. */
    fun forFixed(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        nowMillis: Long,
        h0Deg: Double = H0_STAR,
    ): Times {
        val lst = AstroMath.lstDegrees(AstroMath.julianDay(nowMillis), lonDeg)
        // Time until the object next crosses the meridian (hour angle 0).
        val toTransit = (AstroMath.norm360(raDeg - lst) / 360.0 * SIDEREAL_DAY_MS).toLong()
        val transit = nowMillis + toTransit
        val transitAlt = 90.0 - abs(latDeg - decDeg)

        val phi = Math.toRadians(latDeg)
        val dec = Math.toRadians(decDeg)
        val cosH = (sin(Math.toRadians(h0Deg)) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosH < -1.0) return Times(null, null, transit, transitAlt, circumpolar = true, neverRises = false)
        if (cosH > 1.0) return Times(null, null, transit, transitAlt, circumpolar = false, neverRises = true)

        val halfArc = (Math.toDegrees(acos(cosH)) / 360.0 * SIDEREAL_DAY_MS).toLong()
        var rise = transit - halfArc
        if (rise < nowMillis) rise += SIDEREAL_DAY_MS
        var set = transit + halfArc - SIDEREAL_DAY_MS
        if (set < nowMillis) set += SIDEREAL_DAY_MS
        return Times(rise, set, transit, transitAlt, circumpolar = false, neverRises = false)
    }

    /** The Moon, refining each event with the Moon's position at that time. */
    fun forMoon(latDeg: Double, lonDeg: Double, nowMillis: Long): Times {
        fun moonAt(t: Long): Pair<Double, Double> {
            val jd = AstroMath.julianDay(t)
            val eq = SunMoon.moon(jd, latDeg, AstroMath.lstDegrees(jd, lonDeg))
            return eq.raDeg to eq.decDeg
        }
        fun at(t: Long): Times {
            val (ra, dec) = moonAt(t)
            return forFixed(ra, dec, latDeg, lonDeg, nowMillis, H0_MOON)
        }
        var times = at(nowMillis)
        // Two passes: recompute each event using the Moon's position at the previous estimate.
        repeat(2) {
            val r = times.riseMillis?.let { at(it).riseMillis }
            val s = times.setMillis?.let { at(it).setMillis }
            val tr = at(times.transitMillis)
            times = tr.copy(
                riseMillis = r ?: tr.riseMillis,
                setMillis = s ?: tr.setMillis,
            )
        }
        return times
    }

    /**
     * One line for the card, e.g. "Rises 1:10 AM · highest 6:30 AM (62°)" or
     * "Never sets · highest 4:10 AM (80°)". Events are listed in the order they happen.
     */
    fun describe(t: Times, locale: Locale = Locale.getDefault()): String {
        val hm = SimpleDateFormat("h:mm a", locale)
        fun f(ms: Long) = hm.format(Date(ms))
        val high = "highest ${f(t.transitMillis)} (${t.transitAltDeg.toInt()}°)"
        return when {
            t.neverRises -> "Doesn't rise from here"
            t.circumpolar -> "Never sets · $high"
            else -> {
                val rise = t.riseMillis!!
                val set = t.setMillis!!
                val events = buildList {
                    add(rise to "rises ${f(rise)}")
                    add(set to "sets ${f(set)}")
                    // Culmination only matters while the object is above the horizon.
                    if (t.transitAltDeg > 0 && (t.transitMillis < set || t.transitMillis > rise)) {
                        add(t.transitMillis to high)
                    }
                }.sortedBy { it.first }.take(2).map { it.second }
                events.joinToString(" · ").replaceFirstChar { it.uppercase() }
            }
        }
    }

    /**
     * Rise/set for an identified sky object at the model's time and place, or null for
     * things that don't rise and set with the sky (aircraft, satellites, landmarks).
     */
    fun forObject(obj: IdentifiedObject, model: SkyModel): Times? {
        if (obj.aircraftHex != null) return null
        val target = obj.target ?: return null
        val alt = obj.altDeg ?: return null
        val az = obj.azDeg ?: return null
        val lat = model.location.latitude
        val lon = model.location.longitude
        val now = model.timeMillis
        if (target is SearchTarget.SpecialT) {
            when (target.label) {
                "Moon" -> return forMoon(lat, lon, now)
                "Sun" -> Unit
                else -> return null // satellites pass over in minutes, not with the sky
            }
        }
        val lst = AstroMath.lstDegrees(AstroMath.julianDay(now), lon)
        val (ra, dec) = raDecFromAltAz(alt.toDouble(), az.toDouble(), lat, lst)
        val h0 = if (target is SearchTarget.SpecialT) H0_SUN else H0_STAR
        return forFixed(ra, dec, lat, lon, now, h0)
    }
}
