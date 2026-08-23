package com.starmap.app.events

import java.time.ZoneId

/**
 * Where and when "here" is, for a calculation that runs in the background with no
 * access to the sensors.
 *
 * [fixAgeMillis] drives confidence: someone who flew three time zones since their last
 * fix should be told "the Perseids peak tonight", not confidently told the radiant is
 * 62° up in the north-east, because from where they are now it isn't.
 */
data class Observer(
    val latDeg: Double,
    val lonDeg: Double,
    val elevationM: Double,
    val zone: ZoneId,
    val fixAgeMillis: Long,
) {
    val isUsable: Boolean
        get() = latDeg.isFinite() && lonDeg.isFinite() &&
            latDeg in -90.0..90.0 && lonDeg in -180.0..180.0

    /** Beyond this the location is too old to make claims about altitude and compass direction. */
    val isStale: Boolean get() = fixAgeMillis > STALE_AFTER_MILLIS

    companion object {
        const val STALE_AFTER_MILLIS = 30L * 24 * 3_600_000
    }
}
