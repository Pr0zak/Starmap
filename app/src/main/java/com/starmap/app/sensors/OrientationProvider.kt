package com.starmap.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.sqrt

/**
 * Device orientation from the fused rotation-vector sensor.
 *
 * Produces the camera basis vectors in the world ENU frame (X=East, Y=magnetic
 * North, Z=Up):
 *   - look : direction the back of the phone points (device -Z)
 *   - right: device +X
 *   - up   : device +Y
 *
 * These are referenced to *magnetic* north; the renderer rotates them to true
 * north using the local magnetic declination. Reading [basis] is lock-free so the
 * render loop can sample it every frame.
 */
class OrientationProvider(context: Context) : SensorEventListener {

    data class Basis(
        val right: FloatArray,
        val up: FloatArray,
        val look: FloatArray,
        val azimuthDeg: Float,
        val pitchDeg: Float,
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        .defaultDisplay

    val hasSensor: Boolean get() = rotationSensor != null

    @Volatile
    var basis: Basis = Basis(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0f, 1f, 0f),
        0f, 0f,
    )
        private set

    @Volatile
    var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var initialized = false
    private val smoothing = 0.35f // exponential factor; higher = snappier

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)

        // Account for the display's natural rotation so the canvas axes line up
        // with the device axes regardless of how the panel is mounted.
        val (axisX, axisY) = when (display?.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)

        // Columns of R map device axes into the world (ENU) frame.
        val right = floatArrayOf(remapped[0], remapped[3], remapped[6])
        val up = floatArrayOf(remapped[1], remapped[4], remapped[7])
        val look = floatArrayOf(-remapped[2], -remapped[5], -remapped[8])

        SensorManager.getOrientation(remapped, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f
        val pitch = Math.toDegrees(-orientation[1].toDouble()).toFloat()

        basis = if (!initialized) {
            initialized = true
            Basis(right, up, look, azimuth, pitch)
        } else {
            val prev = basis
            Basis(
                lerpNorm(prev.right, right),
                lerpNorm(prev.up, up),
                lerpNorm(prev.look, look),
                lerpAngle(prev.azimuthDeg, azimuth),
                prev.pitchDeg + (pitch - prev.pitchDeg) * smoothing,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) accuracy = acc
    }

    private fun lerpNorm(a: FloatArray, b: FloatArray): FloatArray {
        val x = a[0] + (b[0] - a[0]) * smoothing
        val y = a[1] + (b[1] - a[1]) * smoothing
        val z = a[2] + (b[2] - a[2]) * smoothing
        val inv = 1f / (sqrt(x * x + y * y + z * z) + 1e-9f)
        return floatArrayOf(x * inv, y * inv, z * inv)
    }

    private fun lerpAngle(a: Float, b: Float): Float {
        var diff = b - a
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        var r = a + diff * smoothing
        if (r < 0) r += 360f
        if (r >= 360f) r -= 360f
        return r
    }
}
