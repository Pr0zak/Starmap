package com.starmap.app.sky

/** A direction this close to the camera plane (or behind it) is culled, not projected. */
internal const val MIN_DEPTH = 0.15f

/**
 * The sky's camera projection: turns a horizon-frame ENU direction into a screen
 * point, given the camera basis (orthonormal [look]/[right]/[up]), the viewport
 * centre ([cx],[cy]) and [focal] length, and a [margin] of off-screen slack.
 *
 * This is the single source of truth for the gnomonic formula
 *   sx = cx + (v·right / v·look) · focal
 *   sy = cy − (v·up    / v·look) · focal
 * used by the per-object label/glyph draws and by tap/centre-reticle identification
 * ([projectAt]). The thousands-per-frame star and polyline draws inline the same
 * maths by hand for speed — keep them in lockstep with [project].
 *
 * The basis fields are nullable so a [Projector] can exist for a frame before the
 * draw pass has computed the camera; projection then reports "not visible".
 */
internal class Projector {
    var look: FloatArray? = null
    var right: FloatArray? = null
    var up: FloatArray? = null
    var cx = 0f
    var cy = 0f
    var focal = 0f
    var width = 0f
    var height = 0f
    var margin = 0f

    /** Whether below-horizon objects are kept (a culling flag carried alongside). */
    var showBelow = false

    /**
     * Projects ENU ([x],[y],[z]) into [out] (screen x, y). Returns true when the
     * point is in front of the camera and within the viewport plus [margin]; false
     * (with [out] possibly written) when it's behind or off-screen, or before the
     * camera basis is set.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val lk = look ?: return false
        val rt = right ?: return false
        val u = up ?: return false
        val depth = x * lk[0] + y * lk[1] + z * lk[2]
        if (depth < MIN_DEPTH) return false
        out[0] = cx + ((x * rt[0] + y * rt[1] + z * rt[2]) / depth) * focal
        out[1] = cy - ((x * u[0] + y * u[1] + z * u[2]) / depth) * focal
        return out[0] >= -margin && out[0] <= width + margin &&
            out[1] >= -margin && out[1] <= height + margin
    }

    /** Projects the 3 floats at [arr]\[base..base+2]. */
    fun projectAt(arr: FloatArray, base: Int, out: FloatArray): Boolean =
        project(arr[base], arr[base + 1], arr[base + 2], out)

    /** Projects the 3-element direction [v]. */
    fun projectVec(v: FloatArray, out: FloatArray): Boolean =
        project(v[0], v[1], v[2], out)
}
