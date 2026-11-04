package br.com.plusapps.s23logvideo.record

import java.io.ByteArrayOutputStream

/**
 * Reescreve a marcação de cor (colour_primaries / transfer_characteristics / matrix_coeffs) no VUI do
 * SPS do HEVC.
 *
 * Por quê: com a sessão de câmera em HLG10, o encoder da Qualcomm marca o bitstream como HLG
 * (transfer_characteristics = 18, `arib-std-b67`), ignorando o KEY_COLOR_TRANSFER pedido. Em Log o
 * arquivo precisa sair marcado como SDR (BT.709, valor 1), senão o editor aplica tone mapping de HDR
 * por cima do Log. O tamanho do campo é fixo (8 bits), então só os bits mudam; os bytes de
 * emulation prevention são refeitos.
 */
object HevcColorPatch {
    const val TRANSFER_BT709 = 1
    const val TRANSFER_HLG = 18

    data class Color(val primaries: Int, val transfer: Int, val matrix: Int, val transferBit: Int)

    /** Lê a cor do VUI de um NAL de SPS (com o cabeçalho de 2 bytes, sem start code). null se não houver. */
    fun readColor(spsNal: ByteArray): Color? = runCatching { parse(unescape(spsNal)) }.getOrNull()

    /**
     * Devolve o SPS com transfer_characteristics = [transfer] (e o resto igual), ou o próprio NAL se não
     * houver descrição de cor no VUI ou o parse falhar.
     */
    fun patchSps(spsNal: ByteArray, transfer: Int): ByteArray {
        val rbsp = unescape(spsNal)
        val c = runCatching { parse(rbsp) }.getOrNull() ?: return spsNal
        if (c.transfer == transfer) return spsNal
        writeBits(rbsp, c.transferBit, 8, transfer)
        return escape(rbsp)
    }

    /**
     * Varre um buffer Annex B (start codes 00 00 01 / 00 00 00 01), reescreve os SPS e devolve o buffer
     * novo, ou null se não havia SPS para mudar.
     */
    fun patchAnnexB(data: ByteArray, transfer: Int): ByteArray? {
        val starts = startCodes(data)
        if (starts.isEmpty()) return null
        var changed = false
        val out = ByteArrayOutputStream(data.size + 16)
        out.write(data, 0, starts[0].first)
        for ((i, sc) in starts.withIndex()) {
            val (scPos, nalPos) = sc
            val end = if (i + 1 < starts.size) starts[i + 1].first else data.size
            out.write(data, scPos, nalPos - scPos)
            val nal = data.copyOfRange(nalPos, end)
            val type = (nal.getOrElse(0) { 0 }.toInt() shr 1) and 0x3F
            if (type == 33) {
                val patched = patchSps(nal, transfer)
                if (!patched.contentEquals(nal)) changed = true
                out.write(patched)
            } else {
                out.write(nal)
            }
        }
        return if (changed) out.toByteArray() else null
    }

