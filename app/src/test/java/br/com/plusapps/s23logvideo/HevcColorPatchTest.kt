package br.com.plusapps.s23logvideo

import br.com.plusapps.s23logvideo.record.HevcColorPatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HevcColorPatchTest {
    /** SPS real do take LogCam_20261104-082326 (S23, 4K30 HLG10, c2.qti.hevc.encoder). */
    private val spsS23 = hex("420101222000000300b00000030000030099a001e02002207c4b65aee4c92ea6a12241205da14250")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun leACorDoS23() {
        val c = HevcColorPatch.readColor(spsS23)
        assertNotNull(c)
        assertEquals(9, c!!.primaries) // BT.2020
        assertEquals(HevcColorPatch.TRANSFER_HLG, c.transfer)
        assertEquals(9, c.matrix) // BT.2020 NCL
    }

    @Test
    fun trocaHlgPorBt709SemMexerNoResto() {
        val patched = HevcColorPatch.patchSps(spsS23, HevcColorPatch.TRANSFER_BT709)
        val c = HevcColorPatch.readColor(patched)!!
        assertEquals(9, c.primaries)
        assertEquals(1, c.transfer)
        assertEquals(9, c.matrix)
        // Só os 8 bits do transfer mudam (no RBSP).
        val a = HevcColorPatch.unescape(spsS23)
        val b = HevcColorPatch.unescape(patched)
        assertEquals(a.size, b.size)
        val diffBits = a.indices.sumOf { Integer.bitCount((a[it].toInt() xor b[it].toInt()) and 0xFF) }
        assertEquals(Integer.bitCount(18 xor 1), diffBits)
    }

    @Test
    fun annexBComVpsSpsPps() {
        val vps = hex("40010c01ffff")
        val pps = hex("4401c172b46240")
        val sc = hex("00000001")
        val buf = sc + vps + sc + spsS23 + sc + pps
        val out = HevcColorPatch.patchAnnexB(buf, 1)!!
        val again = HevcColorPatch.patchAnnexB(out, 1)
        assertNull(again) // já está em BT.709
        assertArrayEquals(sc + vps + sc, out.copyOfRange(0, 4 + vps.size + 4))
    }

    @Test
    fun escapeDesfazUnescape() {
        val raw = hex("00000301000003020000030004")
        assertArrayEquals(raw, HevcColorPatch.escape(HevcColorPatch.unescape(raw)))
    }
}
