package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * THE LOOK-POINT OVER TERRAIN. The terrain in each test is a height for a distance from the
 * aircraft, so a case can be checked by hand: the answer is the first distance where
 * `aircraft height - distance x tan(angle down)` is at or below the ground.
 *
 * The look-point walks the line of sight over the terrain, and the case of the shape seen in
 * flight passes. Three cases check this app's own choice where there is no terrain answer;
 * each says so.
 */
class CameraSlantPointTest {

    private val lat = 45.0
    private val lon = -100.0
    private val bearing = 83.0

    /** A terrain lookup from a height-for-distance rule; null where the rule gives null. */
    private fun terrain(heightAt: (Double) -> Double?): (Double, Double) -> Double? = { la, lo ->
        heightAt(CameraSlantPoint.distanceMeters(lat, lon, la, lo))
    }

    /** Straight lines between (distance, height) points; level past the last one. */
    private fun profile(vararg pts: Pair<Double, Double>): (Double) -> Double? = { d ->
        val i = pts.indexOfLast { it.first <= d }
        when {
            i < 0 -> pts.first().second
            i == pts.lastIndex -> pts.last().second
            else -> {
                val (d0, h0) = pts[i]; val (d1, h1) = pts[i + 1]
                h0 + (h1 - h0) * (d - d0) / (d1 - d0)
            }
        }
    }

    private fun downSlope(deg: Double) = tan(Math.toRadians(deg))

    @Test
    fun levelGroundGivesTheSameAnswerAsNoTerrain() {
        for (pitch in listOf(-5.0, -20.0, -45.0, -80.0)) {
            val flat = CameraSlantPoint.compute(lat, lon, 60.0, bearing, pitch)
            val walked = CameraSlantPoint.compute(lat, lon, 60.0, bearing, pitch, terrain { 100.0 })
            assertEquals("pitch $pitch", flat.rangeMeters, walked.rangeMeters, 0.5)
            assertEquals(100.0, walked.elevationMeters, 0.0)
        }
    }

    // A WORKED CASE OF THE SHAPE THAT WAS SEEN IN FLIGHT: ground that rises toward the target,
    // the aircraft 58.8 m over its takeoff point and the camera 1.3 degrees down, on a target
    // 724 m away. The line of sight meets the level ground at 749 m. The old solve gave 58 m.
    @Test
    fun aShallowCameraOverRisingGroundReachesTheTarget() {
        val ground = profile(
            0.0 to 401.2, 80.0 to 404.0, 200.0 to 410.0, 350.0 to 421.0, 500.0 to 431.0,
            640.0 to 440.0, 724.0 to 443.0, 900.0 to 443.0, 1200.0 to 450.0, 1600.0 to 452.0,
            2000.0 to 465.0, 2600.0 to 480.0, 3000.0 to 560.0)
        val p = CameraSlantPoint.compute(
            lat, lon, 58.8, bearing, -1.3, terrain(ground), aircraftMslMeters = 460.0)
        assertTrue("range was ${p.rangeMeters}", p.rangeMeters in 724.0..790.0)
        // And it is on the ground there, not above or below it.
        assertEquals(460.0 - p.rangeMeters * downSlope(1.3), ground(p.rangeMeters)!!, 0.5)
        assertEquals(ground(p.rangeMeters)!!, p.elevationMeters, 0.5)
    }

    @Test
    fun aSteadyUpSlopeIsSolvedExactly() {
        // 5 % up-slope, 3 degrees down, 50 m high: 50 = d x (tan 3 + 0.05).
        val expected = 50.0 / (downSlope(3.0) + 0.05)
        val p = CameraSlantPoint.compute(
            lat, lon, 50.0, bearing, -3.0, terrain { d -> 100.0 + 0.05 * d })
        assertEquals(expected, p.rangeMeters, 0.5)
    }

