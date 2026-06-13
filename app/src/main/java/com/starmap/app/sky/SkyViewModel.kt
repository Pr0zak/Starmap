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
import com.starmap.app.aircraft.AircraftTrack
import com.starmap.app.catalog.CatalogManager
import com.starmap.app.info.ObjectInfoStore
import com.starmap.app.info.WikiManager
import com.starmap.app.landmark.Landmark
import com.starmap.app.landmark.LandmarkManager
import com.starmap.app.satellite.NamedSat
import com.starmap.app.satellite.SatelliteManager
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.sensors.OrientationProvider
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository
import com.starmap.app.update.ApkUpdater
import com.starmap.app.update.DiagLog
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
    private val aircraftManager = AircraftManager()
    private var aircraftTracks: List<AircraftTrack> = emptyList()
    private val landmarkManager = LandmarkManager()
    private var landmarks: List<Landmark> = emptyList()
    private var landmarkFetchLat = Double.NaN
    private var landmarkFetchLon = Double.NaN
    private var landmarkFetchRangeKm = Float.NaN
    private var landmarkLastAttemptMs = 0L
    private var landmarkAwaitLogged = false
    private val _landmarkMessage = mutableStateOf<String?>(null)
    val landmarkMessage: State<String?> = _landmarkMessage
    private val aircraftHistory = HashMap<String, ArrayDeque<DoubleArray>>()

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

    private val objectInfoStore = ObjectInfoStore(app)
    private val _objectDetail = mutableStateOf<ObjectDetail?>(null)
    val objectDetail: State<ObjectDetail?> = _objectDetail

    private val _offlineSync = mutableStateOf<OfflineSync>(OfflineSync.Idle)
    val offlineSync: State<OfflineSync> = _offlineSync
    private val _offlineBytes = mutableStateOf(0L)
    val offlineBytes: State<Long> = _offlineBytes

    /** Open the encyclopedic detail panel for [obj] and load its Wikipedia summary. */
    fun openObjectDetail(obj: IdentifiedObject) {
        _objectDetail.value = ObjectDetail.Loading(obj.name)
        val query = wikiQueryFor(obj)
        viewModelScope.launch {
            _objectDetail.value = when (val r = objectInfoStore.get(query)) {
                is WikiManager.Result.Ok -> ObjectDetail.Loaded(obj.name, r.info)
                WikiManager.Result.None -> ObjectDetail.Empty(obj.name)
                is WikiManager.Result.Error -> ObjectDetail.Failed(obj.name, r.message)
            }
        }
    }

    fun closeObjectDetail() { _objectDetail.value = null }

    /** Recompute how much disk the offline object info (text + images) uses. */
    fun refreshOfflineSize() = viewModelScope.launch {
        _offlineBytes.value = objectInfoStore.usedBytes()
    }

    fun clearOfflineData() = viewModelScope.launch {
        objectInfoStore.clear()
        _offlineBytes.value = objectInfoStore.usedBytes()
        _offlineSync.value = OfflineSync.Idle
    }

    /** Pre-download Wikipedia text + images for every catalogued object, for offline use. */
    fun syncOfflineData() {
        if (_offlineSync.value is OfflineSync.Running) return
        viewModelScope.launch {
            val objs = offlineSyncObjects()
            _offlineSync.value = OfflineSync.Running(0, objs.size)
            var cached = 0
            objs.forEachIndexed { i, obj ->
                when (val r = objectInfoStore.get(wikiQueryFor(obj))) {
                    is WikiManager.Result.Ok -> {
                        cached++
                        r.info.imageUrl?.let { objectInfoStore.prewarmImage(it) }
                    }
                    else -> {}
                }
                _offlineSync.value = OfflineSync.Running(i + 1, objs.size)
                kotlinx.coroutines.delay(120) // be polite to Wikipedia
            }
            _offlineBytes.value = objectInfoStore.usedBytes()
            _offlineSync.value = OfflineSync.Done(cached)
        }
    }

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

    private fun wikiQueryFor(obj: IdentifiedObject): String {
        val n = obj.name
        return when (obj.kind) {
            "Planet" -> "$n planet"
            "Asteroid" -> "$n asteroid"
            "Comet" -> "$n comet"
            "Moon" -> "Moon"
            "Star" -> if (n == "Sun") "Sun" else "$n star"
            "Constellation" -> "$n constellation"
            "Satellite" ->
                if (n.contains("ISS", ignoreCase = true)) "International Space Station" else "$n satellite"
            else -> when { // Messier / deep-sky: prefer the common name, else "Messier NN"
                n.contains(" · ") -> n.substringAfter(" · ")
                Regex("^M\\d+$").matches(n.trim()) -> "Messier ${n.trim().drop(1)}"
                else -> n
            }
        }
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
                viewModelScope.launch { _selectedRoute.value = aircraftManager.fetchRoute(ac.callsign) }
            }
            if (ac.icaoHex.isNotBlank() || ac.registration.isNotBlank()) {
                _photoStatus.value = "Finding a photo…"
                viewModelScope.launch {
                    when (val r = aircraftManager.fetchPhoto(ac.icaoHex, ac.registration)) {
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

    private var searchEntries: List<SearchEntry> = emptyList()
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
        // Auto-check for updates once on launch if enabled.
        viewModelScope.launch {
            if (settings.value.autoCheckUpdates) checkForUpdates()
        }
        startRebuildLoop()
        startAircraftLoop()
        startLandmarkLoop()
    }

    /** Fetches nearby landmarks once enabled, refreshing when the observer moves a
     *  few km or the range slider changes. Polls often but only hits the network
     *  when something changed, with a short backoff so dragging the slider or a
     *  failing server doesn't hammer Overpass. */
    private fun startLandmarkLoop() = viewModelScope.launch {
        while (isActive) {
            val s = settings.value
            val fix = effectiveLocation.value
            if ((s.showLandmarks || (s.radarMode && s.radarLandmarks)) && fix != null) {
                landmarkAwaitLogged = false
                val rangeKm = s.landmarkRangeKm
                val changed = landmarkFetchLat.isNaN() ||
                    haversineKm(landmarkFetchLat, landmarkFetchLon, fix.latitude, fix.longitude) > 5.0 ||
                    kotlin.math.abs(rangeKm - landmarkFetchRangeKm) > 0.5f
                val sinceAttempt = System.currentTimeMillis() - landmarkLastAttemptMs
                if (changed && sinceAttempt > 8_000) {
                    landmarkLastAttemptMs = System.currentTimeMillis()
                    DiagLog.log(
                        "Landmarks loop: fix=%.4f,%.4f %s range=%dkm — fetching".format(
                            fix.latitude, fix.longitude, if (fix.fromGps) "gps" else "manual", rangeKm.toInt(),
                        ),
                    )
                    val km = rangeKm.toInt()
                    when (val r = landmarkManager.fetch(fix.latitude, fix.longitude, (rangeKm * 1000).toInt())) {
                        is LandmarkManager.Result.Ok -> {
                            landmarks = r.landmarks
                            landmarkFetchLat = fix.latitude
                            landmarkFetchLon = fix.longitude
                            landmarkFetchRangeKm = rangeKm
                            _landmarkMessage.value = if (r.landmarks.isEmpty()) {
                                "No mapped landmarks within $km km"
                            } else {
                                "${r.landmarks.size} landmarks within $km km — look toward the horizon"
                            }
                        }
                        is LandmarkManager.Result.Failed ->
                            _landmarkMessage.value = "Landmarks unavailable · ${r.message}"
                    }
                }
                kotlinx.coroutines.delay(3_000)
            } else {
                if ((s.showLandmarks || (s.radarMode && s.radarLandmarks)) && fix == null && !landmarkAwaitLogged) {
                    DiagLog.log("Landmarks loop: enabled but no location fix yet")
                    landmarkAwaitLogged = true
                }
                if (landmarks.isNotEmpty() || _landmarkMessage.value != null) {
                    landmarks = emptyList()
                    landmarkFetchLat = Double.NaN
                    landmarkFetchRangeKm = Float.NaN
                    _landmarkMessage.value = null
                }
                kotlinx.coroutines.delay(3_000)
            }
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val sl = kotlin.math.sin(Math.toRadians(lat2 - lat1) / 2)
        val so = kotlin.math.sin(Math.toRadians(lon2 - lon1) / 2)
        val a = sl * sl + kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) * so * so
        return 2.0 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(a).coerceAtMost(1.0))
    }

    private fun startAircraftLoop() = viewModelScope.launch {
        while (isActive) {
            val s = settings.value
            val fix = effectiveLocation.value
            if ((s.showAircraft || s.radarMode) && fix != null) {
                val acRange = if (s.radarMode) s.radarRangeNm.toInt() else s.aircraftRangeNm.toInt()
                when (val r = aircraftManager.fetch(fix.latitude, fix.longitude, acRange)) {
                    is AircraftManager.Result.Ok -> {
                        val seen = HashSet<String>()
                        val now = System.currentTimeMillis()
                        aircraftTracks = r.aircraft.map { ac ->
                            seen.add(ac.id)
                            val dq = aircraftHistory.getOrPut(ac.id) { ArrayDeque() }
                            dq.addLast(doubleArrayOf(ac.latitude, ac.longitude, ac.altitudeMeters))
                            while (dq.size > 30) dq.removeFirst()
                            AircraftTrack(
                                ac.id, ac.callsign, ac.isHelicopter, ac.latitude, ac.longitude,
                                ac.altitudeMeters, ac.typeCode, ac.groundSpeedKts, ac.trackDeg,
                                ac.registration, ac.verticalRateFpm, ac.squawk, ac.isEmergency,
                                ac.emergencyText, dq.dropLast(1).toList(), now,
                            )
                        }
                        aircraftHistory.keys.retainAll(seen)
                    }
                    is AircraftManager.Result.Failed -> Log.w(TAG, "Aircraft fetch: ${r.message}")
                }
                kotlinx.coroutines.delay(12_000)
            } else {
                if (aircraftTracks.isNotEmpty()) {
                    aircraftTracks = emptyList()
                    aircraftHistory.clear()
                }
                kotlinx.coroutines.delay(2_000)
            }
        }
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
                            aircraft = aircraftTracks,
                            includeLandmarks = s.showLandmarks || (s.radarMode && s.radarLandmarks),
                            landmarks = landmarks.filter {
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
        for (a in asteroidElements) {
            entries.add(SearchEntry(SearchTarget.AsteroidT(a.name), a.name, "Asteroid", FuzzySearch.normalize(a.name)))
        }
        for (c in cometElements) {
            entries.add(SearchEntry(SearchTarget.CometT(c.name), c.name, "Comet", FuzzySearch.normalize(c.name)))
        }
        for (d in messierDsos) {
            val display = if (d.common.isBlank()) d.name else "${d.name} · ${d.common}"
            entries.add(
                SearchEntry(
                    SearchTarget.MessierT(d.name), display, d.type,
                    FuzzySearch.normalize("${d.name} ${d.common} ${d.type}"),
                ),
            )
        }
        entries.add(SearchEntry(SearchTarget.SpecialT("Sun"), "Sun", "Solar System", "sun"))
        entries.add(SearchEntry(SearchTarget.SpecialT("Moon"), "Moon", "Solar System", "moon"))
        entries.add(SearchEntry(SearchTarget.SpecialT("ISS"), "ISS (Space Station)", "Satellite", "iss space station"))
        for (c in cons) {
            val alias = constellationAliases[c.abbr.lowercase()] ?: ""
            entries.add(
                SearchEntry(
                    SearchTarget.ConstellationT(c.name), c.name, "Constellation",
                    FuzzySearch.normalize("${c.name} ${c.abbr} $alias"),
                ),
            )
        }
        searchEntries = entries
    }

    fun search(query: String): List<SearchResult> = FuzzySearch.search(query, searchEntries)

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
