package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

/**
 * The numbers are from an Air 2S flight: a bearing-as-horizontal-angle mapping put markers at the
 * frame edge when the camera looked down and the aircraft yawed; the picture had them a third of
 * the way out.
 */
class ArProjectionTest {

    @Test
    fun aTargetOnTheAxisIsTheCentreWhateverThePitch() {
        val p = ArProjection.project(0.0, -55.0, -55.0, 78.0, 49.0)!!
        assertEquals(0.0, p.first, 1e-9)
        assertEquals(0.0, p.second, 1e-9)
    }

    @Test
    fun aLevelCameraKeepsTheTangentMapping() {
        val p = ArProjection.project(20.0, 0.0, 0.0, 78.0, 49.0)!!
        assertEquals(tan(Math.toRadians(20.0)) / tan(Math.toRadians(39.0)), p.first, 1e-9)
        assertEquals(0.0, p.second, 1e-9)
    }

    /** Hold 8 of the yaw test: 30.2° off the bearing, camera 55.4° down, target 54.7° down. The
     *  old mapping drew it at 30°; the frame had it at about 17°. */
    @Test
    fun lookingDownABearingOffsetShrinksInThePicture() {
        val (lateral, _, forward) = ArProjection.cameraFrame(30.2, -54.7, -55.4)
        assertEquals(16.9, Math.toDegrees(atan(lateral / forward)), 0.3)
    }

    /** A marker after a 36° yaw with the camera 71° down: the old mapping threw it
     *  to the frame edge at 35°; the picture had it a quarter of the way out. */
    @Test
    fun nearlyStraightDownAYawBarelyMovesAMarker() {
        val (lateral, _, forward) = ArProjection.cameraFrame(-34.9, -71.1, -71.3)
        assertEquals(-10.7, Math.toDegrees(atan(lateral / forward)), 0.4)
    }

    @Test
    fun behindTheCameraIsNotDrawnButStillHasADirection() {
        assertNull(ArProjection.project(170.0, 0.0, 0.0, 78.0, 49.0))
        val d = ArProjection.edgeDirection(170.0, 0.0, 0.0, 78.0, 49.0)
        assertTrue("a target off to the right-rear points right", d.first > 0.9)
    }

    @Test
    fun offFrameInFrontLandsOnTheEdge() {
        val d = ArProjection.edgeDirection(60.0, 0.0, 0.0, 78.0, 49.0)
        assertEquals(1.0, d.first, 1e-9)
        assertEquals(0.0, d.second, 1e-9)
    }
}
