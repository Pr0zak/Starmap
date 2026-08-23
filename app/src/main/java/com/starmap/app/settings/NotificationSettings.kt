package com.starmap.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.starmap.app.events.AlertDefaults
import com.starmap.app.events.AlertPrefs
import com.starmap.app.events.AlertType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Bookkeeping the alert machinery needs but the user never sets directly. */
data class NotificationState(
    val cachedLat: Double = 0.0,
    val cachedLon: Double = 0.0,
    val cachedAltM: Double = 0.0,
    val cachedAtMillis: Long = 0L,
    val lastRunMillis: Long = 0L,
    val permissionRequested: Boolean = false,
    val seenIds: Set<String> = emptySet(),
) {
    val hasLocation: Boolean get() = cachedAtMillis > 0L
}

/**
 * Alert preferences, kept in the same DataStore file as the rest of the settings but
 * behind their own flow.
 *
 * Deliberately not folded into [Settings]: the sky rebuild loop reads that whole object
 * roughly once a second, and every collector downstream re-runs when any field in it
 * changes. Dragging a "pairings closer than" slider would churn the renderer for a value
 * it never looks at.
 */
class NotificationSettingsRepository(private val context: Context) {

    private object Keys {
        val enabled = booleanPreferencesKey("notif_enabled")
        val leadDays = intPreferencesKey("notif_lead_days")
        val quietEnabled = booleanPreferencesKey("notif_quiet_enabled")
        val quietStartHour = intPreferencesKey("notif_quiet_start_hour")
        val quietEndHour = intPreferencesKey("notif_quiet_end_hour")
        val quietAllowEclipses = booleanPreferencesKey("notif_quiet_allow_eclipses")
        val minAltitudeDeg = floatPreferencesKey("notif_min_altitude_deg")
        val meteorMinZhr = intPreferencesKey("notif_meteor_min_zhr")
        val conjunctionMaxSep = floatPreferencesKey("notif_conjunction_max_sep_deg")
        val darkSkyMaxMoon = floatPreferencesKey("notif_dark_sky_max_moon")
        val issMinPeakAlt = floatPreferencesKey("notif_iss_min_peak_alt_deg")
        val moonlight = intPreferencesKey("notif_moonlight")
        val maxPerDay = intPreferencesKey("notif_max_per_day")
        val lastRunAt = longPreferencesKey("notif_last_run_at")
        val permRequested = booleanPreferencesKey("notif_perm_requested")
        val seenIds = stringSetPreferencesKey("notif_seen_ids")
        val cachedLat = doublePreferencesKey("notif_cached_lat")
        val cachedLon = doublePreferencesKey("notif_cached_lon")
        val cachedAlt = doublePreferencesKey("notif_cached_alt_m")
        val cachedAt = longPreferencesKey("notif_cached_at")
        fun type(t: AlertType) = booleanPreferencesKey("notif_on_${t.id}")
    }

    val prefs: Flow<AlertPrefs> = context.dataStore.data.map { read(it) }
    val state: Flow<NotificationState> = context.dataStore.data.map { readState(it) }

    suspend fun snapshot(): AlertPrefs = prefs.first()
    suspend fun stateSnapshot(): NotificationState = state.first()

    // Every fallback below names an AlertDefaults constant rather than repeating a
    // literal, so the value the data class starts with and the value a fresh install
    // reads back cannot drift apart.
    private fun read(p: Preferences) = AlertPrefs(
        enabled = p[Keys.enabled] ?: AlertDefaults.ENABLED,
        types = AlertType.entries.associateWith { p[Keys.type(it)] ?: it.onByDefault },
        leadDays = (p[Keys.leadDays] ?: AlertDefaults.LEAD_DAYS).coerceIn(0, 7),
        quietEnabled = p[Keys.quietEnabled] ?: AlertDefaults.QUIET_ENABLED,
        quietStartHour = (p[Keys.quietStartHour] ?: AlertDefaults.QUIET_START_HOUR).coerceIn(0, 23),
        quietEndHour = (p[Keys.quietEndHour] ?: AlertDefaults.QUIET_END_HOUR).coerceIn(0, 23),
        quietAllowEclipses = p[Keys.quietAllowEclipses] ?: AlertDefaults.QUIET_ALLOW_ECLIPSES,
        minAltitudeDeg = (p[Keys.minAltitudeDeg] ?: AlertDefaults.MIN_ALTITUDE_DEG).coerceIn(0f, 60f),
        meteorMinZhr = (p[Keys.meteorMinZhr] ?: AlertDefaults.METEOR_MIN_ZHR).coerceIn(0, 200),
        conjunctionMaxSepDeg = (p[Keys.conjunctionMaxSep] ?: AlertDefaults.CONJUNCTION_MAX_SEP_DEG)
            .coerceIn(0.1f, 10f),
        darkSkyMaxMoon = (p[Keys.darkSkyMaxMoon] ?: AlertDefaults.DARK_SKY_MAX_MOON).coerceIn(0f, 1f),
        issMinPeakAltDeg = (p[Keys.issMinPeakAlt] ?: AlertDefaults.ISS_MIN_PEAK_ALT_DEG).coerceIn(10f, 80f),
        moonlight = (p[Keys.moonlight] ?: AlertDefaults.MOONLIGHT).coerceIn(0, 2),
        maxPerDay = (p[Keys.maxPerDay] ?: AlertDefaults.MAX_PER_DAY).coerceIn(1, 10),
    )

