package com.starmap.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class QuietHoursTest {

    private val ny: ZoneId = ZoneId.of("America/New_York")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun awindowThatWrapsMidnightIsRecognisedOnBothSides() {
        // The default 23:00-07:00. Boundary inclusivity is pinned deliberately.
        fun quietAt(localIso: String) =
            QuietHours.inQuietHours(ms(localIso), 23, 7, ZoneId.of("UTC"))
        assertTrue("23:30 is inside", quietAt("2026-08-12T23:30:00Z"))
        assertTrue("00:10 is inside", quietAt("2026-08-12T00:10:00Z"))
        assertTrue("06:59 is inside", quietAt("2026-08-12T06:59:00Z"))
        assertFalse("07:00 is outside", quietAt("2026-08-12T07:00:00Z"))
        assertFalse("22:59 is outside", quietAt("2026-08-12T22:59:00Z"))
    }

    @Test
    fun aWindowInsideOneDayWorksToo() {
        fun quietAt(localIso: String) = QuietHours.inQuietHours(ms(localIso), 9, 17, ZoneId.of("UTC"))
        assertTrue(quietAt("2026-08-12T09:00:00Z"))
        assertTrue(quietAt("2026-08-12T16:59:00Z"))
        assertFalse(quietAt("2026-08-12T17:00:00Z"))
        assertFalse(quietAt("2026-08-12T08:59:00Z"))
    }

    @Test
    fun equalStartAndEndMeansNoQuietHoursAtAll() {
        for (hour in 0..23) {
            assertFalse(
                QuietHours.inQuietHours(ms("2026-08-12T%02d:30:00Z".format(hour)), 0, 0, ZoneId.of("UTC")),
            )
        }
    }

    @Test
    fun anAllowedTimeIsReturnedUnchanged() {
        val at = ms("2026-08-12T22:00:00Z")
        assertEquals(at, QuietHours.clampBefore(at, at - 86_400_000L, 23, 7, ZoneId.of("UTC")))
    }

    @Test
    fun aBlockedTimeIsMovedBeforeTheWindowOpens() {
        val at = ms("2026-08-13T01:00:00Z") // inside 23:00-07:00 UTC
        val shifted = QuietHours.clampBefore(at, at - 86_400_000L, 23, 7, ZoneId.of("UTC"))!!
        assertTrue("must move earlier", shifted < at)
        assertFalse(QuietHours.inQuietHours(shifted, 23, 7, ZoneId.of("UTC")))
    }

    @Test
    fun itGivesUpRatherThanSchedulingInThePast() {
        val at = ms("2026-08-13T01:00:00Z")
        // "Now" is already past the only earlier slot, so there is nowhere to put it.
        assertNull(QuietHours.clampBefore(at, at - 60_000L, 23, 7, ZoneId.of("UTC")))
    }

    @Test
    fun theObserversZoneIsWhatCounts() {
        // 03:00 UTC is 23:00 the previous evening in New York — inside a 23-7 window
        // either way, but for different reasons. What matters is that the zone argument
        // is honoured rather than the device's.
        val at = ms("2026-08-12T03:00:00Z")
        assertTrue(QuietHours.inQuietHours(at, 23, 7, ny))
        assertFalse(QuietHours.inQuietHours(at, 4, 8, ZoneId.of("UTC")))
    }

    @Test
    fun daylightSavingTransitionsDoNotThrowOrHang() {
        // Spring forward: 02:00-03:00 does not exist locally. Fall back: 01:00-02:00
        // happens twice. Both must resolve to something, deterministically.
        for (iso in listOf("2026-03-08T06:30:00Z", "2026-11-01T05:30:00Z")) {
            val at = ms(iso)
            val first = QuietHours.clampBefore(at, at - 86_400_000L, 23, 7, ny)
            repeat(5) {
                assertEquals(first, QuietHours.clampBefore(at, at - 86_400_000L, 23, 7, ny))
            }
        }
    }
}
