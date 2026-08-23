package com.starmap.app.events

/**
 * Every default lives here exactly once, and is referenced both by [AlertPrefs]'s
 * constructor defaults and by the DataStore read fallback. The settings layer of this
 * app has a standing trap where a default is written in two places and the two drift;
 * routing both through these constants makes that impossible for the alert block.
 */
object AlertDefaults {
    const val ENABLED = false
    const val LEAD_DAYS = 1
    const val QUIET_ENABLED = true
    const val QUIET_START_HOUR = 23
    const val QUIET_END_HOUR = 7
    const val QUIET_ALLOW_ECLIPSES = true
    const val MIN_ALTITUDE_DEG = 15f
    const val METEOR_MIN_ZHR = 15
    const val CONJUNCTION_MAX_SEP_DEG = 3.0f
    const val DARK_SKY_MAX_MOON = 0.25f
    const val ISS_MIN_PEAK_ALT_DEG = 30f
    const val MOONLIGHT = 1
    const val MAX_PER_DAY = 2
}

/** Everything the user can tune about sky alerts. */
data class AlertPrefs(
    val enabled: Boolean = AlertDefaults.ENABLED,
    val types: Map<AlertType, Boolean> = AlertType.entries.associateWith { it.onByDefault },
    /** How many nights ahead of a big night to give the heads-up. */
    val leadDays: Int = AlertDefaults.LEAD_DAYS,
    val quietEnabled: Boolean = AlertDefaults.QUIET_ENABLED,
    val quietStartHour: Int = AlertDefaults.QUIET_START_HOUR,
    val quietEndHour: Int = AlertDefaults.QUIET_END_HOUR,
    val quietAllowEclipses: Boolean = AlertDefaults.QUIET_ALLOW_ECLIPSES,
    val minAltitudeDeg: Float = AlertDefaults.MIN_ALTITUDE_DEG,
    val meteorMinZhr: Int = AlertDefaults.METEOR_MIN_ZHR,
    val conjunctionMaxSepDeg: Float = AlertDefaults.CONJUNCTION_MAX_SEP_DEG,
    val darkSkyMaxMoon: Float = AlertDefaults.DARK_SKY_MAX_MOON,
    val issMinPeakAltDeg: Float = AlertDefaults.ISS_MIN_PEAK_ALT_DEG,
    /** 0 = ignore the Moon, 1 = mention it in the text, 2 = skip the alert entirely. */
    val moonlight: Int = AlertDefaults.MOONLIGHT,
    val maxPerDay: Int = AlertDefaults.MAX_PER_DAY,
) {
    operator fun get(t: AlertType): Boolean = types[t] ?: t.onByDefault

    /** True when alerts are on as a whole AND this type is on. */
    fun on(t: AlertType): Boolean = enabled && get(t)

    val anyTypeOn: Boolean get() = AlertType.entries.any { get(it) }

    companion object {
        const val MOONLIGHT_IGNORE = 0
        const val MOONLIGHT_MENTION = 1
        const val MOONLIGHT_SKIP = 2
    }
}

/**
 * Calibration constants — conventions and judgement calls rather than physics, pulled
 * out so tests can vary them and so no magic number hides in the calculator.
 */
data class EventConfig(
    /** How strongly moonlight is assumed to suppress meteor counts. A convention. */
    val moonlightCoefficient: Double = 0.8,
    /** Below this the radiant is too low for the shower to be worth mentioning. */
    val minRadiantAltDeg: Double = 20.0,
    /** A night with less true darkness than this is not worth an alert. */
    val minDarknessMinutes: Long = 45,
    /** A pairing too close to the Sun is unobservable however close together it is. */
    val conjunctionMinElongationDeg: Double = 15.0,
    /** Full moon nearer than this (Earth radii) gets called a supermoon. */
    val supermoonMaxDistanceRe: Double = 57.0,
    val twilightDepthDeg: Double = -18.0,
    /** Fallback for high summer latitudes where the Sun never reaches -18. */
    val fallbackTwilightDepthDeg: Double = -12.0,
)
