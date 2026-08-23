package com.starmap.app.events

import java.time.LocalDate
import java.time.ZoneId

/** An event plus the moment it should actually be announced. */
data class PlannedAlert(val event: SkyEvent, val deliverAtMillis: Long)

/**
 * Turns a raw list of events into the short list actually worth sending: drops what has
 * already been said or has gone stale, respects quiet hours, and caps how much lands in
 * any one day.
 */
object AlertPlanner {

    fun plan(
        events: List<SkyEvent>,
        prefs: AlertPrefs,
        zone: ZoneId,
        nowMillis: Long,
        alreadyNotifiedIds: Set<String>,
    ): List<PlannedAlert> {
        if (!prefs.enabled) return emptyList()

        val candidates = ArrayList<PlannedAlert>()
        for (e in events) {
            if (e.id in alreadyNotifiedIds) continue
            if (!prefs.on(e.kind.alertType)) continue
            if (e.validUntilMillis < nowMillis) continue

            var deliverAt = maxOf(e.deliverAtMillis, nowMillis)
            if (deliverAt > e.validUntilMillis) continue

            if (prefs.quietEnabled) {
                val exempt = prefs.quietAllowEclipses &&
                    (e.kind == SkyEventKind.LunarEclipse || e.kind == SkyEventKind.SolarEclipse)
                if (!exempt) {
                    deliverAt = QuietHours.clampBefore(
                        deliverAt, nowMillis, prefs.quietStartHour, prefs.quietEndHour, zone,
                    ) ?: continue
                }
            }
            candidates.add(PlannedAlert(e, deliverAt))
        }

        // Slots this night has already spent. Something already announced keeps its
        // place in its night's quota, so a rescan the same evening cannot promote what
        // an earlier scan dropped and push the night past the cap.
        val spent = HashMap<LocalDate, Int>()
        for (e in events) {
            if (e.id !in alreadyNotifiedIds || !prefs.on(e.kind.alertType)) continue
            val night = nightKeyOf(e.deliverAtMillis, zone)
            spent[night] = (spent[night] ?: 0) + 1
        }

        // Cap per LOCAL day, keeping the most notable. Ties break on id so two runs over
        // the same data always choose the same events.
        val cap = prefs.maxPerDay.coerceAtLeast(1)
        val perDay = candidates.groupBy { nightKeyOf(it.deliverAtMillis, zone) }
        val kept = perDay.entries.flatMap { (night, day) ->
            day.sortedWith(
                compareByDescending<PlannedAlert> { it.event.priority }.thenBy { it.event.id },
            ).take((cap - (spent[night] ?: 0)).coerceAtLeast(0))
        }
        return kept.sortedWith(compareBy<PlannedAlert> { it.deliverAtMillis }.thenBy { it.event.id })
    }
}
