package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassReliabilityTest {

    private val earth = 55.5   // microtesla, a typical field of the Earth

    @Test
    fun `a good status and the Earth's field is a reliable compass`() {
        assertTrue(compassReliable(3, 55.0, earth))
        assertTrue(compassReliable(2, 60.0, earth))
    }

    @Test
    fun `the sensor's own low or unreliable status is enough`() {
        assertFalse(compassReliable(0, 55.0, earth))
        assertFalse(compassReliable(1, 55.0, earth))
    }

    @Test
    fun `a field a quarter too strong or too weak is a disturbed compass`() {
        // In a vehicle: steel and current add their own field.
        assertFalse(compassReliable(3, 75.0, earth))
        assertFalse(compassReliable(3, 38.0, earth))
        assertTrue(compassReliable(3, 68.0, earth))
        assertTrue(compassReliable(3, 43.0, earth))
    }

    @Test
    fun `the way back needs a field nearer to the Earth's`() {
        // 23 % off: still reliable if it was, not yet reliable again if it was not.
        val v = earth * 1.23
        assertTrue(compassReliable(3, v, earth, wasReliable = true))
        assertFalse(compassReliable(3, v, earth, wasReliable = false))
        assertTrue(compassReliable(3, earth * 1.15, earth, wasReliable = false))
    }

    @Test
    fun `what is not known is not a sign of a bad compass`() {
        assertTrue(compassReliable(null, null, null))
        assertTrue(compassReliable(3, null, earth))
        assertTrue(compassReliable(3, 80.0, null))
    }
}
