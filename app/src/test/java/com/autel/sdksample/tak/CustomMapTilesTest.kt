package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of the custom map download: which tiles a circle needs, and the detail that
 * fits the size limit. The network and the files are not tested here.
 */
class CustomMapTilesTest {

    @Test
    fun theTileOfAKnownPlaceIsTheStandardOne() {
        // The standard web-map grid: zoom 0 is one tile, and 0 N 0 E is the corner of four
        // tiles at zoom 1.
        assertEquals(0, CustomMapTiles.tileX(-100.0, 0))
        assertEquals(0, CustomMapTiles.tileY(45.0, 0))
        assertEquals(1, CustomMapTiles.tileX(0.0, 1))
        assertEquals(1, CustomMapTiles.tileY(-0.0001, 1))
        assertEquals(0, CustomMapTiles.tileY(0.0001, 1))
        // Worked by hand from the grid's formula: 45 N 100 W at zoom 10.
        assertEquals(227, CustomMapTiles.tileX(-100.0, 10))
        assertEquals(368, CustomMapTiles.tileY(45.0, 10))
    }

    @Test
    fun theCircleHoldsTheTileOfItsCenterAtEveryZoom() {
        for (z in CustomMapTiles.BASE_ZOOM..CustomMapTiles.TOP_ZOOM) {
            val tiles = CustomMapTiles.tilesInCircle(45.0, -100.0, 1.0, z)
            val center = CustomMapTiles.Tile(z, CustomMapTiles.tileX(-100.0, z), CustomMapTiles.tileY(45.0, z))
            assertTrue("zoom $z", tiles.contains(center))
        }
    }

    @Test
    fun aLargerRadiusAndAHigherZoomNeedMoreTiles() {
        val small = CustomMapTiles.tilesInCircle(45.0, -100.0, 2.0, 14).size
        val large = CustomMapTiles.tilesInCircle(45.0, -100.0, 10.0, 14).size
        val finer = CustomMapTiles.tilesInCircle(45.0, -100.0, 2.0, 15).size
        assertTrue(large > small * 10)
        assertTrue(finer > small * 2)
    }

    @Test
    fun theCircleNeedsFewerTilesThanItsBox() {
        // About pi / 4 of the box, plus the tiles at the edge.
        val b = CustomMapTiles.boundsOf(45.0, -100.0, 10.0)
        val z = 15
        val box = (CustomMapTiles.tileX(b.east, z) - CustomMapTiles.tileX(b.west, z) + 1) *
            (CustomMapTiles.tileY(b.south, z) - CustomMapTiles.tileY(b.north, z) + 1)
        val circle = CustomMapTiles.tilesInCircle(45.0, -100.0, 10.0, z).size
        assertTrue("$circle of $box", circle < box && circle > box * 0.7)
    }

    @Test
    fun theDetailGoesDownUntilTheDownloadFitsTheLimit() {
        // Large tiles, so that 25 miles to the top zoom is more than the limit.
        val perTile = 80_000L
        val small = CustomMapTiles.pickMaxZoom(45.0, -100.0, 2.0) { perTile }
        val large = CustomMapTiles.pickMaxZoom(45.0, -100.0, 25.0) { perTile }
        assertEquals("a small circle gets the top zoom", CustomMapTiles.TOP_ZOOM, small)
        assertNotNull(large)
        assertTrue("a large circle gets less", large!! < CustomMapTiles.TOP_ZOOM)
        val bytes = CustomMapTiles.countUpTo(45.0, -100.0, 25.0, large).toLong() * perTile
        assertTrue(bytes <= CustomMapTiles.LIMIT_BYTES)
    }

    @Test
    fun aDownloadThatCannotFitIsRefused() {
        assertNull(CustomMapTiles.pickMaxZoom(45.0, -100.0, 25.0, limitBytes = 1_000_000L) { 50_000L })
    }

    @Test
    fun theAddressOfATileTakesTheThreeNumbersInAnyLetterCase() {
        val t = CustomMapTiles.Tile(9, 42, 147)
        assertEquals("https://m.example.com/9/42/147.jpg",
            CustomMapTiles.urlFor("https://m.example.com/{z}/{x}/{y}.jpg", t))
        assertEquals("https://m.example.com/9/42/147.jpg",
            CustomMapTiles.urlFor(" https://m.example.com/{Z}/{X}/{Y}.jpg ", t))
    }

    @Test
    fun aDeeperTileIsCutFromItsSavedAncestor() {
        // The near view asks zoom 18; the download stops at 16. Tile (18, 1001, 2002) is the
        // second column and third row of the 4 x 4 grid inside (16, 250, 500).
        val t = CustomMapTiles.Tile(18, 1001, 2002)
        assertEquals(CustomMapTiles.Tile(16, 250, 500), CustomMapTiles.ancestor(t, 16))
        assertEquals(Triple(1, 2, 4), CustomMapTiles.partOf(t, 16))
        // Its own zoom is itself, whole.
        assertEquals(t, CustomMapTiles.ancestor(t, 18))
        assertEquals(Triple(0, 0, 1), CustomMapTiles.partOf(t, 18))
    }
}