    private fun readState(p: Preferences) = NotificationState(
        cachedLat = p[Keys.cachedLat] ?: 0.0,
        cachedLon = p[Keys.cachedLon] ?: 0.0,
        cachedAltM = p[Keys.cachedAlt] ?: 0.0,
        cachedAtMillis = p[Keys.cachedAt] ?: 0L,
        lastRunMillis = p[Keys.lastRunAt] ?: 0L,
        permissionRequested = p[Keys.permRequested] ?: false,
        seenIds = p[Keys.seenIds] ?: emptySet(),
    )

    suspend fun setEnabled(value: Boolean) = context.dataStore.edit { it[Keys.enabled] = value }

    suspend fun setType(type: AlertType, value: Boolean) =
        context.dataStore.edit { it[Keys.type(type)] = value }

    suspend fun setBool(selector: AlertBool, value: Boolean) =
        context.dataStore.edit { it[selector.key] = value }

    suspend fun setInt(selector: AlertInt, value: Int) =
        context.dataStore.edit { it[selector.key] = value }

    suspend fun setFloat(selector: AlertFloat, value: Float) =
        context.dataStore.edit { it[selector.key] = value }

    suspend fun markPermissionRequested() =
        context.dataStore.edit { it[Keys.permRequested] = true }

    /**
     * Remember where the user was while the app was in the foreground, so the background
     * scan has somewhere to work from. This is the whole reason the app does not need
     * background-location permission.
     */
    suspend fun cacheLocation(lat: Double, lon: Double, altM: Double, atMillis: Long) =
        context.dataStore.edit {
            it[Keys.cachedLat] = lat
            it[Keys.cachedLon] = lon
            it[Keys.cachedAlt] = altM
            it[Keys.cachedAt] = atMillis
        }

    /**
     * Record what was actually sent.
     *
     * Written only after a notification is posted, never when one is merely planned —
     * that asymmetry is what makes threshold changes behave sensibly in both directions.
     * Raising a threshold simply stops rescheduling things; lowering it makes them
     * eligible again, because they were never recorded as sent.
     */
    suspend fun recordRun(firedIds: Set<String>, nowMillis: Long) = context.dataStore.edit { p ->
        p[Keys.lastRunAt] = nowMillis
        if (firedIds.isEmpty()) return@edit
        // Keep the store bounded: a bug that minted unbounded ids should not grow this
        // file forever.
        val merged = ((p[Keys.seenIds] ?: emptySet()) + firedIds).toList()
        p[Keys.seenIds] = merged.takeLast(MAX_SEEN_IDS).toSet()
    }

    enum class AlertBool(val key: Preferences.Key<Boolean>) {
        QuietHours(Keys.quietEnabled),
        QuietAllowEclipses(Keys.quietAllowEclipses),
    }

    enum class AlertInt(val key: Preferences.Key<Int>) {
        LeadDays(Keys.leadDays),
        QuietStartHour(Keys.quietStartHour),
        QuietEndHour(Keys.quietEndHour),
        MeteorMinZhr(Keys.meteorMinZhr),
        Moonlight(Keys.moonlight),
        MaxPerDay(Keys.maxPerDay),
    }

    enum class AlertFloat(val key: Preferences.Key<Float>) {
        MinAltitude(Keys.minAltitudeDeg),
        ConjunctionMaxSep(Keys.conjunctionMaxSep),
        DarkSkyMaxMoon(Keys.darkSkyMaxMoon),
        IssMinPeakAlt(Keys.issMinPeakAlt),
    }

    private companion object {
        const val MAX_SEEN_IDS = 400
    }
}
