package br.com.plusapps.s23logvideo

import br.com.plusapps.s23logvideo.record.Mp4LogTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Usa o moov real de um take do S23 (MediaMuxer do Android 16): meta QuickTime com com.android.version. */
class Mp4LogTagTest {
    private val moov = javaClass.getResourceAsStream("/moov_s23.bin")!!.readBytes()

    private fun u32(b: ByteArray, p: Int) = ByteBuffer.wrap(b).getInt(p)
    private fun type(b: ByteArray, p: Int) = String(b, p + 4, 4, Charsets.ISO_8859_1)
    private fun kids(b: ByteArray, s: Int, e: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = s
        while (p + 8 <= e) {
            val size = u32(b, p)
            out += Triple(type(b, p), p, p + size)
            p += size
        }
        return out
    }

    @Test
    fun mesclaNoMetaExistenteEPreservaOResto() {
        assertEquals(2850, moov.size)
        val tags = Mp4LogTag.tags()
        val merged = Mp4LogTag.mergeMoov(moov, tags)
        assertNotNull(merged)
        merged!!
        assertEquals(merged.size, u32(merged, 0)) // tamanho do moov consistente

        val top = kids(merged, 8, merged.size)
        assertEquals(1, top.count { it.first == "meta" }) // um único meta (o ffmpeg lê só um)
        val meta = top.first { it.first == "meta" }
        val inner = kids(merged, meta.second + 8, meta.third) // QuickTime: sem versão/flags
        val keys = inner.first { it.first == "keys" }
        val count = u32(merged, keys.second + 12)
        assertEquals(1 + tags.size, count)

        // Nomes das chaves, na ordem: a do Android primeiro, as nossas depois.
        var p = keys.second + 16
        val names = ArrayList<String>()
        repeat(count) {
            val size = u32(merged, p)
            names += String(merged, p + 8, size - 8)
            p += size
        }
        assertEquals("com.android.version", names[0])
        assertEquals(tags.map { it.first }, names.drop(1))

        // Cada item do ilst aponta (pelo tipo = índice 1-based) para a chave certa, com o valor certo.
        val ilst = inner.first { it.first == "ilst" }
        val items = HashMap<Int, String>()
        var q = ilst.second + 8
        while (q < ilst.third) {
            val size = u32(merged, q)
            val index = u32(merged, q + 4)
            val dataSize = u32(merged, q + 8)
            items[index] = String(merged, q + 8 + 16, dataSize - 16)
            q += size
        }
        assertEquals("16", items[1])
        tags.forEachIndexed { i, (_, v) -> assertEquals(v, items[i + 2]) }

        // Tudo o que não é o meta (trak, mvhd, udta) fica byte a byte igual: nenhum offset de amostra muda.
        val oldRest = kids(moov, 8, moov.size).filter { it.first != "meta" }.map { moov.copyOfRange(it.second, it.third) }
        val newRest = top.filter { it.first != "meta" }.map { merged.copyOfRange(it.second, it.third) }
        assertEquals(oldRest.size, newRest.size)
        oldRest.zip(newRest).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun jaMarcadoNaoMarcaDeNovo() {
        val once = Mp4LogTag.mergeMoov(moov, Mp4LogTag.tags())!!
        assertArrayEquals(once, Mp4LogTag.mergeMoov(once, Mp4LogTag.tags()))
    }

    @Test
    fun naoMexeEmArquivoQueNaoEMoov() {
        assertNull(Mp4LogTag.mergeMoov(ByteArray(40), Mp4LogTag.tags()))
    }

    @Test
    fun tagsDizemLogEBt2020() {
        val t = Mp4LogTag.tags().toMap()
        assertEquals("LogCam Log 10-bit", t["com.logcam.logprofile"])
        assertEquals("BT.2020", t["com.logcam.gamut"])
        assertTrue(t["com.logcam.curve"]!!.contains("a=1.011514"))
    }
}
