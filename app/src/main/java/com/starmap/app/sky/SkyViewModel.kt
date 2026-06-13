package com.starmap.app.sky

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.StarCatalog
import com.starmap.app.aircraft.AircraftManager
import com.starmap.app.catalog.CatalogManager
import com.starmap.app.info.WikiManager
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

/** A sky object the user tapped to identify. */
data class IdentifiedObject(
    val name: String,
    val kind: String,
    val detail: String,
    /** Re-resolvable handle so the object can be followed as it moves. */
    val target: SearchTarget? = null,
    /** ICAO hex when this is an aircraft, so it can be tracked. */
    val aircraftHex: String? = null,
)

/** Progress of pre-downloading object info for offline use. */
sealed interface OfflineSync {
    object Idle : OfflineSync
    data class Running(val done: Int, val total: Int) : OfflineSync
    data class Done(val count: Int) : OfflineSync
}

/** Encyclopedic detail panel state for an identified object. */
sealed interface ObjectDetail {
    val title: String
    data class Loading(override val title: String) : ObjectDetail
    data class Loaded(override val title: String, val info: WikiManager.Info) : ObjectDetail
    data class Empty(override val title: String) : ObjectDetail
    data class Failed(override val title: String, val message: String) : ObjectDetail
}

class SkyViewModel(app: Application) : AndroidViewModel(app) {

    val orientation = OrientationProvider(app)
    private val location = LocationProvider(app)
    private val settingsRepo = SettingsRepository(app)
    val catalogManager = CatalogManager(app)

    val settings: StateFlow<Settings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val satelliteController = SatelliteController(app, settings, viewModelScope)
    val satelliteManager: SatelliteManager get() = satelliteController.manager

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
    private var constellationArt: List<com.starmap.app.astro.ConstellationArt.Art> = emptyList()
    private var asteroidElements: List<com.starmap.app.astro.Asteroids.Element> = emptyList()
    private var cometElements: List<com.starmap.app.astro.Comets.Element> = emptyList()
    private var messierDsos: List<com.starmap.app.astro.Messier.Dso> = emptyList()
    private var milkyWay: com.starmap.app.astro.MilkyWay? = null

    private val aircraftController = AircraftController(settings, effectiveLocation, viewModelScope)
    private val landmarkController = LandmarkController(settings, effectiveLocation, viewModelScope)
    val landmarkMessage: State<String?> get() = landmarkController.message

    private val _selectedAircraft = mutableStateOf<AircraftRender?>(null)
    val selectedAircraft: State<AircraftRender?> = _selectedAircraft
    private val _selectedRoute = mutableStateOf<AircraftManager.Route?>(null)
    val selectedRoute: State<AircraftManager.Route?> = _selectedRoute
    private val _selectedPhoto = mutableStateOf<AircraftManager.Photo?>(null)
    val selectedPhoto: State<AircraftManager.Photo?> = _selectedPhoto
    /** Human-readable note shown when no aircraft photo is available (or while loading). */
    private val _photoStatus = mutableStateOf<String?>(null)
    val photoStatus: State<String?> = _photoStatus

    private val _selectedObject = mutableStateOf<IdentifiedObject?>(null)
    val selectedObject: State<IdentifiedObject?> = _selectedObject

    /** The object currently under the centre reticle (live), shown when nothing is pinned. */
    private val _centerObject = mutableStateOf<IdentifiedObject?>(null)
    val centerObject: State<IdentifiedObject?> = _centerObject
    fun setCenterObject(obj: IdentifiedObject?) { _centerObject.value = obj }

    private val objectInfoController = ObjectInfoController(app, viewModelScope)
    val objectDetail: State<ObjectDetail?> get() = objectInfoController.detail
    val offlineSync: State<OfflineSync> get() = objectInfoController.sync
    val offlineBytes: State<Long> get() = objectInfoController.bytes

    fun openObjectDetail(obj: IdentifiedObject) = objectInfoController.openDetail(obj)
    fun closeObjectDetail() = objectInfoController.closeDetail()
    fun refreshOfflineSize() = objectInfoController.refreshSize()
    fun clearOfflineData() = objectInfoController.clear()

    /** Pre-download Wikipedia text + images for every catalogued object, for offline use. */
    fun syncOfflineData() = objectInfoController.syncOfflineData(offlineSyncObjects())

    private fun offlineSyncObjects(): List<IdentifiedObject> {
        val list = ArrayList<IdentifiedObject>()
        for (p in listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")) {
            list.add(IdentifiedObject(p, "Planet", ""))
        }
        list.add(IdentifiedObject("Sun", "Star", ""))
        list.add(IdentifiedObject("Moon", "Moon", ""))
        list.add(IdentifiedObject("ISS", "Satellite", ""))
        for (c in constellations) list.add(IdentifiedObject(c.name, "Constellation", ""))
        for (c in cometElements) list.add(IdentifiedObject(c.name, "Comet", ""))
        for (a in asteroidElements) list.add(IdentifiedObject(a.name, "Asteroid", ""))
        for (d in messierDsos) {
            val label = if (d.common.isBlank()) d.name else "${d.name} · ${d.common}"
            list.add(IdentifiedObject(label, d.type, ""))
        }
        catalog?.let { cat ->
            for ((idx, name) in cat.labels) {
                if (idx in 0 until cat.count && idx < cat.mag.size && cat.mag[idx] <= 3.0f) {
                    list.add(IdentifiedObject(name, "Star", ""))
                }
            }
        }
        return list
    }

