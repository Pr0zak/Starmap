package com.starmap.app.sky

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starmap.app.BuildConfig
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.StarCatalog
import com.starmap.app.catalog.CatalogManager
import com.starmap.app.sensors.LocationProvider
import com.starmap.app.sensors.OrientationProvider
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository
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

    private val _model = mutableStateOf<SkyModel?>(null)
    val model: State<SkyModel?> = _model

    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    private val _updateResult = mutableStateOf<UpdateChecker.Result?>(null)
    val updateResult: State<UpdateChecker.Result?> = _updateResult

    private val _checkingUpdate = mutableStateOf(false)
    val checkingUpdate: State<Boolean> = _checkingUpdate

    private val _downloadState = mutableStateOf<CatalogManager.DownloadState>(
        if (catalogManager.isExtendedDownloaded) {
            CatalogManager.DownloadState.Done(catalogManager.extendedSizeBytes)
        } else {
            CatalogManager.DownloadState.Idle
        },
    )
    val downloadState: State<CatalogManager.DownloadState> = _downloadState

    init {
        viewModelScope.launch {
            constellations = catalogManager.loadConstellations()
            catalog = catalogManager.loadStars(settings.value.useExtendedCatalog)
            _loading.value = false
        }
        // Reload the star set whenever the extended-catalog preference flips.
        viewModelScope.launch {
            settingsRepo.settings.map { it.useExtendedCatalog }.distinctUntilChanged()
                .collect { useExtended ->
                    catalog = catalogManager.loadStars(useExtended)
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
                val built = withContext(Dispatchers.Default) {
                    SkyBuilder.build(
                        catalog = cat,
                        constellations = constellations,
                        fix = fix,
                        timeMillis = System.currentTimeMillis(),
                        includeConstellations = s.showConstellations,
                    )
                }
                _model.value = built
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

    // --- Offline catalog downloads ---
    fun downloadExtendedCatalog() {
        if (_downloadState.value is CatalogManager.DownloadState.InProgress) return
        _downloadState.value = CatalogManager.DownloadState.InProgress(0f)
        viewModelScope.launch {
            val result = catalogManager.downloadExtended { fraction ->
                _downloadState.value = CatalogManager.DownloadState.InProgress(fraction)
            }
            _downloadState.value = result
            if (result is CatalogManager.DownloadState.Done && settings.value.useExtendedCatalog) {
                catalog = catalogManager.loadStars(true)
            }
        }
    }

    fun deleteExtendedCatalog() {
        catalogManager.deleteExtended()
        _downloadState.value = CatalogManager.DownloadState.Idle
        viewModelScope.launch { catalog = catalogManager.loadStars(false) }
    }

    override fun onCleared() {
        super.onCleared()
        orientation.stop()
        location.stop()
    }
}
