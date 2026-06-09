package com.starmap.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All user-tunable options for the sky view. */
data class Settings(
    val magnitudeLimit: Float = 6.0f,
    val labelMagnitudeLimit: Float = 2.5f,
    val fovDeg: Float = 55f,
    val showStarLabels: Boolean = true,
    val showConstellations: Boolean = true,
    val showConstellationNames: Boolean = true,
    val showConstellationArt: Boolean = false,
    val showMeteorShowers: Boolean = false,
    val showMessier: Boolean = false,
    val showMilkyWay: Boolean = false,
    val showHorizon: Boolean = true,
    val showCardinals: Boolean = true,
    val showEcliptic: Boolean = false,
    val showEquator: Boolean = false,
    val showGrid: Boolean = false,
    val showSun: Boolean = true,
    val showMoon: Boolean = true,
    val showPlanets: Boolean = true,
    val showAsteroids: Boolean = false,
    val showAsteroidPaths: Boolean = false,
    val showComets: Boolean = false,
    val showCometPaths: Boolean = false,
    val showIss: Boolean = true,
    val showStarlink: Boolean = false,
    val showAircraft: Boolean = false,
    val showAircraftTrails: Boolean = true,
    val showAircraftLabels: Boolean = true,
    val aircraftRangeNm: Float = 40f,
    val showBelowHorizon: Boolean = false,
    val showDaylightSky: Boolean = true,
    val applyRefraction: Boolean = true,
    val centerIdentify: Boolean = true,
    val arMode: Boolean = false,
    val nightMode: Boolean = false,
    /** 0 = follow system auto-rotate, 1 = portrait, 2 = landscape. */
    val orientationMode: Int = 0,
    /** Field-of-view guide rings: 0 = off, 1 = Telrad, 2 = binoculars, 3 = 1° eyepiece. */
    val fovCirclesMode: Int = 0,
    val useExtendedCatalog: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val manualLocation: Boolean = false,
    val manualLat: Double = 0.0,
    val manualLon: Double = 0.0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "starmap_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val magnitudeLimit = floatPreferencesKey("magnitude_limit")
        val labelMagnitudeLimit = floatPreferencesKey("label_magnitude_limit")
        val fovDeg = floatPreferencesKey("fov_deg")
        val showStarLabels = booleanPreferencesKey("show_star_labels")
        val showConstellations = booleanPreferencesKey("show_constellations")
        val showConstellationNames = booleanPreferencesKey("show_constellation_names")
        val showConstellationArt = booleanPreferencesKey("show_constellation_art")
        val showMeteorShowers = booleanPreferencesKey("show_meteor_showers")
        val showMessier = booleanPreferencesKey("show_messier")
        val showMilkyWay = booleanPreferencesKey("show_milky_way")
        val showHorizon = booleanPreferencesKey("show_horizon")
        val showCardinals = booleanPreferencesKey("show_cardinals")
        val showEcliptic = booleanPreferencesKey("show_ecliptic")
        val showEquator = booleanPreferencesKey("show_equator")
        val showGrid = booleanPreferencesKey("show_grid")
        val showSun = booleanPreferencesKey("show_sun")
        val showMoon = booleanPreferencesKey("show_moon")
        val showPlanets = booleanPreferencesKey("show_planets")
        val showAsteroids = booleanPreferencesKey("show_asteroids")
        val showAsteroidPaths = booleanPreferencesKey("show_asteroid_paths")
        val showComets = booleanPreferencesKey("show_comets")
        val showCometPaths = booleanPreferencesKey("show_comet_paths")
        val showIss = booleanPreferencesKey("show_iss")
        val showStarlink = booleanPreferencesKey("show_starlink")
        val showAircraft = booleanPreferencesKey("show_aircraft")
        val showAircraftTrails = booleanPreferencesKey("show_aircraft_trails")
        val showAircraftLabels = booleanPreferencesKey("show_aircraft_labels")
        val aircraftRangeNm = floatPreferencesKey("aircraft_range_nm")
        val showBelowHorizon = booleanPreferencesKey("show_below_horizon")
        val showDaylightSky = booleanPreferencesKey("show_daylight_sky")
        val applyRefraction = booleanPreferencesKey("apply_refraction")
        val centerIdentify = booleanPreferencesKey("center_identify")
        val arMode = booleanPreferencesKey("ar_mode")
        val nightMode = booleanPreferencesKey("night_mode")
        val orientationMode = intPreferencesKey("orientation_mode")
        val fovCirclesMode = intPreferencesKey("fov_circles_mode")
        val useExtendedCatalog = booleanPreferencesKey("use_extended_catalog")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val manualLocation = booleanPreferencesKey("manual_location")
        val manualLat = doublePreferencesKey("manual_lat")
        val manualLon = doublePreferencesKey("manual_lon")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            magnitudeLimit = p[Keys.magnitudeLimit] ?: 6.0f,
            labelMagnitudeLimit = p[Keys.labelMagnitudeLimit] ?: 2.5f,
            fovDeg = p[Keys.fovDeg] ?: 55f,
            showStarLabels = p[Keys.showStarLabels] ?: true,
            showConstellations = p[Keys.showConstellations] ?: true,
            showConstellationNames = p[Keys.showConstellationNames] ?: true,
            showConstellationArt = p[Keys.showConstellationArt] ?: false,
            showMeteorShowers = p[Keys.showMeteorShowers] ?: false,
            showMessier = p[Keys.showMessier] ?: false,
            showMilkyWay = p[Keys.showMilkyWay] ?: false,
            showHorizon = p[Keys.showHorizon] ?: true,
            showCardinals = p[Keys.showCardinals] ?: true,
            showEcliptic = p[Keys.showEcliptic] ?: false,
            showEquator = p[Keys.showEquator] ?: false,
            showGrid = p[Keys.showGrid] ?: false,
            showSun = p[Keys.showSun] ?: true,
            showMoon = p[Keys.showMoon] ?: true,
            showPlanets = p[Keys.showPlanets] ?: true,
            showAsteroids = p[Keys.showAsteroids] ?: false,
            showAsteroidPaths = p[Keys.showAsteroidPaths] ?: false,
            showComets = p[Keys.showComets] ?: false,
            showCometPaths = p[Keys.showCometPaths] ?: false,
            showIss = p[Keys.showIss] ?: true,
            showStarlink = p[Keys.showStarlink] ?: false,
            showAircraft = p[Keys.showAircraft] ?: false,
            showAircraftTrails = p[Keys.showAircraftTrails] ?: true,
            showAircraftLabels = p[Keys.showAircraftLabels] ?: true,
            aircraftRangeNm = (p[Keys.aircraftRangeNm] ?: 40f).coerceAtMost(80f),
            showBelowHorizon = p[Keys.showBelowHorizon] ?: false,
            showDaylightSky = p[Keys.showDaylightSky] ?: true,
            applyRefraction = p[Keys.applyRefraction] ?: true,
            centerIdentify = p[Keys.centerIdentify] ?: true,
            arMode = p[Keys.arMode] ?: false,
            nightMode = p[Keys.nightMode] ?: false,
            orientationMode = p[Keys.orientationMode] ?: 0,
            fovCirclesMode = p[Keys.fovCirclesMode] ?: 0,
            useExtendedCatalog = p[Keys.useExtendedCatalog] ?: true,
            autoCheckUpdates = p[Keys.autoCheckUpdates] ?: true,
            manualLocation = p[Keys.manualLocation] ?: false,
            manualLat = p[Keys.manualLat] ?: 0.0,
            manualLon = p[Keys.manualLon] ?: 0.0,
        )
    }

    suspend fun setFloat(selector: FloatSetting, value: Float) =
        context.dataStore.edit { it[selector.key] = value }

    suspend fun setBool(selector: BoolSetting, value: Boolean) =
        context.dataStore.edit { it[selector.key] = value }

    suspend fun setManualLocation(enabled: Boolean, lat: Double, lon: Double) =
        context.dataStore.edit {
            it[Keys.manualLocation] = enabled
            it[Keys.manualLat] = lat
            it[Keys.manualLon] = lon
        }

    suspend fun setOrientation(mode: Int) =
        context.dataStore.edit { it[Keys.orientationMode] = mode }

    suspend fun setFovCircles(mode: Int) =
        context.dataStore.edit { it[Keys.fovCirclesMode] = mode }

    enum class FloatSetting(val key: Preferences.Key<Float>) {
        MagnitudeLimit(Keys.magnitudeLimit),
        LabelMagnitudeLimit(Keys.labelMagnitudeLimit),
        Fov(Keys.fovDeg),
        AircraftRange(Keys.aircraftRangeNm),
    }

    enum class BoolSetting(val key: Preferences.Key<Boolean>) {
        StarLabels(Keys.showStarLabels),
        Constellations(Keys.showConstellations),
        ConstellationNames(Keys.showConstellationNames),
        ConstellationArt(Keys.showConstellationArt),
        MeteorShowers(Keys.showMeteorShowers),
        Messier(Keys.showMessier),
        MilkyWay(Keys.showMilkyWay),
        Horizon(Keys.showHorizon),
        Cardinals(Keys.showCardinals),
        Ecliptic(Keys.showEcliptic),
        Equator(Keys.showEquator),
        Grid(Keys.showGrid),
        Sun(Keys.showSun),
        Moon(Keys.showMoon),
        Planets(Keys.showPlanets),
        Asteroids(Keys.showAsteroids),
        AsteroidPaths(Keys.showAsteroidPaths),
        Comets(Keys.showComets),
        CometPaths(Keys.showCometPaths),
        Iss(Keys.showIss),
        Starlink(Keys.showStarlink),
        Aircraft(Keys.showAircraft),
        AircraftTrails(Keys.showAircraftTrails),
        AircraftLabels(Keys.showAircraftLabels),
        BelowHorizon(Keys.showBelowHorizon),
        DaylightSky(Keys.showDaylightSky),
        Refraction(Keys.applyRefraction),
        CenterIdentify(Keys.centerIdentify),
        ArMode(Keys.arMode),
        NightMode(Keys.nightMode),
        ExtendedCatalog(Keys.useExtendedCatalog),
        AutoCheckUpdates(Keys.autoCheckUpdates),
    }
}
