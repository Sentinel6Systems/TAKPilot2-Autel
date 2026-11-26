package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewTest {

    @Test
    fun `the camera's display mode maps to a view by name`() {
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName("VISIBLE"))
        assertEquals(CameraView.IR, CameraView.fromDisplayModeName("IR"))
        assertEquals(CameraView.PIP, CameraView.fromDisplayModeName("PICTURE_IN_PICTURE"))
    }

    @Test
    fun `overlap is a blend and reads as PIP`() {
        // This firmware refuses Overlap (status -1, measured), but the SDK enum has it
        // and another firmware or Explorer could leave the camera in it. It has the video's
        // shape and the visible field, which is all a blend needs to be treated as.
        assertEquals(CameraView.PIP, CameraView.fromDisplayModeName("OVERLAP"))
    }

    @Test
    fun `an unknown or missing answer changes nothing`() {
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName("UNKNOWN"))
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName(null))
    }

    @Test
    fun `only the bare thermal sensor is shown whole and expects the thermal frame`() {
        // PIP is the 1280x720 composite (a recording off the card reads 1280x720p25). It
        // fills like the visible modes and the frame witness expects the video shape; a
        // "thermal" expectation there would flip the state back 1.5 s after every PIP write.
        assertTrue(CameraView.IR.fitsWhole)
        assertTrue(CameraView.IR.expectsThermalFrame)
        assertFalse(CameraView.PIP.fitsWhole)
        assertFalse(CameraView.PIP.expectsThermalFrame)
        assertFalse(CameraView.VISIBLE.fitsWhole)
        assertFalse(CameraView.VISIBLE.expectsThermalFrame)
    }

    @Test
    fun `the palette applies wherever the thermal sensor is on screen`() {
        assertFalse(CameraView.VISIBLE.hasThermal)
        assertTrue(CameraView.IR.hasThermal)
        assertTrue(CameraView.PIP.hasThermal)
    }

    @Test
    fun `the IR pill toggles RGB and full IR, and from PIP goes back to RGB`() {
        assertEquals(CameraView.IR, CameraView.VISIBLE.next)
        assertEquals(CameraView.VISIBLE, CameraView.IR.next)
        assertEquals(CameraView.VISIBLE, CameraView.PIP.next)
    }

    @Test
    fun `the PIP pill shows only in thermal, enters PIP from IR and leaves it to IR`() {
        assertEquals(CameraView.PIP, CameraView.IR.pipTarget)
        assertEquals(CameraView.IR, CameraView.PIP.pipTarget)
        assertEquals(null, CameraView.VISIBLE.pipTarget)
    }

    @Test
    fun `the IR pill is lit in IR and PIP, the PIP pill in PIP only`() {
        assertFalse(CameraView.VISIBLE.irLit); assertFalse(CameraView.VISIBLE.pipLit)
        assertTrue(CameraView.IR.irLit); assertFalse(CameraView.IR.pipLit)
        assertTrue(CameraView.PIP.irLit); assertTrue(CameraView.PIP.pipLit)
    }

    @Test
    fun `each view has its lens`() {
        assertEquals(AutelTakBridge.Lens.EO, CameraView.VISIBLE.lens)
        assertEquals(AutelTakBridge.Lens.IR, CameraView.IR.lens)
        assertEquals(AutelTakBridge.Lens.BLEND, CameraView.PIP.lens)
    }
}
