package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.Satellites
import com.starmap.app.astro.Sgp4
import com.starmap.app.astro.SunMoon
import kotlin.math.sqrt

/** One visible overflight: when it appears, how high it gets, and where it goes. */
data class IssPass(
    val startMillis: Long,
    val peakMillis: Long,
    val endMillis: Long,
    val startAzimuthDeg: Double,
    val peakAltitudeDeg: Double,
    val endAzimuthDeg: Double,
    /** Set when the orbital elements are old enough that the timing may have drifted. */
    val approximate: Boolean,
)

/**
 * Finds the Space Station passes that are actually worth going outside for.
 *
 * A pass is only visible when three things line up: the station is above the horizon,
 * it is still in sunlight while the observer is not, and it gets high enough to stand
 * out. The second is the one people get wrong — the ISS is overhead constantly, but for
 * most of those passes it is either in Earth's shadow or the sky is too bright.
 *
 * Pure Kotlin: the caller loads the orbital elements and passes the propagator in, so
 * this is testable on the JVM with no Android runtime.
 */
object IssPasses {

    /** Elements older than this drift enough that timings stop being trustworthy. */
    const val STALE_TLE_MILLIS = 7L * 24 * 3_600_000

    /** Beyond three days old, say so in the wording rather than pretending to precision. */
    private const val APPROXIMATE_TLE_MILLIS = 3L * 24 * 3_600_000

    /** The observer needs a reasonably dark sky; civil twilight is the usual cutoff. */
    private const val OBSERVER_DARK_SUN_ALT = -6.0

    private const val EARTH_RADIUS_KM = 6378.137

    /**
     * Visible passes between [fromMillis] and [toMillis].
     *
     * Returns nothing at all — rather than something wrong — when the elements are older
     * than [STALE_TLE_MILLIS].
     */
    fun find(
        sgp4: Sgp4,
        tleAgeMillis: Long,
        observer: Observer,
        fromMillis: Long,
        toMillis: Long,
        minPeakAltitudeDeg: Double = 30.0,
    ): List<IssPass> {
        if (!observer.isUsable) return emptyList()
        if (tleAgeMillis > STALE_TLE_MILLIS) return emptyList()
        if (sgp4.deepSpace) return emptyList()
        val approximate = tleAgeMillis > APPROXIMATE_TLE_MILLIS

        val obsEcef = Satellites.observerEcef(observer.latDeg, observer.lonDeg, observer.elevationM)
        val out = ArrayList<IssPass>()

        val step = 30_000L
        // Sample past both edges so a pass the window happens to cut through is still seen
        // whole; the peak-in-window check below stops the padding leaking extra passes.
        val pad = 15 * 60_000L
        val scanEnd = toMillis + pad
        var t = fromMillis - pad
        var inPass = false
        var anySunlit = false
        // The geometric pass runs horizon to horizon, but the station drops into Earth's
        // shadow partway across and is never re-lit on the same pass. What the observer can
        // actually see is this sub-arc, and it is what gets reported.
        var visStart = 0L
        var visStartAz = 0.0
        var visEnd = 0L
        var visEndAz = 0.0
        var visPeak = -90.0
        var visPeakAt = 0L

        while (t <= scanEnd) {
            val look = look(sgp4, obsEcef, observer, t)
            if (look == null) { t += step; inPass = false; continue }

            val aboveHorizon = look.altitudeDeg > 0
            if (aboveHorizon && !inPass) {
                inPass = true
                anySunlit = false
                visPeak = -90.0
                visEnd = 0L
            }
            if (inPass && look.sunlit && observerIsDark(observer, t)) {
                if (!anySunlit) {
                    anySunlit = true
                    visStart = t
                    visStartAz = look.azimuthDeg
                }
                visEnd = t
                visEndAz = look.azimuthDeg
                if (look.altitudeDeg > visPeak) { visPeak = look.altitudeDeg; visPeakAt = t }
            }
            if (!aboveHorizon && inPass) {
                inPass = false
                // A "pass" whose visible part is a single sample is not worth waking
                // someone for, and the peak must be the visible peak, not the geometric one.
                if (anySunlit && visPeak >= minPeakAltitudeDeg &&
                    visEnd - visStart >= 60_000L && visPeakAt in fromMillis..toMillis
                ) {
                    out.add(
                        IssPass(
                            startMillis = visStart,
                            peakMillis = visPeakAt,
                            endMillis = visEnd,
                            startAzimuthDeg = visStartAz,
                            peakAltitudeDeg = visPeak,
                            endAzimuthDeg = visEndAz,
                            approximate = approximate,
                        ),
                    )
                }
            }
            t += step
        }
        return out
    }

    private class Look(val altitudeDeg: Double, val azimuthDeg: Double, val sunlit: Boolean)

    private fun look(sgp4: Sgp4, obsEcef: DoubleArray, observer: Observer, atMillis: Long): Look? {
        val jd = AstroMath.julianDay(atMillis)
        val tsince = (jd - sgp4.tle.jdEpoch) * 1440.0
        val teme = sgp4.positionTeme(tsince) ?: return null
        val gmstRad = Math.toRadians(AstroMath.gmstDegrees(jd))
        val enu = Satellites.lookEnu(teme, obsEcef, gmstRad, observer.latDeg, observer.lonDeg)
        val horiz = sqrt(enu[0] * enu[0] + enu[1] * enu[1])
        val alt = Math.toDegrees(Math.atan2(enu[2], horiz))
        val az = AstroMath.norm360(Math.toDegrees(Math.atan2(enu[0], enu[1])))
        return Look(alt, az, isSunlit(teme, jd))
    }

    /**
     * Is the satellite still catching sunlight? Cylindrical shadow test: project the
     * satellite onto the Sun direction — if that projection is positive it is on the
     * sunward side, and otherwise it clears the shadow only if its perpendicular
     * distance exceeds Earth's radius plus the ~90 km of atmosphere that matters.
     */
    private fun isSunlit(teme: DoubleArray, jd: Double): Boolean {
        val s = SunMoon.sun(jd)
        // Sun direction in TEME: take the equatorial unit vector and rotate it the same
        // way lookEnu rotates the satellite, in reverse.
        // TEME and the equatorial frame share their pole and their x-axis to well within
        // what this test needs, so the Sun's equatorial unit vector serves directly.
        val sunHat = AstroMath.equatorialToVec(s.raDeg, s.decDeg)

        val proj = teme[0] * sunHat[0] + teme[1] * sunHat[1] + teme[2] * sunHat[2]
        if (proj > 0) return true
        val r2 = teme[0] * teme[0] + teme[1] * teme[1] + teme[2] * teme[2]
        val perpendicular2 = r2 - proj * proj
        val shadowRadius = EARTH_RADIUS_KM + 90.0
        return perpendicular2 > shadowRadius * shadowRadius
    }

    /** Is the sky dark enough at the observer for a satellite to stand out? */
    private fun observerIsDark(observer: Observer, atMillis: Long): Boolean {
        val jd = AstroMath.julianDay(atMillis)
        val s = SunMoon.sun(jd)
        val h = AstroMath.toHorizontal(
            AstroMath.equatorialToVec(s.raDeg, s.decDeg),
            AstroMath.enuBasis(AstroMath.lstDegrees(jd, observer.lonDeg), observer.latDeg),
        )
        return h.altitudeDeg < OBSERVER_DARK_SUN_ALT
    }
}
