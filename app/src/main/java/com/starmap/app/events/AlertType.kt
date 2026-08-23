package com.starmap.app.events

/**
 * The categories of sky event a user can be told about, one switch each.
 *
 * Deliberately stored as one preference key per type rather than a set of enabled
 * ids: when a later release adds a type, a stored set is the whole truth, so the new
 * id is simply absent and the feature ships silently off to everyone who already had
 * alerts on. One key per type is *absent* from the store instead, so the fallback
 * fires and the new type arrives at whatever default it declares here.
 */
enum class AlertType(
    val id: String,
    val label: String,
    val blurb: String,
    val onByDefault: Boolean,
) {
    MeteorPeak(
        "meteor_peak",
        "Meteor shower peaks",
        "The night a shower is at its best, and how many an hour to expect.",
        true,
    ),
    Eclipse(
        "eclipse",
        "Eclipses",
        "When the Moon slides into Earth's shadow, or across the face of the Sun.",
        true,
    ),
    Conjunction(
        "conjunction",
        "Close pairings",
        "When two planets — or a planet and the Moon — sit side by side.",
        true,
    ),
    PlanetEvent(
        "planet_event",
        "Planets at their best",
        "The weeks Mars, Jupiter or Mercury are easiest to find.",
        true,
    ),
    SeasonMarker(
        "season",
        "Solstice & equinox",
        "The longest night, the shortest, and the two in between.",
        true,
    ),
    IssPass(
        "iss_pass",
        "Space Station passes",
        "Evenings the ISS sails over bright enough to follow. Needs the ISS data downloaded.",
        false,
    ),
    MoonPhase(
        "moon_phase",
        "Full & new moon",
        "The brightest night of the month, and the darkest.",
        false,
    ),
    DarkSkyNight(
        "dark_sky",
        "Moonless nights",
        "Nights with no Moon in the way — the good ones for faint things.",
        false,
    ),
}
