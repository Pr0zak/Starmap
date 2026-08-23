package com.starmap.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * The planner is what stands between a working feature and one that gets muted: it
 * decides what is actually sent, how often, and — above all — that nothing is ever sent
 * twice.
 */
class AlertPlannerTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val prefs = AlertPrefs(enabled = true, types = AlertType.entries.associateWith { true })

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        id: String,
        deliverIso: String,
        priority: Int = 50,
        kind: SkyEventKind = SkyEventKind.MeteorPeak,
        validUntilIso: String = "2030-01-01T00:00:00Z",
    ) = SkyEvent(
        id = id,
        kind = kind,
        subject = id,
        peakMillis = ms(deliverIso) + 3_600_000L,
        windowStartMillis = ms(deliverIso) + 3_600_000L,
        windowEndMillis = ms(deliverIso) + 7_200_000L,
        deliverAtMillis = ms(deliverIso),
        validUntilMillis = ms(validUntilIso),
        priority = priority,
        title = id,
        body = id,
        confidence = Confidence.Firm,
        requiresLocation = false,
    )

    @Test
    fun anEventAlreadyAnnouncedIsNeverAnnouncedAgain() {
        val events = listOf(event("a", "2026-08-11T22:00:00Z"), event("b", "2026-08-12T22:00:00Z"))
        val planned = AlertPlanner.plan(events, prefs, zone, ms("2026-08-01T00:00:00Z"), setOf("a"))
        assertEquals(listOf("b"), planned.map { it.event.id })
    }

    @Test
    fun replanningAfterEverythingHasFiredProducesNothing() {
        val events = listOf(event("a", "2026-08-11T22:00:00Z"), event("b", "2026-08-12T22:00:00Z"))
        val fired = events.map { it.id }.toSet()
        assertTrue(AlertPlanner.plan(events, prefs, zone, ms("2026-08-01T00:00:00Z"), fired).isEmpty())
    }

    @Test
    fun theDailyCapKeepsTheMostNotableAndIsDeterministic() {
        // Five things on one evening, cap of two: the two highest priorities survive,
        // and the same two survive on every run.
        val sameEvening = listOf(
            event("low", "2026-08-11T22:00:00Z", priority = 10),
            event("mid", "2026-08-11T22:10:00Z", priority = 50),
            event("high", "2026-08-11T22:20:00Z", priority = 95),
            event("alsoHigh", "2026-08-11T22:30:00Z", priority = 95),
            event("lowest", "2026-08-11T22:40:00Z", priority = 5),
        )
        val results = (1..5).map {
            AlertPlanner.plan(sameEvening, prefs.copy(maxPerDay = 2), zone, ms("2026-08-01T00:00:00Z"), emptySet())
                .map { p -> p.event.id }
        }
        assertEquals("must be stable across runs", 1, results.toSet().size)
        assertEquals(setOf("alsoHigh", "high"), results.first().toSet())
    }

    @Test
    fun theCapCountsLocalDaysNotUtcDays() {
        // At UTC+14 the local evening of the 12th is the UTC morning of the 12th; at
        // UTC-11 it is the UTC morning of the 13th. Grouping on UTC would put these in
        // one bucket and silently drop one.
        val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14
        val events = listOf(
            event("n1", "2026-08-11T08:00:00Z", priority = 90),
            event("n2", "2026-08-12T08:00:00Z", priority = 90),
        )
        val planned = AlertPlanner.plan(events, prefs.copy(maxPerDay = 1), kiritimati, ms("2026-08-01T00:00:00Z"), emptySet())
        assertEquals("two separate local nights, both should survive a cap of one", 2, planned.size)
    }

    @Test
    fun somethingThatHasAlreadyPassedIsDropped() {
        val stale = event("old", "2026-08-01T22:00:00Z", validUntilIso = "2026-08-02T06:00:00Z")
        val planned = AlertPlanner.plan(listOf(stale), prefs, zone, ms("2026-08-10T00:00:00Z"), emptySet())
        assertTrue("an expired event must not be announced late", planned.isEmpty())
    }

    @Test
    fun quietHoursDropRatherThanDefer() {
        // 03:00 local is inside the default 23:00-07:00 window, and the whole preceding
        // evening is already in the past, so there is nowhere earlier to move it to.
        val nightOwl = event("late", "2026-08-12T07:00:00Z") // 03:00 EDT
        val planned = AlertPlanner.plan(
            listOf(nightOwl), prefs, zone, ms("2026-08-12T06:00:00Z"), emptySet(),
        )
        assertTrue("must not be saved for the morning, when it would be false", planned.isEmpty())
    }

    @Test
    fun anEclipseIsLetThroughQuietHoursWhenAllowed() {
        val eclipse = event("ecl", "2026-08-12T07:00:00Z", kind = SkyEventKind.LunarEclipse)
        val allowed = AlertPlanner.plan(
            listOf(eclipse), prefs.copy(quietAllowEclipses = true), zone, ms("2026-08-12T06:00:00Z"), emptySet(),
        )
        assertEquals(1, allowed.size)
        val blocked = AlertPlanner.plan(
            listOf(eclipse), prefs.copy(quietAllowEclipses = false), zone, ms("2026-08-12T06:00:00Z"), emptySet(),
        )
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun aLateDuskIsPulledForwardRatherThanLost() {
        // The high-latitude summer case: it does not get dark until well after the quiet
        // window opens. Firing a little early beats not firing at all.
        val lateDusk = event("midnightsun", "2026-06-21T02:00:00Z") // 22:00 EDT is fine; use UTC zone
        val utc = ZoneId.of("UTC")
        val planned = AlertPlanner.plan(
            listOf(lateDusk), prefs, utc, ms("2026-06-20T00:00:00Z"), emptySet(),
        )
        assertEquals(1, planned.size)
        assertTrue(
            "should have been moved out of the quiet window",
            !QuietHours.inQuietHours(planned.first().deliverAtMillis, 23, 7, utc),
        )
        assertTrue(planned.first().deliverAtMillis < lateDusk.deliverAtMillis)
    }

    @Test
    fun turningATypeOffRemovesItFromThePlan() {
        val events = listOf(event("m", "2026-08-11T22:00:00Z", kind = SkyEventKind.MeteorPeak))
        val off = prefs.copy(types = prefs.types + (AlertType.MeteorPeak to false))
        assertTrue(AlertPlanner.plan(events, off, zone, ms("2026-08-01T00:00:00Z"), emptySet()).isEmpty())
    }

    @Test
    fun nothingIsPlannedWhileAlertsAreSwitchedOff() {
        val events = listOf(event("m", "2026-08-11T22:00:00Z"))
        assertTrue(
            AlertPlanner.plan(events, prefs.copy(enabled = false), zone, ms("2026-08-01T00:00:00Z"), emptySet())
                .isEmpty(),
        )
    }

    @Test
    fun theResultDoesNotDependOnTheDeviceTimeZone() {
        val events = listOf(
            event("a", "2026-08-11T22:00:00Z", priority = 90),
            event("b", "2026-08-12T22:00:00Z", priority = 20),
        )
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val a = AlertPlanner.plan(events, prefs, zone, ms("2026-08-01T00:00:00Z"), emptySet())
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val b = AlertPlanner.plan(events, prefs, zone, ms("2026-08-01T00:00:00Z"), emptySet())
            assertEquals("the planner must use the observer's zone, not the device's", a, b)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
