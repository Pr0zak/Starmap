package com.starmap.app

import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.positionInto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the aircraft dead-reckoning between ADS-B fixes. */
class AircraftDeadReckoningTest {

    private fun craft(
        enu: FloatArray, rangeKm: Double, groundSpeedKts: Double, trackDeg: Double, updatedAtMillis: Long,
    ) = AircraftRender(
        enu = enu, icaoHex = "abc123", callsign = "TST1", isHelicopter = false, typeCode = "B738",
        altitudeMeters = 10000.0, groundSpeedKts = groundSpeedKts, trackDeg = trackDeg, rangeKm = rangeKm,
        registration = "N1", verticalRateFpm = 0.0, squawk = "1200", isEmergency = false, emergencyText = "",
        trail = FloatArray(0), updatedAtMillis = updatedAtMillis,
    )

    @Test
    fun advancesEastAlongTrack() {
        // Unit ENU due north, scaled by range 10 km; flying due east at 360 kt for 10 s.
        val ac = craft(floatArrayOf(0f, 1f, 0f), rangeKm = 10.0, groundSpeedKts = 360.0, trackDeg = 90.0, updatedAtMillis = 0L)
        val out = FloatArray(3)
        ac.positionInto(10_000L, out)
        // 360 kt = 0.1852 km/s → 1.852 km east in 10 s.
        assertEquals(1.852f, out[0], 0.01f)
        assertEquals(10.0f, out[1], 0.01f)
        assertEquals(0.0f, out[2], 0.001f)
    }

    @Test
    fun zeroAgeIsJustScaledPosition() {
        val ac = craft(floatArrayOf(0.6f, 0.8f, 0.1f), rangeKm = 5.0, groundSpeedKts = 450.0, trackDeg = 270.0, updatedAtMillis = 1000L)
        val out = FloatArray(3)
        ac.positionInto(1000L, out)
        assertEquals(3.0f, out[0], 0.001f) // 0.6 * 5
        assertEquals(4.0f, out[1], 0.001f) // 0.8 * 5
        assertEquals(0.5f, out[2], 0.001f) // 0.1 * 5
    }

    @Test
    fun ageIsClampedToThirtySeconds() {
        val ac = craft(floatArrayOf(0f, 1f, 0f), rangeKm = 10.0, groundSpeedKts = 360.0, trackDeg = 90.0, updatedAtMillis = 0L)
        val far = FloatArray(3); ac.positionInto(10_000_000L, far) // ~2.8 h later
        val cap = FloatArray(3); ac.positionInto(30_000L, cap)     // exactly 30 s
        // Both clamp to 30 s of travel, so they match.
        assertEquals(cap[0], far[0], 0.001f)
        assertEquals(cap[1], far[1], 0.001f)
    }
}
