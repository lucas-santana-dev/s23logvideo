package br.com.plusapps.s23logvideo

import br.com.plusapps.s23logvideo.camera.WhiteBalance
import br.com.plusapps.s23logvideo.color.LogCurve
import br.com.plusapps.s23logvideo.gl.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Fase3a6Test {
    private fun lutFile(name: String): File =
        listOf(File("../luts/$name"), File("luts/$name")).first { it.exists() }

    @Test
    fun cubeTecnicaCarregaECinzaCaiEm0409() {
        val lut = CubeLut.parse(lutFile("LogCam_to_Rec709.cube").readText())
        assertEquals(33, lut.size)
        assertEquals(33 * 33 * 33 * 3, lut.data.size)
        // Interpolação trilinear no cinza Log (0,41,0,41,0,41): deve dar ~0,409 nos três canais.
        val g = LogCurve.GRAY_OUT.toFloat() * (lut.size - 1)
        val i0 = g.toInt()
        val f = g - i0
        fun at(i: Int, c: Int) = lut.data[((i * lut.size + i) * lut.size + i) * 3 + c]
        for (c in 0 until 3) {
            val v = at(i0, c) * (1 - f) + at(i0 + 1, c) * f
            assertEquals(0.409f, v, 0.01f)
        }
    }

    @Test
    fun cubeCriativaCarrega() {
        val lut = CubeLut.parse(lutFile("LogCam_Look_Filme.cube").readText())
        assertEquals(33, lut.size)
        assertTrue(lut.data.all { it in 0f..1f })
    }

    @Test(expected = IllegalStateException::class)
    fun cubeIncompletoFalha() {
        CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")
    }

    @Test
    fun locusDePlanck() {
        // Valores de referência do locus de Planck (CIE 1931).
        val (x65, y65) = WhiteBalance.planckianXy(6500.0)
        assertEquals(0.3135, x65, 0.002)
        assertEquals(0.3237, y65, 0.002)
        val (xa, ya) = WhiteBalance.planckianXy(2856.0)
        assertEquals(0.4476, xa, 0.002)
        assertEquals(0.4074, ya, 0.002)
    }
}
