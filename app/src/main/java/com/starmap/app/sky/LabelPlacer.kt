package com.starmap.app.sky

/**
 * Greedy label placement for one frame: labels are offered in priority order and
 * each is drawn only if its box doesn't overlap one already placed. Boxes are kept
 * in a reusable pool so a frame allocates nothing once warmed up.
 */
internal class LabelPlacer {
    private val boxes = ArrayList<FloatArray>()
    private var used = 0

    /** Forget every placed box; call once at the start of each frame. */
    fun reset() {
        used = 0
    }

    fun fits(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (i in 0 until used) {
            val b = boxes[i]
            if (left < b[2] && right > b[0] && top < b[3] && bottom > b[1]) return false
        }
        return true
    }

    /** Claim a box whether or not it overlaps (for labels that must always show). */
    fun reserve(left: Float, top: Float, right: Float, bottom: Float) {
        if (used == boxes.size) boxes.add(FloatArray(4))
        val b = boxes[used++]
        b[0] = left; b[1] = top; b[2] = right; b[3] = bottom
    }

    /** Claim the box if it's free; returns whether the label should be drawn. */
    fun tryPlace(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (!fits(left, top, right, bottom)) return false
        reserve(left, top, right, bottom)
        return true
    }
}
