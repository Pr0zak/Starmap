package com.starmap.app.sky

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starmap.app.BuildConfig
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.StarCatalog
import com.starmap.app.catalog.CatalogManager
import com.starmap.app.satellite.NamedSat
import com.starmap.app.satellite.SatelliteManager
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.sensors.OrientationProvider
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository
import com.starmap.app.update.ApkUpdater
import com.starmap.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkyViewModel(app: Application) : AndroidViewModel(app) {

    val orientation = OrientationProvider(app)
    private val location = LocationProvider(app)
    private val settingsRepo = SettingsRepository(app)
    val catalogManager = CatalogManager(app)
    val satelliteManager = SatelliteManager(app)

    val settings: StateFlow<Settings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Manual location overrides the sensor fix when enabled. */
    val effectiveLocation: StateFlow<LocationProvider.Fix?> =
        combine(settingsRepo.settings, location.location) { s, fix ->
            if (s.manualLocation) {
                LocationProvider.Fix(s.manualLat, s.manualLon, 0.0, fromGps = false)
            } else {
                fix
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var catalog: StarCatalog? = null
    private var constellations: List<Constellation> = emptyList()
    private var issSats: List<NamedSat> = emptyList()
    private var starlinkSats: List<NamedSat> = emptyList()

    private val _model = mutableStateOf<SkyModel?>(null)
    val model: State<SkyModel?> = _model

    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    private val _updateResult = mutableStateOf<UpdateChecker.Result?>(null)
    val updateResult: State<UpdateChecker.Result?> = _updateResult

    private val _checkingUpdate = mutableStateOf(false)
    val checkingUpdate: State<Boolean> = _checkingUpdate

    private val _updateDownload = mutableStateOf<ApkUpdater.State>(ApkUpdater.State.Idle)
    val updateDownload: State<ApkUpdater.State> = _updateDownload

    private val _issBusy = mutableStateOf(false)
    val issBusy: State<Boolean> = _issBusy
    private val _starlinkBusy = mutableStateOf(false)
    val starlinkBusy: State<Boolean> = _starlinkBusy
    private val _starlinkProgress = mutableStateOf(0f)
    val starlinkProgress: State<Float> = _starlinkProgress
    private val _satMessage = mutableStateOf<String?>(null)
    val satMessage: State<String?> = _satMessage

    private var searchEntries: List<SearchEntry> = emptyList()
    private val _searchTarget = mutableStateOf<SearchTarget?>(null)
    val searchTarget: State<SearchTarget?> = _searchTarget

    init {
        Log.i(TAG, "SkyViewModel init")
        viewModelScope.launch {
            try {
                constellations = catalogManager.loadConstellations()
                catalog = catalogManager.loadStars(settings.value.useExtendedCatalog)
                buildSearchIndex()
                Log.i(TAG, "Catalog loaded: ${catalog?.count ?: 0} stars, ${constellations.size} constellations")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load catalog", t)
            } finally {
                _loading.value = false
            }
        }
        // Reload the star set whenever the extended-catalog preference flips.
        viewModelScope.launch {
            settingsRepo.settings.map { it.useExtendedCatalog }.distinctUntilChanged()
                .collect { useExtended ->
                    catalog = catalogManager.loadStars(useExtended)
                    buildSearchIndex()
                    _searchTarget.value = null // star indices changed
                }
        }
        // Load satellite elements lazily, only while their layer is enabled.
        viewModelScope.launch {
            settingsRepo.settings.map { it.showIss }.distinctUntilChanged().collect { on ->
                issSats = if (on && satelliteManager.isIssDownloaded) satelliteManager.loadIss() else emptyList()
            }
        }
        viewModelScope.launch {
            settingsRepo.settings.map { it.showStarlink }.distinctUntilChanged().collect { on ->
                starlinkSats =
                    if (on && satelliteManager.isStarlinkDownloaded) satelliteManager.loadStarlink() else emptyList()
            }
        }
        // Auto-check for updates once on launch if enabled.
        viewModelScope.launch {
            if (settings.value.autoCheckUpdates) checkForUpdates()
        }
        startRebuildLoop()
    }

    private fun startRebuildLoop() = viewModelScope.launch {
        while (isActive) {
            val cat = catalog
            val fix = effectiveLocation.value
            val s = settings.value
            if (cat != null && fix != null) {
                try {
                    val sats: List<NamedSat> = when {
                        issSats.isEmpty() && starlinkSats.isEmpty() -> emptyList()
                        else -> issSats + starlinkSats
                    }
                    val built = withContext(Dispatchers.Default) {
                        SkyBuilder.build(
                            catalog = cat,
                            constellations = constellations,
                            fix = fix,
                            timeMillis = System.currentTimeMillis(),
                            includeConstellations = s.showConstellations,
                            includePlanets = s.showPlanets,
                            satellites = sats,
                            showBelowHorizon = s.showBelowHorizon,
                        )
                    }
                    _model.value = built
                } catch (t: Throwable) {
                    Log.e(TAG, "Sky build failed", t)
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // --- Lifecycle wiring (called from the UI) ---
    fun startSensors() = orientation.start()
    fun stopSensors() = orientation.stop()
    fun startLocation() {
        if (!settings.value.manualLocation) location.start()
    }
    fun stopLocation() = location.stop()

    val hasOrientationSensor: Boolean get() = orientation.hasSensor

    // --- Settings passthrough ---
    fun setBool(selector: SettingsRepository.BoolSetting, value: Boolean) = viewModelScope.launch {
        settingsRepo.setBool(selector, value)
    }

    fun setFloat(selector: SettingsRepository.FloatSetting, value: Float) = viewModelScope.launch {
        settingsRepo.setFloat(selector, value)
    }

    fun setManualLocation(enabled: Boolean, lat: Double, lon: Double) {
        viewModelScope.launch { settingsRepo.setManualLocation(enabled, lat, lon) }
        if (enabled) location.setManual(lat, lon) else location.start()
    }

    // --- Updates ---
    fun checkForUpdates() {
        if (_checkingUpdate.value) return
        _checkingUpdate.value = true
        viewModelScope.launch {
            _updateResult.value = UpdateChecker.check(BuildConfig.VERSION_NAME)
            _checkingUpdate.value = false
        }
    }

    /** Download the new release's APK so it can be installed over the top. */
    fun downloadUpdate(apkUrl: String) {
        if (_updateDownload.value is ApkUpdater.State.Downloading) return
        _updateDownload.value = ApkUpdater.State.Downloading(0f)
        viewModelScope.launch {
            _updateDownload.value = ApkUpdater.download(getApplication<Application>(), apkUrl) { fraction ->
                _updateDownload.value = ApkUpdater.State.Downloading(fraction)
            }
        }
    }

    fun resetUpdateDownload() {
        _updateDownload.value = ApkUpdater.State.Idle
    }

    // --- Satellite (TLE) downloads ---
    fun downloadIss() {
        if (_issBusy.value) return
        _issBusy.value = true
        viewModelScope.launch {
            when (val r = satelliteManager.downloadIss()) {
                is SatelliteManager.Result.Ok -> {
                    _satMessage.value = "ISS elements updated"
                    if (settings.value.showIss) issSats = satelliteManager.loadIss()
                }
                is SatelliteManager.Result.Failed -> _satMessage.value = "ISS: ${r.message}"
            }
            _issBusy.value = false
        }
    }

    fun downloadStarlink() {
        if (_starlinkBusy.value) return
        _starlinkBusy.value = true
        _starlinkProgress.value = 0f
        viewModelScope.launch {
            when (val r = satelliteManager.downloadStarlink { _starlinkProgress.value = it }) {
                is SatelliteManager.Result.Ok -> {
                    _satMessage.value = "Starlink: ${r.count} satellites"
                    if (settings.value.showStarlink) starlinkSats = satelliteManager.loadStarlink()
                }
                is SatelliteManager.Result.Failed -> _satMessage.value = "Starlink: ${r.message}"
            }
            _starlinkBusy.value = false
        }
    }

    fun deleteIss() {
        satelliteManager.deleteIss()
        issSats = emptyList()
        _satMessage.value = null
    }

    fun deleteStarlink() {
        satelliteManager.deleteStarlink()
        starlinkSats = emptyList()
        _satMessage.value = null
    }

    // --- Search ---
    private fun buildSearchIndex() {
        val cat = catalog ?: return
        val cons = constellations
        val abbrToName = cons.associate { it.abbr.lowercase() to it.name }
        val entries = ArrayList<SearchEntry>(cat.labels.size + cons.size + 16)
        for ((idx, label) in cat.labels) {
            val tokens = label.split(' ')
            val last = tokens.lastOrNull()?.lowercase()
            // Bayer labels ("α CMa") get the full constellation name added to the key.
            val extra = if (tokens.size >= 2 && last != null && abbrToName.containsKey(last)) {
                " " + abbrToName.getValue(last)
            } else {
                ""
            }
            entries.add(SearchEntry(SearchTarget.StarT(idx, label), label, "Star", FuzzySearch.normalize(label + extra)))
        }
        for (p in listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")) {
            entries.add(SearchEntry(SearchTarget.PlanetT(p), p, "Planet", FuzzySearch.normalize(p)))
        }
        entries.add(SearchEntry(SearchTarget.SpecialT("Sun"), "Sun", "Solar System", "sun"))
        entries.add(SearchEntry(SearchTarget.SpecialT("Moon"), "Moon", "Solar System", "moon"))
        entries.add(SearchEntry(SearchTarget.SpecialT("ISS"), "ISS (Space Station)", "Satellite", "iss space station"))
        for (c in cons) {
            entries.add(
                SearchEntry(
                    SearchTarget.ConstellationT(c.name), c.name, "Constellation",
                    FuzzySearch.normalize("${c.name} ${c.abbr}"),
                ),
            )
        }
        searchEntries = entries
    }

    fun search(query: String): List<SearchResult> = FuzzySearch.search(query, searchEntries)

    fun selectSearchTarget(target: SearchTarget?) {
        _searchTarget.value = target
    }

    override fun onCleared() {
        super.onCleared()
        orientation.stop()
        location.stop()
    }

    private companion object {
        const val TAG = "Starmap"
    }
}
