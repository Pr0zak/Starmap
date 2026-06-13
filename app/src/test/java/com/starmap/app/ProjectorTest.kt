package com.starmap.app

import com.starmap.app.sky.Projector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the sky's camera projection. ENU is [east, north, up]; the test
 * camera looks due north with east to the right and up as up, so the maths is easy to
 * reason about. This is the formula the tap / centre-reticle identification relies on.
 */
class ProjectorTest {

    /** Camera at viewport (100,100), focal 100, 200×200 viewport, looking north. */
    private fun northCam(margin: Float = 0f) = Projector().apply {
        look = floatArrayOf(0f, 1f, 0f)
        right = floatArrayOf(1f, 0f, 0f)
        up = floatArrayOf(0f, 0f, 1f)
        cx = 100f; cy = 100f; focal = 100f
        width = 200f; height = 200f
        this.margin = margin
    }

    @Test
    fun straightAheadProjectsToCentre() {
        val out = FloatArray(2)
        assertTrue(northCam().project(0f, 1f, 0f, out))
        assertEquals(100f, out[0], 1e-3f)
        assertEquals(100f, out[1], 1e-3f)
    }

    @Test
    fun eastOffsetGoesRight_upOffsetGoesUp() {
        val out = FloatArray(2)
        // A direction tilted east and up by 0.1 (north component 1).
        assertTrue(northCam().project(0.1f, 1f, 0.1f, out))
        assertEquals(110f, out[0], 1e-3f)        // east → screen right (+x)
        assertEquals(90f, out[1], 1e-3f)         // up   → screen up (−y)
    }

    @Test
    fun behindCameraIsNotVisible() {
        val out = FloatArray(2)
        assertFalse(northCam().project(0f, -1f, 0f, out)) // due south, behind
    }

    @Test
    fun tooCloseToCameraPlaneIsCulled() {
        val out = FloatArray(2)
        // depth 0.1 < MIN_DEPTH (0.15) → culled even though it's in front.
        assertFalse(northCam().project(1f, 0.1f, 0f, out))
    }

    @Test
    fun offScreenReturnsFalseButStillWritesPosition() {
        val out = FloatArray(2)
        // depth 1, east 3 → sx = 100 + 300 = 400, well past width (200), margin 0.
        assertFalse(northCam().project(3f, 1f, 0f, out))
        assertEquals(400f, out[0], 1e-3f)
    }

    @Test
    fun marginKeepsNearEdgePointsVisible() {
        val out = FloatArray(2)
        // sx = 100 + (1.1/1)*... → east 1.1 → sx = 210, just past 200 but within margin 64.
        assertTrue(northCam(margin = 64f).project(1.1f, 1f, 0f, out))
        assertEquals(210f, out[0], 1e-3f)
    }

    @Test
    fun unsetBasisIsNotVisible() {
        val out = FloatArray(2)
        assertFalse(Projector().project(0f, 1f, 0f, out)) // null look/right/up
    }

    @Test
    fun projectAtReadsTripleAtBaseOffset() {
        val out = FloatArray(2)
        // Two points packed back-to-back; project the second (base 3): straight ahead.
        val arr = floatArrayOf(9f, 9f, 9f, 0f, 1f, 0f)
        assertTrue(northCam().projectAt(arr, 3, out))
        assertEquals(100f, out[0], 1e-3f)
        assertEquals(100f, out[1], 1e-3f)
    }
}
