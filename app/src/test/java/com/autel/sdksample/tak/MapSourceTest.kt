package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The Map Display address rules: what is refused, and what the flight map then shows. */
class MapSourceTest {

    @Test
    fun `a good https address with z x y is accepted`() {
        assertNull(MapSource.validateCustomUrl("https://tiles.example.com/{z}/{x}/{y}.png"))
        assertNull(MapSource.validateCustomUrl("  HTTPS://tiles.example.com/{Z}/{X}/{Y}.jpg "))
    }

    @Test
    fun `http, no scheme, empty and missing placeholders are refused`() {
        assertNotNull(MapSource.validateCustomUrl("http://tiles.example.com/{z}/{x}/{y}.png"))
        assertNotNull(MapSource.validateCustomUrl("tiles.example.com/{z}/{x}/{y}.png"))
        assertNotNull(MapSource.validateCustomUrl("   "))
        val missing = MapSource.validateCustomUrl("https://tiles.example.com/{z}/{x}.png")
        assertEquals(true, missing!!.contains("{y}"))
    }

    @Test
    fun `the tile address takes z x and y in any letter case`() {
        assertEquals("https://t.example.com/12/650/1201.png",
            MapSource.tileUrl("https://t.example.com/{z}/{x}/{y}.png", 12, 650, 1201))
        assertEquals("https://t.example.com/3/2/1",
            MapSource.tileUrl(" https://t.example.com/{Z}/{X}/{Y} ", 3, 2, 1))
    }

    @Test
    fun `a custom choice with no good address shows Street, not a blank map`() {
        assertEquals(MapSource.STREET, MapSource.effective(MapSource.CUSTOM, ""))
        assertEquals(MapSource.STREET, MapSource.effective(MapSource.CUSTOM, "http://x/{z}/{x}/{y}"))
        assertEquals("custom:https://t.example.com/{z}/{x}/{y}.png",
            MapSource.effective(MapSource.CUSTOM, "https://t.example.com/{z}/{x}/{y}.png"))
        assertEquals(MapSource.HYBRID, MapSource.effective(MapSource.HYBRID, ""))
        assertEquals(MapSource.STREET, MapSource.effective("anything else", ""))
    }
}