    /** (posição do start code, posição do 1º byte do NAL). */
    private fun startCodes(d: ByteArray): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val sc = if (i > 0 && d[i - 1].toInt() == 0) i - 1 else i
                res += sc to i + 3
                i += 3
            } else i++
        }
        return res
    }

    // ---- RBSP ----

    /** Remove os bytes 0x03 de emulation prevention (00 00 03 → 00 00). */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Põe de volta os 0x03 onde aparecer 00 00 0x (x ≤ 3). */
    fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun writeBits(d: ByteArray, bitPos: Int, n: Int, value: Int) {
        for (k in 0 until n) {
            val bit = (value shr (n - 1 - k)) and 1
            val p = bitPos + k
            val idx = p / 8
            val mask = 0x80 ushr (p % 8)
            d[idx] = if (bit == 1) (d[idx].toInt() or mask).toByte() else (d[idx].toInt() and mask.inv()).toByte()
        }
    }

    private class Bits(val d: ByteArray, var pos: Int = 0) {
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                check(pos < d.size * 8) { "fim do SPS" }
                v = (v shl 1) or ((d[pos / 8].toInt() shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }

        fun skip(n: Int) {
            pos += n
            check(pos <= d.size * 8) { "fim do SPS" }
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                check(zeros < 32) { "ue inválido" }
            }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1 + u(zeros))
        }

        fun se(): Int {
            val k = ue()
            return if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    /** Parse do SPS (H.265 7.3.2.2) até a descrição de cor do VUI (E.2.1). */
    private fun parse(rbsp: ByteArray): Color? {
        val b = Bits(rbsp)
        b.skip(16) // cabeçalho do NAL
        b.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = b.u(3)
        b.skip(1) // temporal_id_nesting
        profileTierLevel(b, maxSubLayersMinus1)
        b.ue() // sps_seq_parameter_set_id
        val chroma = b.ue()
        if (chroma == 3) b.skip(1)
        b.ue(); b.ue() // largura, altura
        if (b.u(1) == 1) repeat(4) { b.ue() } // conformance window
        b.ue(); b.ue() // bit depth luma/croma
        b.ue() // log2_max_pic_order_cnt_lsb_minus4
        val orderingAll = b.u(1)
        val first = if (orderingAll == 1) 0 else maxSubLayersMinus1
        for (i in first..maxSubLayersMinus1) repeat(3) { b.ue() }
        repeat(6) { b.ue() } // tamanhos de bloco e profundidades de transformada
        if (b.u(1) == 1 && b.u(1) == 1) scalingListData(b)
        b.skip(1) // amp
        b.skip(1) // sao
        if (b.u(1) == 1) { // pcm
            b.skip(8)
            b.ue(); b.ue()
            b.skip(1)
        }
        val numSets = b.ue()
        val numDeltaPocs = IntArray(numSets)
        for (i in 0 until numSets) numDeltaPocs[i] = stRefPicSet(b, i, numDeltaPocs)
        if (b.u(1) == 1) { // long term refs
            val n = b.ue()
            val lsbBits = 0 // o tamanho depende de log2_max_poc_lsb; não usado pelos encoders de celular
            check(n == 0 || lsbBits > 0) { "long term refs no SPS não suportado" }
        }
        b.skip(1) // temporal_mvp
        b.skip(1) // strong_intra_smoothing
        if (b.u(1) == 0) return null // sem VUI
        if (b.u(1) == 1) { // aspect ratio
            if (b.u(8) == 255) b.skip(32)
        }
        if (b.u(1) == 1) b.skip(1) // overscan
        if (b.u(1) == 0) return null // sem video_signal_type
        b.skip(3) // video_format
        b.skip(1) // full range
        if (b.u(1) == 0) return null // sem colour_description
        val primaries = b.u(8)
        val transferBit = b.pos
        val transfer = b.u(8)
        val matrix = b.u(8)
        return Color(primaries, transfer, matrix, transferBit)
    }

    private fun profileTierLevel(b: Bits, maxSubLayersMinus1: Int) {
        b.skip(88) // general profile (2+1+5+32+4+43+1)
        b.skip(8) // general_level_idc
        val profilePresent = IntArray(maxSubLayersMinus1)
        val levelPresent = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = b.u(1)
            levelPresent[i] = b.u(1)
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) b.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i] == 1) b.skip(88)
            if (levelPresent[i] == 1) b.skip(8)
        }
    }

    private fun scalingListData(b: Bits) {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (b.u(1) == 0) {
                    b.ue()
                } else {
                    val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) b.se()
                    repeat(coefNum) { b.se() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    /** st_ref_pic_set (7.3.7) dentro do SPS; devolve NumDeltaPocs do conjunto. */
    private fun stRefPicSet(b: Bits, idx: Int, numDeltaPocs: IntArray): Int {
        val inter = if (idx != 0) b.u(1) else 0
        if (inter == 1) {
            b.skip(1) // delta_rps_sign
            b.ue() // abs_delta_rps_minus1
            val ref = idx - 1 // no SPS, delta_idx_minus1 = 0
            var count = 0
            for (j in 0..numDeltaPocs[ref]) {
                val used = b.u(1)
                val useDelta = if (used == 0) b.u(1) else 1
                if (used == 1 || useDelta == 1) count++
            }
            return count
        }
        val neg = b.ue()
        val pos = b.ue()
        repeat(neg + pos) {
            b.ue()
            b.skip(1)
        }
        return neg + pos
    }
}
