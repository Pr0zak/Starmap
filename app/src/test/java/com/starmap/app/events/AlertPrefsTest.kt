package com.starmap.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This app has a standing trap where a settings default is written in two places — the
 * data class and the storage read's fallback — and the two silently drift, so a value
 * differs depending on whether the key has ever been written. The alert block routes
 * both through [AlertDefaults]; this pins that they actually agree.
 */
class AlertPrefsTest {

    @Test
    fun everyDefaultComesFromTheSharedConstants() {
        val p = AlertPrefs()
        assertEquals(AlertDefaults.ENABLED, p.enabled)
        assertEquals(AlertDefaults.LEAD_DAYS, p.leadDays)
        assertEquals(AlertDefaults.QUIET_ENABLED, p.quietEnabled)
        assertEquals(AlertDefaults.QUIET_START_HOUR, p.quietStartHour)
        assertEquals(AlertDefaults.QUIET_END_HOUR, p.quietEndHour)
        assertEquals(AlertDefaults.QUIET_ALLOW_ECLIPSES, p.quietAllowEclipses)
        assertEquals(AlertDefaults.MIN_ALTITUDE_DEG, p.minAltitudeDeg, 0f)
        assertEquals(AlertDefaults.METEOR_MIN_ZHR, p.meteorMinZhr)
        assertEquals(AlertDefaults.CONJUNCTION_MAX_SEP_DEG, p.conjunctionMaxSepDeg, 0f)
        assertEquals(AlertDefaults.DARK_SKY_MAX_MOON, p.darkSkyMaxMoon, 0f)
        assertEquals(AlertDefaults.ISS_MIN_PEAK_ALT_DEG, p.issMinPeakAltDeg, 0f)
        assertEquals(AlertDefaults.MOONLIGHT, p.moonlight)
        assertEquals(AlertDefaults.MAX_PER_DAY, p.maxPerDay)
    }

    @Test
    fun anUnknownTypeFallsBackToItsOwnDeclaredDefault() {
        // A release that adds a type must ship it at the default the enum declares, not
        // silently off — which is exactly what a stored set of enabled ids would do.
        val partial = AlertPrefs(enabled = true, types = mapOf(AlertType.Eclipse to false))
        for (type in AlertType.entries) {
            if (type == AlertType.Eclipse) {
                assertFalse(partial[type])
            } else {
                assertEquals("${type.id} should fall back to its declared default", type.onByDefault, partial[type])
            }
        }
    }

    @Test
    fun theMasterSwitchGatesEveryType() {
        val off = AlertPrefs(enabled = false, types = AlertType.entries.associateWith { true })
        for (type in AlertType.entries) assertFalse(off.on(type))
    }

    @Test
    fun everyTypeHasCopyAndAStableId() {
        val ids = AlertType.entries.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        for (type in AlertType.entries) {
            assertTrue("${type.name} has no label", type.label.isNotBlank())
            assertTrue("${type.name} has no blurb", type.blurb.isNotBlank())
            assertTrue("${type.name} id is not storage-safe", type.id.matches(Regex("^[a-z_]+$")))
        }
    }

    @Test
    fun everyEventKindMapsToATypeTheUserCanSwitchOff() {
        for (kind in SkyEventKind.entries) {
            assertTrue(kind.alertType in AlertType.entries)
        }
        // And every type is reachable by at least one kind, so no switch is inert.
        val covered = SkyEventKind.entries.map { it.alertType }.toSet()
        for (type in AlertType.entries) {
            assertTrue("${type.id} has no events that can trigger it", type in covered)
        }
    }
}
