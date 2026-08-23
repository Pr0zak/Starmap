package com.starmap.app.events

import java.time.Instant
import java.time.ZoneId

/**
 * The overnight window in which Starmap keeps quiet.
 *
 * Note what this deliberately does *not* do: it never saves a notification for later.
 * A sky alert describes a configuration of the sky, and the sky moves — "the Perseids
 * peak tonight" delivered at eight the next morning isn't a late notification, it's a
 * false one. So an alert that would land inside quiet hours is either moved *earlier*,
 * or dropped.
 */
object QuietHours {

    /** Windows normally wrap midnight (23 to 7), so the test has to be modular. */
    fun inQuietHours(atMillis: Long, startHour: Int, endHour: Int, zone: ZoneId): Boolean {
        if (startHour == endHour) return false // an empty window means no quiet hours
        val hour = Instant.ofEpochMilli(atMillis).atZone(zone).hour
        return if (startHour < endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }

    /**
     * The latest instant at or before [atMillis] that falls outside the window, or null
     * if pulling it earlier would push it into the past relative to [notBeforeMillis].
     *
     * This covers the one case that genuinely needs handling: a high-latitude summer
     * evening where it does not get dark until 23:30. Firing at 22:45 with "best after
     * 01:00" beats both firing at seven the next morning and saying nothing.
     */
    fun clampBefore(
        atMillis: Long,
        notBeforeMillis: Long,
        startHour: Int,
        endHour: Int,
        zone: ZoneId,
    ): Long? {
        if (!inQuietHours(atMillis, startHour, endHour, zone)) return atMillis
        val zoned = Instant.ofEpochMilli(atMillis).atZone(zone)
        // Step back to the top of the quiet window, then a further 15 minutes clear of it.
        var candidate = zoned.withMinute(0).withSecond(0).withNano(0).withHour(startHour)
        if (candidate.toInstant().toEpochMilli() > atMillis) candidate = candidate.minusDays(1)
        val shifted = candidate.toInstant().toEpochMilli() - 15 * 60_000L
        return if (shifted >= notBeforeMillis) shifted else null
    }
}
