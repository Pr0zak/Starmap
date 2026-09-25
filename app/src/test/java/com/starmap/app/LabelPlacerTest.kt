package com.starmap.app

import com.starmap.app.sky.LabelPlacer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacerTest {
    @Test
    fun overlappingLabelIsRejectedAndTouchingOneIsNot() {
        val p = LabelPlacer()
        assertTrue(p.tryPlace(0f, 0f, 100f, 20f))
        assertFalse(p.tryPlace(50f, 10f, 150f, 30f))
        assertTrue(p.tryPlace(100f, 0f, 200f, 20f)) // shares an edge only
    }

    @Test
    fun resetClearsThePreviousFrame() {
        val p = LabelPlacer()
        p.reserve(0f, 0f, 100f, 100f)
        assertFalse(p.fits(10f, 10f, 20f, 20f))
        p.reset()
        assertTrue(p.fits(10f, 10f, 20f, 20f))
    }
}
