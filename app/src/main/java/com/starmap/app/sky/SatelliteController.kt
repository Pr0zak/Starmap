package com.starmap.app.sky

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.starmap.app.satellite.NamedSat
import com.starmap.app.satellite.SatelliteManager
import com.starmap.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * NORAD two-line-element (TLE) satellites: downloads/caches the ISS and Starlink
 * element sets and keeps the propagated lists ready for the sky build. Owned by
 * [SkyViewModel] and driven on its scope. The elements are loaded lazily — only
 * while their layer is enabled — and re-loaded when a fresh download lands. The
 * rebuild loop reads [currentSats]; the UI reads the busy/progress/message state
 * and [manager] (for the downloaded-yet checks).
 */
class SatelliteController(
    context: Context,
    settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    val manager = SatelliteManager(context)

    private var issSats: List<NamedSat> = emptyList()
    private var starlinkSats: List<NamedSat> = emptyList()

    private val _issBusy = mutableStateOf(false)
    val issBusy: State<Boolean> = _issBusy
    private val _starlinkBusy = mutableStateOf(false)
    val starlinkBusy: State<Boolean> = _starlinkBusy
    private val _starlinkProgress = mutableStateOf(0f)
    val starlinkProgress: State<Float> = _starlinkProgress
    private val _message = mutableStateOf<String?>(null)
    val message: State<String?> = _message

    init {
        // Load each set lazily, only while its layer is enabled.
        scope.launch {
            settings.map { it.showIss }.distinctUntilChanged().collect { on ->
                issSats = if (on && manager.isIssDownloaded) manager.loadIss() else emptyList()
            }
        }
        scope.launch {
            settings.map { it.showStarlink }.distinctUntilChanged().collect { on ->
                starlinkSats = if (on && manager.isStarlinkDownloaded) manager.loadStarlink() else emptyList()
            }
        }
    }

    /** All currently-loaded satellites for the sky build (empty when both layers are off). */
    fun currentSats(): List<NamedSat> =
        if (issSats.isEmpty() && starlinkSats.isEmpty()) emptyList() else issSats + starlinkSats

    fun downloadIss(showIss: Boolean) {
        if (_issBusy.value) return
        _issBusy.value = true
        scope.launch {
            when (val r = manager.downloadIss()) {
                is SatelliteManager.Result.Ok -> {
                    _message.value = "ISS elements updated"
                    if (showIss) issSats = manager.loadIss()
                }
                is SatelliteManager.Result.Failed -> _message.value = "ISS: ${r.message}"
            }
            _issBusy.value = false
        }
    }

    fun downloadStarlink(showStarlink: Boolean) {
        if (_starlinkBusy.value) return
        _starlinkBusy.value = true
        _starlinkProgress.value = 0f
        scope.launch {
            when (val r = manager.downloadStarlink { _starlinkProgress.value = it }) {
                is SatelliteManager.Result.Ok -> {
                    _message.value = "Starlink: ${r.count} satellites"
                    if (showStarlink) starlinkSats = manager.loadStarlink()
                }
                is SatelliteManager.Result.Failed -> _message.value = "Starlink: ${r.message}"
            }
            _starlinkBusy.value = false
        }
    }

    fun deleteIss() {
        manager.deleteIss()
        issSats = emptyList()
        _message.value = null
    }

    fun deleteStarlink() {
        manager.deleteStarlink()
        starlinkSats = emptyList()
        _message.value = null
    }
}
