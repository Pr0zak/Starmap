package com.starmap.app.events

import com.starmap.app.astro.MeteorShowers
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Invariants on the shower table itself. These cost nothing and catch the two traps
 * that are easy to walk into when someone adds a shower later.
 */
class MeteorShowerTableTest {

    @Test
    fun noShowerWindowWrapsTheYearBoundary() {
        // The "is it running now" check is `today in start..end`, which silently never
        // matches for a range like Dec 28 - Jan 12. If a wrapping shower is ever added,
        // that check needs restructuring first.
        for (s in MeteorShowers.all) {
            assertTrue("${s.name} wraps the year boundary (${s.start}..${s.end})", s.start <= s.end)
        }
    }

    @Test
    fun everyPeakLiesInsideItsOwnActiveWindow() {
        for (s in MeteorShowers.all) {
            assertTrue("${s.name} peaks outside its window", s.peak in s.start..s.end)
        }
    }

    @Test
    fun everyStoredDateIsARealCalendarDateInAnyYear() {
        // A peak stored as Feb 29 would throw in a non-leap year, and the surrounding
        // catch would swallow it and quietly report the wrong day.
        for (s in MeteorShowers.all) {
            for (field in listOf(s.start, s.end, s.peak)) {
                val month = field / 100
                val day = field % 100
                assertTrue("${s.name}: month $month out of range", month in 1..12)
                LocalDate.of(2027, month, day) // a non-leap year: throws if Feb 29
            }
        }
    }

    @Test
    fun everyShowerHasAUsableRate() {
        for (s in MeteorShowers.all) {
            assertTrue("${s.name} has ZHR ${s.zhr}", s.zhr > 0)
        }
    }

    @Test
    fun theTableIsNotEmptyAndNamesAreUnique() {
        assertTrue(MeteorShowers.all.size >= 10)
        assertTrue(MeteorShowers.all.map { it.name }.toSet().size == MeteorShowers.all.size)
    }
}
