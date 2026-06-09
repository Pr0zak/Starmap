package com.starmap.app.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/** Best-effort rear-camera field of view, used to line the AR overlay up with the camera. */
object CameraFov {

    /**
     * The vertical field of view (degrees) the rear camera shows across the screen's
     * vertical extent, from the sensor size and focal length.
     *
     * The sensor's long axis is horizontal in its native (landscape) orientation. With
     * a FILL_CENTER preview the tall portrait screen shows the full long axis vertically
     * (the sides are cropped, not the top/bottom), so in portrait the screen-vertical FOV
     * is the long-axis FOV; in landscape it is the short-axis FOV (approximate — the
     * top/bottom are cropped there, and the user can pinch to fine-tune).
     *
     * Returns null if the camera doesn't report its intrinsics.
     */
    fun screenVerticalFovDeg(context: Context, portrait: Boolean): Float? {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focals == null || focals.isEmpty() || size == null) continue
                val f = focals[0]
                if (f <= 0f) continue
                val axisMm = if (portrait) max(size.width, size.height) else min(size.width, size.height)
                return Math.toDegrees(2.0 * atan((axisMm / (2f * f)).toDouble())).toFloat()
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