    @Test
    fun groundThatFallsAwayPutsThePointFurtherOut() {
        // 2 % down-slope, 5 degrees down, 60 m high: 60 = d x (tan 5 - 0.02).
        val expected = 60.0 / (downSlope(5.0) - 0.02)
        val p = CameraSlantPoint.compute(
            lat, lon, 60.0, bearing, -5.0, terrain { d -> 500.0 - 0.02 * d })
        assertEquals(expected, p.rangeMeters, 1.0)
    }

    // The camera sees the near side of a ridge, not the level ground behind it.
    @Test
    fun aRidgeInFrontIsWhatTheCameraSees() {
        val ground = profile(0.0 to 100.0, 400.0 to 100.0, 500.0 to 160.0, 600.0 to 100.0)
        val p = CameraSlantPoint.compute(lat, lon, 60.0, bearing, -3.0, terrain(ground))
        // Level ground alone would give 60 / tan 3 = 1145 m.
        assertTrue("range was ${p.rangeMeters}", p.rangeMeters in 400.0..500.0)
    }

    @Test
    fun theAircraftsOwnHeightFrameIsUsedWhenGiven() {
        // 40 m above takeoff, but the ground under the aircraft is 20 m below the takeoff
        // point: 60 m above level ground at 80 m.
        val p = CameraSlantPoint.compute(
            lat, lon, 40.0, bearing, -10.0, terrain { 80.0 }, aircraftMslMeters = 140.0)
        assertEquals(60.0 / downSlope(10.0), p.rangeMeters, 0.5)
    }

    @Test
    fun whereTheTerrainDataEndsTheWalkGoesOnOverLevelGround() {
        // Data to 200 m only, level at 100 m. 60 m high, 5 degrees: 686 m.
        val p = CameraSlantPoint.compute(
            lat, lon, 60.0, bearing, -5.0, terrain { d -> if (d <= 200.0) 100.0 else null })
        assertEquals(60.0 / downSlope(5.0), p.rangeMeters, 1.0)
        // THIS APP'S CHOICE: the point carries the last terrain seen (100 m), not NaN.
        assertEquals(100.0, p.elevationMeters, 0.0)
    }

    @Test
    fun noGroundInsideTheLimitIsHeldAtTheLimit() {
        // 2 degrees down over ground that falls 5 %: the line never meets it.
        val p = CameraSlantPoint.compute(
            lat, lon, 60.0, bearing, -2.0, terrain { d -> 1000.0 - 0.05 * d })
        assertEquals(10_000.0, p.rangeMeters, 0.0)
    }

    @Test
    fun straightDownIsUnderTheAircraft() {
        val p = CameraSlantPoint.compute(lat, lon, 60.0, bearing, -90.0, terrain { 100.0 })
        // THIS APP'S CHOICE: the crossing is bisected to 0.5 m (BISECT_TOLERANCE_M).
        assertEquals(0.0, p.rangeMeters, 0.5)
    }

    // No line to walk: the level-ground fallback, as before.
    @Test
    fun aCameraNearLevelOrAnAircraftOnTheGroundKeepsTheFallback() {
        assertEquals(300.0,
            CameraSlantPoint.compute(lat, lon, 60.0, bearing, -0.5, terrain { 100.0 }).rangeMeters, 0.0)
        assertEquals(300.0,
            CameraSlantPoint.compute(lat, lon, 0.0, bearing, -20.0, terrain { 100.0 }).rangeMeters, 0.0)
        // The terrain frame says the aircraft is below its own ground: no solution. THIS APP'S
        // CHOICE is the level-ground answer for its 60 m height, not the 300 m fallback.
        assertEquals(60.0 / downSlope(20.0), CameraSlantPoint.compute(
            lat, lon, 60.0, bearing, -20.0, terrain { 100.0 }, aircraftMslMeters = 90.0).rangeMeters, 0.5)
    }

    @Test
    fun noCoverageUnderTheAircraftIsTheLevelGroundAnswer() {
        val flat = CameraSlantPoint.compute(lat, lon, 60.0, bearing, -10.0)
        val p = CameraSlantPoint.compute(lat, lon, 60.0, bearing, -10.0, terrain { null })
        assertEquals(flat.rangeMeters, p.rangeMeters, 0.0)
    }
}