    /** Show the info card for a tapped sky object (clears any selected aircraft). */
    fun selectObject(obj: IdentifiedObject?) {
        _selectedObject.value = obj
        if (obj != null) _selectedAircraft.value = null
    }

    fun selectAircraft(ac: AircraftRender?) {
        _selectedAircraft.value = ac
        _selectedRoute.value = null
        _selectedPhoto.value = null
        _photoStatus.value = null
        if (ac != null) _selectedObject.value = null
        if (ac != null) {
            if (ac.callsign.isNotBlank() && ac.callsign != "?") {
                viewModelScope.launch { _selectedRoute.value = aircraftController.manager.fetchRoute(ac.callsign) }
            }
            if (ac.icaoHex.isNotBlank() || ac.registration.isNotBlank()) {
                _photoStatus.value = "Finding a photo…"
                viewModelScope.launch {
                    when (val r = aircraftController.manager.fetchPhoto(ac.icaoHex, ac.registration)) {
                        is AircraftManager.PhotoResult.Ok -> {
                            _selectedPhoto.value = r.photo
                            _photoStatus.value = null
                        }
                        AircraftManager.PhotoResult.None ->
                            _photoStatus.value = "No photo on planespotters"
                        is AircraftManager.PhotoResult.Error ->
                            _photoStatus.value = "Photo unavailable · ${r.message}"
                    }
                }
            } else {
                _photoStatus.value = "No registration to find a photo"
            }
        }
    }

    private val _model = mutableStateOf<SkyModel?>(null)
    val model: State<SkyModel?> = _model

    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    private val updateController = UpdateController(app, viewModelScope)
    val updateResult: State<UpdateChecker.Result?> get() = updateController.result
    val checkingUpdate: State<Boolean> get() = updateController.checking
    val updateDownload: State<ApkUpdater.State> get() = updateController.download

    val issBusy: State<Boolean> get() = satelliteController.issBusy
    val starlinkBusy: State<Boolean> get() = satelliteController.starlinkBusy
    val starlinkProgress: State<Float> get() = satelliteController.starlinkProgress
    val satMessage: State<String?> get() = satelliteController.message

    private val searchIndex = SearchIndex()
    private val _searchTarget = mutableStateOf<SearchTarget?>(null)
    val searchTarget: State<SearchTarget?> = _searchTarget

    /** When true the view is controlled by dragging instead of the phone's sensors. */
    private val _manualMode = mutableStateOf(false)
    val manualMode: State<Boolean> = _manualMode
    fun toggleManualMode() {
        _manualMode.value = !_manualMode.value
        if (!_manualMode.value) {
            _followActive.value = false
            _followAircraftHex.value = null
        }
    }

    /** When true the view auto-slews to keep the current search target (or aircraft) centred. */
    private val _followActive = mutableStateOf(false)
    val followActive: State<Boolean> = _followActive
    /** ICAO hex of the aircraft being tracked, if any (else the search target is followed). */
    private val _followAircraftHex = mutableStateOf<String?>(null)
    val followAircraftHex: State<String?> = _followAircraftHex

    fun setFollow(on: Boolean) {
        _followActive.value = on
        if (on) {
            _manualMode.value = true // following only makes sense in manual look
        } else {
            _followAircraftHex.value = null
        }
    }

    /** Track [hex] as it moves (null stops tracking). */
    fun followAircraft(hex: String?) {
        _followAircraftHex.value = hex
        if (hex != null) {
            _searchTarget.value = null // mutually exclusive with search-target follow
            _followActive.value = true
            _manualMode.value = true
        } else {
            _followActive.value = false
        }
    }

    // --- Time machine (see TimeMachine) -------------------------------------
    private val timeMachine = TimeMachine(viewModelScope)
    val liveTime: State<Boolean> get() = timeMachine.liveTime
    val timeFlowRate: State<Long> get() = timeMachine.timeFlowRate

    /** The instant the sky is currently drawn for. */
    fun currentSkyTimeMillis(): Long = timeMachine.currentSkyTimeMillis()

    /** Jump the simulated time by [deltaMillis] (leaves live mode). */
    fun jumpTime(deltaMillis: Long) = timeMachine.jumpTime(deltaMillis)

    /** Snap back to the real clock. */
    fun goLiveTime() = timeMachine.goLive()

    /** Animate time at [rate] simulated-millis per real second (0 pauses). */
    fun setTimeFlowRate(rate: Long) = timeMachine.setFlowRate(rate)

