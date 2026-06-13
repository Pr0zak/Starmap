package com.starmap.app.sky

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The "time machine": lets the sky be drawn for a moment other than now, either by
 * jumping to a fixed instant or by running a time-lapse. Self-contained state plus a
 * ticking loop, owned by [SkyViewModel] and driven on its scope. When live, the sky
 * follows the real clock; otherwise it shows [simTimeMillis], advanced each tick by
 * [timeFlowRate] simulated-millis per real second.
 */
class TimeMachine(scope: CoroutineScope) {

    private val _liveTime = mutableStateOf(true)
    val liveTime: State<Boolean> = _liveTime

    private var simTimeMillis = System.currentTimeMillis()

    /** Simulated time-lapse rate: simulated millis advanced per real second (0 = paused). */
    private val _timeFlowRate = mutableStateOf(0L)
    val timeFlowRate: State<Long> = _timeFlowRate

    /** Plain (non-Compose) reads for the rebuild loop's cadence decision. */
    val isLive: Boolean get() = _liveTime.value
    val flowRate: Long get() = _timeFlowRate.value

    /** The instant the sky should be drawn for. */
    fun currentSkyTimeMillis(): Long =
        if (_liveTime.value) System.currentTimeMillis() else simTimeMillis

    /** Jump the simulated time by [deltaMillis] (leaves live mode). */
    fun jumpTime(deltaMillis: Long) {
        simTimeMillis = currentSkyTimeMillis() + deltaMillis
        _liveTime.value = false
    }

    /** Snap back to the real clock. */
    fun goLive() {
        _liveTime.value = true
        _timeFlowRate.value = 0L
    }

    /** Animate time at [rate] simulated-millis per real second (0 pauses). */
    fun setFlowRate(rate: Long) {
        if (_liveTime.value) {
            simTimeMillis = System.currentTimeMillis()
            _liveTime.value = false
        }
        _timeFlowRate.value = rate
    }

    init {
        scope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(100)
                val now = System.currentTimeMillis()
                val rate = _timeFlowRate.value
                if (!_liveTime.value && rate != 0L) {
                    simTimeMillis += rate * (now - last) / 1000
                }
                last = now
            }
        }
    }
}
