package com.starmap.app

import com.starmap.app.aircraft.RadarKind
import com.starmap.app.sky.RadarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RadarMathTest {
    @Test
    fun planeFlyingEastPassesNorthOfUs() {
        // 10 km west, 2 km north, heading due east at 300 kt (9.26 km/min).
        val a = RadarMath.closestApproach(-10f, 2f, 300.0, 90.0)
        assertNotNull(a)
        assertEquals(10f / 9.26f, a!!.minutes, 0.01f)
        assertEquals(2f, a.distanceKm, 0.01f)
        assertEquals(0f, a.eastKm, 0.01f)
    }

    @Test
    fun planeFlyingAwayHasNoApproach() {
        assertNull(RadarMath.closestApproach(10f, 0f, 300.0, 90.0))
    }

    @Test
    fun hoveringOrFarOffApproachIsIgnored() {
        assertNull(RadarMath.closestApproach(-10f, 0f, 5.0, 90.0))
        assertNull(RadarMath.closestApproach(-500f, 0f, 300.0, 90.0)) // ~54 min away
    }

    @Test
    fun lookAnglesFromUnitVector() {
        val (el, az) = RadarMath.lookAngles(floatArrayOf(0.5f, 0.5f, 0.7071f))
        assertEquals(45f, el, 0.1f)
        assertEquals(45f, az, 0.1f)
    }

    @Test
    fun routeProgressHalfwayAndEta() {
        // Along the equator from 0° to 2° longitude, now at 1°: halfway, ~111 km left.
        val p = RadarMath.routeProgress(0.0, 0.0, 0.0, 2.0, 0.0, 1.0, 120.0, 0L)
        assertNotNull(p)
        assertEquals(0.5f, p!!.fraction, 0.01f)
        assertEquals(111.2, p.remainingKm, 0.5)
        assertEquals(111.2 / (120 * 1.852) * 3_600_000, p.etaMillis!!.toDouble(), 60_000.0)
    }

    @Test
    fun unitsFormat() {
        assertEquals("5.4 nm", RadarMath.Unit.NauticalMiles.format(10.0))
        assertEquals("62 mi", RadarMath.Unit.Miles.format(100.0))
        assertEquals(RadarMath.Unit.Kilometres, RadarMath.Unit.of(2))
    }

    @Test
    fun kindsFromCategoryAndFlags() {
        assertEquals(RadarKind.MILITARY, RadarKind.classify("A5", 1, false, 400.0, 30000.0))
        assertEquals(RadarKind.HELICOPTER, RadarKind.classify("A7", 0, true, 100.0, 1500.0))
        assertEquals(RadarKind.AIRLINER, RadarKind.classify("A3", 0, false, 450.0, 36000.0))
        assertEquals(RadarKind.LIGHT, RadarKind.classify("A1", 0, false, 110.0, 6000.0))
        assertEquals(RadarKind.LIGHT, RadarKind.classify("", 0, false, 110.0, 6000.0))
        assertEquals(RadarKind.OTHER, RadarKind.classify("B1", 0, false, 60.0, 8000.0))
    }
}
