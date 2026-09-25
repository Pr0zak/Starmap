package com.starmap.app

import com.starmap.app.astro.AstroMath
import com.starmap.app.sky.RiseSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class RiseSetTest {
    private val now = 1_790_294_400_000L // 2026-09-25 00:00 UTC

    @Test
    fun equatorialStarAtEquatorIsUpAboutHalfTheDay() {
        val t = RiseSet.forFixed(120.0, 0.0, 0.0, 0.0, now)
        val rise = assertNotNullL(t.riseMillis)
        val set = assertNotNullL(t.setMillis)
        // Up-time is 12 sidereal hours plus a few minutes of refraction.
        val up = if (set > rise) set - rise else set + 86_164_091L - rise
        assertTrue("up for ${up / 60000} min", up in 43_000_000L..44_000_000L)
        assertEquals(90.0, t.transitAltDeg, 1e-9)
    }

    @Test
    fun transitHappensWhenLocalSiderealTimeEqualsRa() {
        val ra = 45.0
        val t = RiseSet.forFixed(ra, 20.0, 40.0, -105.27, now)
        val lstAtTransit = AstroMath.lstDegrees(AstroMath.julianDay(t.transitMillis), -105.27)
        assertTrue(abs(AstroMath.norm360(lstAtTransit - ra + 180.0) - 180.0) < 0.05)
        assertTrue(t.transitMillis >= now && t.transitMillis < now + 86_164_091L)
    }

    @Test
    fun polarAndSouthernStarsFromBoulder() {
        assertTrue(RiseSet.forFixed(37.95, 89.26, 40.02, -105.27, now).circumpolar) // Polaris
        assertTrue(RiseSet.forFixed(95.99, -52.7, 40.02, -105.27, now).neverRises) // Canopus
        assertEquals("Doesn't rise from here",
            RiseSet.describe(RiseSet.forFixed(95.99, -52.7, 40.02, -105.27, now), Locale.US))
    }

    @Test
    fun altAzRoundTripsThroughRaDec() {
        val lst = 123.4
        val (ra, dec) = RiseSet.raDecFromAltAz(30.0, 200.0, 40.0, lst)
        val h = AstroMath.toHorizontal(AstroMath.equatorialToVec(ra, dec), AstroMath.enuBasis(lst, 40.0))
        assertEquals(30.0, h.altitudeDeg, 1e-6)
        assertEquals(200.0, h.azimuthDeg, 1e-6)
    }

    @Test
    fun moonEventsAreInTheFutureAndOrdered() {
        val t = RiseSet.forMoon(40.02, -105.27, now)
        val rise = assertNotNullL(t.riseMillis)
        val set = assertNotNullL(t.setMillis)
        assertTrue(rise > now && set > now)
        assertTrue(rise < now + 26 * 3_600_000L && set < now + 26 * 3_600_000L)
    }

    private fun assertNotNullL(v: Long?): Long { assertNotNull(v); return v!! }
}