    init {
        Log.i(TAG, "SkyViewModel init")
        viewModelScope.launch {
            try {
                constellations = catalogManager.loadConstellations()
                constellationArt = catalogManager.loadConstellationArt()
                asteroidElements = catalogManager.loadAsteroids()
                cometElements = catalogManager.loadComets()
                messierDsos = catalogManager.loadMessier()
                milkyWay = catalogManager.loadMilkyWay()
                catalog = catalogManager.loadStars(settings.value.useExtendedCatalog)
                rebuildSearchIndex()
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
                    rebuildSearchIndex()
                    _searchTarget.value = null // star indices changed
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
                    val sats: List<NamedSat> = satelliteController.currentSats()
                    val built = withContext(Dispatchers.Default) {
                        SkyBuilder.build(
                            catalog = cat,
                            constellations = constellations,
                            fix = fix,
                            timeMillis = currentSkyTimeMillis(),
                            includeConstellations = s.showConstellations,
                            includeConstellationArt = s.showConstellationArt,
                            constellationArt = constellationArt,
                            includeEcliptic = s.showEcliptic,
                            includeEquator = s.showEquator,
                            includeGrid = s.showGrid,
                            includeMeteors = s.showMeteorShowers,
                            includeMessier = s.showMessier,
                            messierDsos = messierDsos,
                            includePlanets = s.showPlanets,
                            includeAsteroids = s.showAsteroids,
                            asteroidElements = asteroidElements,
                            includeAsteroidPaths = s.showAsteroidPaths,
                            includeComets = s.showComets,
                            cometElements = cometElements,
                            includeCometPaths = s.showCometPaths,
                            includeMilkyWay = s.showMilkyWay,
                            milkyWay = milkyWay,
                            includeRefraction = s.applyRefraction,
                            satellites = sats,
                            aircraft = aircraftController.currentTracks(),
                            includeLandmarks = s.showLandmarks || (s.radarMode && s.radarLandmarks),
                            landmarks = landmarkController.currentLandmarks().filter {
                                when (it.type) {
                                    "airport" -> s.landmarkAirports
                                    "tower" -> s.landmarkTowers
                                    else -> s.landmarkCities
                                }
                            },
                            showBelowHorizon = s.showBelowHorizon,
                        )
                    }
                    _model.value = built
                } catch (t: Throwable) {
                    Log.e(TAG, "Sky build failed", t)
                }
            }
            // Refresh faster while time-travelling so the time-lapse looks smooth.
            kotlinx.coroutines.delay(if (!timeMachine.isLive && timeMachine.flowRate != 0L) 120 else 1000)
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

    fun setOrientation(mode: Int) = viewModelScope.launch { settingsRepo.setOrientation(mode) }
    fun setFovCircles(mode: Int) = viewModelScope.launch { settingsRepo.setFovCircles(mode) }
    fun setRadarBasemap(mode: Int) = viewModelScope.launch { settingsRepo.setRadarBasemap(mode) }
    fun setRadarWeather(mode: Int) = viewModelScope.launch { settingsRepo.setRadarWeather(mode) }

    // --- Updates (see UpdateController) ---
    fun checkForUpdates() = updateController.checkForUpdates()
    fun downloadUpdate(apkUrl: String) = updateController.downloadUpdate(apkUrl)
    fun resetUpdateDownload() = updateController.resetDownload()

    // --- Satellite (TLE) downloads (see SatelliteController) ---
    fun downloadIss() = satelliteController.downloadIss(settings.value.showIss)
    fun downloadStarlink() = satelliteController.downloadStarlink(settings.value.showStarlink)
    fun deleteIss() = satelliteController.deleteIss()
    fun deleteStarlink() = satelliteController.deleteStarlink()

    // --- Search (index in SearchIndex; target state stays here, coupled with follow) ---
    private fun rebuildSearchIndex() {
        val cat = catalog ?: return
        searchIndex.build(cat, constellations, asteroidElements, cometElements, messierDsos)
    }

    fun search(query: String): List<SearchResult> = searchIndex.search(query)

    fun selectSearchTarget(target: SearchTarget?) {
        _searchTarget.value = target
        _followAircraftHex.value = null
        if (target == null) {
            _followActive.value = false
            return
        }
        // Turn on the layer the target lives in, so it can actually be located
        // instead of showing "Locating…" forever when that layer is off.
        val layer = when (target) {
            is SearchTarget.PlanetT -> SettingsRepository.BoolSetting.Planets
            is SearchTarget.AsteroidT -> SettingsRepository.BoolSetting.Asteroids
            is SearchTarget.CometT -> SettingsRepository.BoolSetting.Comets
            is SearchTarget.MessierT -> SettingsRepository.BoolSetting.Messier
            is SearchTarget.ConstellationT -> SettingsRepository.BoolSetting.Constellations
            is SearchTarget.SpecialT -> when {
                target.label.contains("ISS", ignoreCase = true) -> SettingsRepository.BoolSetting.Iss
                target.label.contains("STARLINK", ignoreCase = true) -> SettingsRepository.BoolSetting.Starlink
                else -> null // Sun/Moon are always computed
            }
            is SearchTarget.StarT -> null // stars are always in the model
        }
        if (layer != null) setBool(layer, true)
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
