package com.pedro.srt

import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.mpeg2ts.Pes
import com.pedro.srt.mpeg2ts.PesType
import com.pedro.srt.mpeg2ts.psi.PsiManager
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MPEG-TS packets the SRT path sends, read back the way a receiver reads them.
 *
 * ⚠ THE REGRESSION THIS FILE EXISTS FOR: a frame small enough to fit in its first
 * packet (under 162 bytes) went out with the stuffing inside the payload and a changed PTS
 * byte. The receiver lost that frame and every frame that depends on it.
 */
class MpegTsPacketizerTest {

    private fun packetize(size: Int, key: Boolean = false): List<ByteArray> {
        val data = ByteArray(size) { (it % 251 + 1).toByte() }
        val pes = Pes(256, key, PesType.VIDEO, 1_234_567L, ByteBuffer.wrap(data))
        return MpegTsPacketizer(PsiManager(Mpeg2TsService())).write(listOf(pes))
    }

    /** The payload of every packet, joined: what a demuxer hands to the PES parser. */
    private fun payload(packets: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        packets.forEachIndexed { i, p ->
            assertEquals(188, p.size)
            assertEquals(0x47, p[0].toInt() and 0xFF)
            assertEquals("payload_unit_start only on the first packet", i == 0, (p[1].toInt() shr 6) and 1 == 1)
            val afc = (p[3].toInt() shr 4) and 3
            var off = 4
            if (afc and 2 != 0) off += 1 + (p[4].toInt() and 0xFF)
            out.write(p, off, 188 - off)
        }
        return out.toByteArray()
    }

    @Test
    fun everyFrameSizeComesBackExactly() {
        for (size in listOf(1, 55, 60, 161, 162, 163, 184, 500, 5000)) {
            val got = payload(packetize(size))
            val want = ByteArray(size) { (it % 251 + 1).toByte() }
            assertEquals("size $size: PES header + frame, no stuffing in the payload",
                Pes.HEADER_LENGTH + size, got.size)
            assertArrayEquals("size $size", want, got.copyOfRange(Pes.HEADER_LENGTH, got.size))
            assertEquals("size $size: PES start code", 1, got[2].toInt())
        }
    }

    @Test
    fun aSmallFrameKeepsTheSameTimestampBytesAsALargeOne() {
        val small = payload(packetize(60)).copyOfRange(9, Pes.HEADER_LENGTH)
        val large = payload(packetize(5000)).copyOfRange(9, Pes.HEADER_LENGTH)
        assertArrayEquals(large, small)
    }

    @Test
    fun aSmallKeyframeAlsoFitsInOnePacket() {
        val p = packetize(100, key = true)
        assertEquals(1, p.size)
        assertEquals(Pes.HEADER_LENGTH + 100, payload(p).size)
    }
}
