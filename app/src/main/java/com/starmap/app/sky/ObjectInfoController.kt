package com.starmap.app.sky

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.starmap.app.info.ObjectInfoStore
import com.starmap.app.info.WikiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Encyclopedic detail for a tapped object and the offline pre-download of that info.
 * Owned by [SkyViewModel] and driven on its scope; backed by [ObjectInfoStore] (a
 * disk cache of Wikipedia text + images). The catalogue-dependent list of what to
 * pre-cache is built by the VM (which holds the loaded catalogue) and passed into
 * [syncOfflineData], so this controller stays free of catalogue state.
 */
class ObjectInfoController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val store = ObjectInfoStore(context)

    private val _detail = mutableStateOf<ObjectDetail?>(null)
    val detail: State<ObjectDetail?> = _detail

    private val _sync = mutableStateOf<OfflineSync>(OfflineSync.Idle)
    val sync: State<OfflineSync> = _sync

    private val _bytes = mutableStateOf(0L)
    val bytes: State<Long> = _bytes

    /** Open the encyclopedic detail panel for [obj] and load its Wikipedia summary. */
    fun openDetail(obj: IdentifiedObject) {
        _detail.value = ObjectDetail.Loading(obj.name)
        val query = wikiQueryFor(obj)
        scope.launch {
            _detail.value = when (val r = store.get(query)) {
                is WikiManager.Result.Ok -> ObjectDetail.Loaded(obj.name, r.info)
                WikiManager.Result.None -> ObjectDetail.Empty(obj.name)
                is WikiManager.Result.Error -> ObjectDetail.Failed(obj.name, r.message)
            }
        }
    }

    fun closeDetail() { _detail.value = null }

    /** Recompute how much disk the offline object info (text + images) uses. */
    fun refreshSize() = scope.launch {
        _bytes.value = store.usedBytes()
    }

    fun clear() = scope.launch {
        store.clear()
        _bytes.value = store.usedBytes()
        _sync.value = OfflineSync.Idle
    }

    /** Pre-download Wikipedia text + images for [objs], for offline use. */
    fun syncOfflineData(objs: List<IdentifiedObject>) {
        if (_sync.value is OfflineSync.Running) return
        scope.launch {
            _sync.value = OfflineSync.Running(0, objs.size)
            var cached = 0
            objs.forEachIndexed { i, obj ->
                when (val r = store.get(wikiQueryFor(obj))) {
                    is WikiManager.Result.Ok -> {
                        cached++
                        r.info.imageUrl?.let { store.prewarmImage(it) }
                    }
                    else -> {}
                }
                _sync.value = OfflineSync.Running(i + 1, objs.size)
                delay(120) // be polite to Wikipedia
            }
            _bytes.value = store.usedBytes()
            _sync.value = OfflineSync.Done(cached)
        }
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
}
