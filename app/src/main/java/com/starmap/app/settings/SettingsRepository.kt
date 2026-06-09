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
    val showHorizon: Boolean = true,
    val showCardinals: Boolean = true,
    val showSun: Boolean = true,
    val showMoon: Boolean = true,
    val showPlanets: Boolean = true,
    val showAsteroids: Boolean = false,
    val showAsteroidPaths: Boolean = false,
    val showIss: Boolean = true,
    val showStarlink: Boolean = false,
    val showAircraft: Boolean = false,
    val showAircraftTrails: Boolean = true,
    val showAircraftLabels: Boolean = true,
    val aircraftRangeNm: Float = 120f,
    val showBelowHorizon: Boolean = false,
    val nightMode: Boolean = false,
    /** 0 = follow system auto-rotate, 1 = portrait, 2 = landscape. */
    val orientationMode: Int = 0,
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
        val showHorizon = booleanPreferencesKey("show_horizon")
        val showCardinals = booleanPreferencesKey("show_cardinals")
        val showSun = booleanPreferencesKey("show_sun")
        val showMoon = booleanPreferencesKey("show_moon")
        val showPlanets = booleanPreferencesKey("show_planets")
        val showAsteroids = booleanPreferencesKey("show_asteroids")
        val showAsteroidPaths = booleanPreferencesKey("show_asteroid_paths")
        val showIss = booleanPreferencesKey("show_iss")
        val showStarlink = booleanPreferencesKey("show_starlink")
        val showAircraft = booleanPreferencesKey("show_aircraft")
        val showAircraftTrails = booleanPreferencesKey("show_aircraft_trails")
        val showAircraftLabels = booleanPreferencesKey("show_aircraft_labels")
        val aircraftRangeNm = floatPreferencesKey("aircraft_range_nm")
        val showBelowHorizon = booleanPreferencesKey("show_below_horizon")
        val nightMode = booleanPreferencesKey("night_mode")
        val orientationMode = intPreferencesKey("orientation_mode")
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
            showHorizon = p[Keys.showHorizon] ?: true,
            showCardinals = p[Keys.showCardinals] ?: true,
            showSun = p[Keys.showSun] ?: true,
            showMoon = p[Keys.showMoon] ?: true,
            showPlanets = p[Keys.showPlanets] ?: true,
            showAsteroids = p[Keys.showAsteroids] ?: false,
            showAsteroidPaths = p[Keys.showAsteroidPaths] ?: false,
            showIss = p[Keys.showIss] ?: true,
            showStarlink = p[Keys.showStarlink] ?: false,
            showAircraft = p[Keys.showAircraft] ?: false,
            showAircraftTrails = p[Keys.showAircraftTrails] ?: true,
            showAircraftLabels = p[Keys.showAircraftLabels] ?: true,
            aircraftRangeNm = p[Keys.aircraftRangeNm] ?: 120f,
            showBelowHorizon = p[Keys.showBelowHorizon] ?: false,
            nightMode = p[Keys.nightMode] ?: false,
            orientationMode = p[Keys.orientationMode] ?: 0,
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
        Horizon(Keys.showHorizon),
        Cardinals(Keys.showCardinals),
        Sun(Keys.showSun),
        Moon(Keys.showMoon),
        Planets(Keys.showPlanets),
        Asteroids(Keys.showAsteroids),
        AsteroidPaths(Keys.showAsteroidPaths),
        Iss(Keys.showIss),
        Starlink(Keys.showStarlink),
        Aircraft(Keys.showAircraft),
        AircraftTrails(Keys.showAircraftTrails),
        AircraftLabels(Keys.showAircraftLabels),
        BelowHorizon(Keys.showBelowHorizon),
        NightMode(Keys.nightMode),
        ExtendedCatalog(Keys.useExtendedCatalog),
        AutoCheckUpdates(Keys.autoCheckUpdates),
    }
}
