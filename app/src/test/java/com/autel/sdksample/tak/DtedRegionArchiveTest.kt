package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A terrain region is imported as one .zip and nothing else (see [DtedStore.isRegionArchive]).
 * A stored tile is named with [DtedStore.poolName].
 */
class DtedRegionArchiveTest {

    /**
     * A region zip names each tile by LATITUDE ONLY and keeps the longitude in the folder, so
     * "n45.dt2" is in the archive once per longitude. Every one of them must get its own file:
     * if two shared a name, the later one would silently replace the earlier and the region
     * would still list its full tile count.
     */
    @Test
    fun everyTileOfARegionZipGetsItsOwnFile() {
        val entries = mutableListOf<String>()
        for (lon in 97..105) for (lat in 43..47) {
            entries += "w$lon/n$lat.dt0"
            entries += "w$lon/n$lat.dt2"
            entries += "w$lon/n$lat.dt3"
        }
        val stored = entries.map { DtedStore.poolName(it)!! }
        assertEquals(entries.size, stored.toSet().size)
        assertTrue("w101_n45.dt2" in stored && "w102_n45.dt2" in stored)
    }

    /** Only a region .zip is imported. A tile picked by itself has lost its longitude folder. */
    @Test
    fun onlyAZipIsARegionArchive() {
        assertTrue(DtedStore.isRegionArchive("region-dt3.zip"))
        assertTrue(DtedStore.isRegionArchive("AREA.ZIP"))
        assertTrue(DtedStore.isRegionArchive(" area.zip "))
        for (loose in listOf("n45.dt2", "n45.dt0", "w101_n45.dt3", "n45", "", "area.zip.dt2", "zip")) {
            assertTrue("\"$loose\" must be refused", !DtedStore.isRegionArchive(loose))
        }
    }

    // A picker can give a zip with no ".zip" in its name. Its first bytes say what it is; a
    // tile starts "UHL1" and is still refused.
    @Test
    fun aZipIsKnownByItsFirstBytes() {
        assertTrue(DtedStore.isZipHeader(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertTrue(DtedStore.isZipHeader(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 20, 0)))
        assertTrue(!DtedStore.isZipHeader("UHL1".toByteArray()))
        assertTrue(!DtedStore.isZipHeader(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        assertTrue(!DtedStore.isZipHeader(ByteArray(0)))
    }

    // A zip made on a Mac carries "._" files that end like tiles. They are not tiles.
    @Test
    fun finderFilesInAZipAreNotTiles() {
        assertTrue(DtedStore.isJunkEntry("__MACOSX/w101/._n45.dt2"))
        assertTrue(DtedStore.isJunkEntry("w101/._n45.dt2"))
        assertTrue(DtedStore.isJunkEntry("._n45.dt2"))
        assertTrue(DtedStore.isJunkEntry("__MACOSX\\w101\\n45.dt2"))
        assertTrue(!DtedStore.isJunkEntry("w101/n45.dt2"))
        assertTrue(!DtedStore.isJunkEntry("region/w101/n45.dt3"))
        assertTrue(!DtedStore.isJunkEntry("n45.dt2"))
    }
}
