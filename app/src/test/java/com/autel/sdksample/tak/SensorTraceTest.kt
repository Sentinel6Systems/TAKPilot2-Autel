package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the raw sensor record. The GPS heights in millimetres come from the text of
 * `GPSInfoInternal.toString()`, because the SDK getters give whole metres. The text below has
 * the form of a line of the SDK's own log; its time, position and heights are made up.
 */
class SensorTraceTest {

    private val sdkText = "Timestamp:1700000000000000 Lat:451167013 Lon:-1008943049 Alt:41235 hmsl:28406 " +
        "VelocityNorth:-9 VelocityEast:4 VelocityDown:0 GPSSpeed:10 HorizontalAccuracy:2473 " +
        "VerticalAccuracy:768 SpeedAccurary:370 TimeUsec:0.0 VelNedValid:0, Fixtype : 3, SatellitesVisible : 27"

    @Test
    fun rawHeightsComeFromTheSdkText() {
        assertEquals(41235 to 28406, SensorTrace.rawGps(sdkText))
    }

    @Test
    fun negativeHeightsAreRead() {
        assertEquals(-1500 to -13160, SensorTrace.rawGps("Lon:5 Alt:-1500 hmsl:-13160 VelocityNorth:0"))
    }

    @Test
    fun textWithoutTheHeightsGivesNull() {
        assertNull(SensorTrace.rawGps("HomeAltitude:12 Lat:3"))
        assertNull(SensorTrace.rawGps(""))
    }

    @Test
    fun headerHasTheColumnsTheAnalysisReads() {
        // The header is a fixed contract for readers of the file.
        assertEquals(54, SensorTrace.CSV_HEADER.trim().split(',').size)
    }
}
