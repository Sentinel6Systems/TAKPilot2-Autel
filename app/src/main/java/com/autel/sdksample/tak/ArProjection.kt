package com.autel.sdksample.tak

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where a target lands in the camera's picture, from the camera's bearing and pitch.
 *
 * Taking the bearing difference and the elevation difference as if they were the picture's
 * horizontal and vertical angles is true only with the camera on the horizon. Looking 71° down,
 * a marker 35° off the camera's bearing is only 11° from the centre of the picture — bearing
 * differences shrink by about cos(depression) — and that mapping throws it to the frame edge,
 * left or right of its true place depending on which way the aircraft has just yawed. The
 * marker under the crosshair is always right, because at zero offset every mapping agrees,
 * which is exactly how the error survives a "drop at the crosshair" check. The flight numbers
 * that showed it are this object's tests.
 *
 * This does the geometry instead: the target's direction as a unit vector in the world (east,
 * north, up), the camera's forward, right and up axes from its bearing and pitch, and the
 * target's components along them. `lateral/forward` and `up/forward` are the true perspective
 * (gnomonic) picture coordinates; divided by tan of the half fields of view they run −1..1
 * across the frame. Pure arithmetic, no Android, so it is unit-tested with the flight's numbers.
 */
object ArProjection {
    /** A target is drawn only when it is at least this far in front of the camera (the cosine
     *  of its angle off the axis). Past about 87° the perspective divide means nothing. */
    private const val MIN_FORWARD = 0.05

    /**
     * (lateral, up, forward) components of the target's direction in the camera frame.
     * [dBearingDeg] is the target's bearing minus the camera's; [elevDeg] the target's elevation
     * seen from the camera, down negative; [pitchDeg] the camera's pitch, down negative.
     */
    fun cameraFrame(dBearingDeg: Double, elevDeg: Double, pitchDeg: Double): Triple<Double, Double, Double> {
        val b = Math.toRadians(dBearingDeg)
        val e = Math.toRadians(elevDeg)
        val p = Math.toRadians(pitchDeg)
        // World axes with north along the camera's bearing (the bearing is relative): x east,
        // y north, z up. Camera forward is (0, cos p, sin p), right (1, 0, 0), up (0, −sin p, cos p).
        val tx = cos(e) * sin(b)
        val ty = cos(e) * cos(b)
        val tz = sin(e)
        val forward = ty * cos(p) + tz * sin(p)
        val lateral = tx
        val up = -ty * sin(p) + tz * cos(p)
        return Triple(lateral, up, forward)
    }

    /**
     * Picture coordinates: −1..1 across the frame (right positive) and up it (up positive), or
     * null when the target is behind the camera. Values beyond ±1 are in front but off-frame.
     */
    fun project(dBearingDeg: Double, elevDeg: Double, pitchDeg: Double, hFovDeg: Double, vFovDeg: Double): Pair<Double, Double>? {
        val (lateral, up, forward) = cameraFrame(dBearingDeg, elevDeg, pitchDeg)
        if (forward < MIN_FORWARD) return null
        val nx = (lateral / forward) / tan(Math.toRadians(hFovDeg / 2.0))
        val ny = (up / forward) / tan(Math.toRadians(vFovDeg / 2.0))
        return nx to ny
    }

    /**
     * The direction of a target that is not drawn, as (across, up) scaled so the longer axis is
     * ±1 — the frame edge an arrow belongs on. In front, it is the projected point pushed out to
     * the edge; behind, the lateral and up components still say which way to turn. (0, 0) only
     * for a target exactly behind the camera, which has no direction at all.
     */
    fun edgeDirection(dBearingDeg: Double, elevDeg: Double, pitchDeg: Double, hFovDeg: Double, vFovDeg: Double): Pair<Double, Double> {
        val (lateral, up, forward) = cameraFrame(dBearingDeg, elevDeg, pitchDeg)
        val x: Double
        val y: Double
        if (forward >= MIN_FORWARD) {
            x = (lateral / forward) / tan(Math.toRadians(hFovDeg / 2.0))
            y = (up / forward) / tan(Math.toRadians(vFovDeg / 2.0))
        } else {
            x = lateral
            y = up
        }
        val m = maxOf(abs(x), abs(y))
        if (m <= 1e-9) return 0.0 to 0.0
        return (x / m) to (y / m)
    }
}
