package com.starmap.app.sky

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.starmap.app.BuildConfig
import com.starmap.app.update.ApkUpdater
import com.starmap.app.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Self-update flow: checks GitHub for a newer release and downloads its APK so the
 * app can install over itself. Owned by [SkyViewModel] and driven on its scope; the
 * UI reads the three [State]s and triggers the three actions. In-place install works
 * only because every release shares a signing certificate (see [ApkUpdater]).
 */
class UpdateController(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    private val _result = mutableStateOf<UpdateChecker.Result?>(null)
    val result: State<UpdateChecker.Result?> = _result

    private val _checking = mutableStateOf(false)
    val checking: State<Boolean> = _checking

    private val _download = mutableStateOf<ApkUpdater.State>(ApkUpdater.State.Idle)
    val download: State<ApkUpdater.State> = _download

    fun checkForUpdates() {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            _result.value = UpdateChecker.check(BuildConfig.VERSION_NAME)
            _checking.value = false
        }
    }

    /** Download the new release's APK so it can be installed over the top. */
    fun downloadUpdate(apkUrl: String) {
        if (_download.value is ApkUpdater.State.Downloading) return
        _download.value = ApkUpdater.State.Downloading(0f)
        scope.launch {
            _download.value = ApkUpdater.download(app, apkUrl) { fraction ->
                _download.value = ApkUpdater.State.Downloading(fraction)
            }
        }
    }

    fun resetDownload() {
        _download.value = ApkUpdater.State.Idle
    }
}
