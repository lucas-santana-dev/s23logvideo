package br.com.plusapps.s23logvideo.record

import br.com.plusapps.s23logvideo.color.LogCurve
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Marca o MP4 como Log com metadados QuickTime (`com.logcam.logprofile` etc.), o mesmo mecanismo que o
 * Apple Log usa (`com.apple.proapps.logprofile`).
 *
 * Por quê: o padrão de vídeo não tem um código de transferência para a curva do LogCam, então a tag de cor
 * só pode dizer "não especificada". Sem outra pista, editores e ferramentas leem "SDR normal". Estes
 * metadados dizem explicitamente que é Log e com qual curva.
 *
 * O MediaMuxer escreve o `moov` por último, depois do `mdat`, e já grava nele um bloco `meta` (com
 * `com.android.version`). As chaves novas são MESCLADAS nesse bloco (o ffmpeg só lê um `meta` por arquivo)
 * e só o `moov` é reescrito: nenhum offset de amostra muda. O `tools/marcar_log.py` faz o mesmo em arquivos
 * já gravados.
 */
object Mp4LogTag {
    fun tags(): List<Pair<String, String>> = listOf(
        "com.logcam.logprofile" to "LogCam Log 10-bit",
        "com.logcam.curve" to "y = a*log10(x + c) + d; a=%.6f c=%.6f d=%.6f; preto=%s cinza18=%s branco=%s".format(
            java.util.Locale.US, LogCurve.a, LogCurve.c, LogCurve.d,
            LogCurve.BLACK_OUT, LogCurve.GRAY_OUT, LogCurve.WHITE_OUT,
        ),
        "com.logcam.gamut" to "BT.2020",
        "com.logcam.lut" to "LogCam_to_Rec709.cube",
    )

    /** Grava as tags no arquivo (já finalizado pelo muxer). Devolve false, sem alterar nada, se não der. */
    fun apply(fd: FileDescriptor, tags: List<Pair<String, String>> = tags()): Boolean = runCatching {
        val input = FileInputStream(fd).channel
        val size = input.size()
        // Procura o moov entre os boxes de topo, lendo só os cabeçalhos.
        var pos = 0L
        var moovPos = -1L
        var moovSize = 0L
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= size) {
            hdr.clear()
            input.read(hdr, pos)
            hdr.flip()
            var s = (hdr.int.toLong() and 0xFFFFFFFFL)
            val type = ByteArray(4).also { hdr.get(it) }.toString(Charsets.ISO_8859_1)
            if (s == 1L) s = hdr.long else if (s == 0L) s = size - pos
            if (s < 8) return@runCatching false
            if (type == "moov") { moovPos = pos; moovSize = s }
            pos += s
        }
        // Só se o moov for o último box e tiver tamanho de 32 bits (é o que o MediaMuxer produz).
        if (moovPos < 0 || moovPos + moovSize != size || moovSize > 64 * 1024 * 1024) return@runCatching false
        val old = ByteBuffer.allocate(moovSize.toInt())
        input.read(old, moovPos)
        val merged = mergeMoov(old.array(), tags) ?: return@runCatching false
        val out = FileOutputStream(fd).channel
        out.write(ByteBuffer.wrap(merged), moovPos)
        out.truncate(moovPos + merged.size)
        out.force(true)
        true
    }.getOrDefault(false)

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    private fun u32(vararg v: Int): ByteArray =
        ByteBuffer.allocate(4 * v.size).order(ByteOrder.BIG_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()

    private class Child(val type: String, val start: Int, val end: Int)

    private fun children(b: ByteArray, start: Int, end: Int): List<Child> {
        val out = ArrayList<Child>()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        var pos = start
        while (pos + 8 <= end) {
            val size = bb.getInt(pos)
            if (size < 8 || pos + size > end) break
            out += Child(String(b, pos + 4, 4, Charsets.ISO_8859_1), pos, pos + size)
            pos += size
        }
        return out
    }

    /** `meta` QuickTime (sem versão/flags) com chaves `mdta`, para arquivos sem meta prévio. */
    private fun metaFrom(tags: List<Pair<String, String>>): ByteArray {
        val hdlr = box("hdlr", u32(0) + byteArrayOf(0, 0, 0, 0) + "mdta".toByteArray() + ByteArray(12) + byteArrayOf(0))
        val keys = box("keys", u32(0, tags.size) + tags.map { box("mdta", it.first.toByteArray()) }.fold(ByteArray(0)) { a, c -> a + c })
        var items = ByteArray(0)
        tags.forEachIndexed { i, (_, v) ->
            val data = box("data", u32(1, 0) + v.toByteArray())
            items += u32(8 + data.size, i + 1) + data
        }
        return box("meta", hdlr + keys + box("ilst", items))
    }

    /**
     * Devolve o `moov` com as [tags] mescladas no `meta` existente (ou um `meta` novo, se não houver).
     * null se o moov não tiver o formato esperado. Função pura, testada em JVM com um moov real do S23.
     */
    fun mergeMoov(moov: ByteArray, tags: List<Pair<String, String>>): ByteArray? {
        val bb = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
        if (moov.size < 16 || bb.getInt(0) != moov.size || String(moov, 4, 4, Charsets.ISO_8859_1) != "moov") return null
        if (String(moov).contains("com.logcam.logprofile")) return moov // já marcado

        val meta = children(moov, 8, moov.size).firstOrNull { it.type == "meta" }
        val rebuiltBody: ByteArray
        if (meta == null) {
            rebuiltBody = moov.copyOfRange(8, moov.size) + metaFrom(tags)
        } else {
            // O meta do Android/QuickTime NÃO tem versão/flags (o hdlr vem logo após o cabeçalho); o ISO tem.
            val head = if (meta.start + 16 <= meta.end && String(moov, meta.start + 12, 4, Charsets.ISO_8859_1) == "hdlr") 8 else 12
            val inner = children(moov, meta.start + head, meta.end)
            val keys = inner.firstOrNull { it.type == "keys" } ?: return null
            val ilst = inner.firstOrNull { it.type == "ilst" } ?: return null
            val count = bb.getInt(keys.start + 12)
            val newKeys = box(
                "keys",
                u32(0, count + tags.size) + moov.copyOfRange(keys.start + 16, keys.end) +
                    tags.map { box("mdta", it.first.toByteArray()) }.fold(ByteArray(0)) { a, c -> a + c },
            )
            var items = ByteArray(0)
            tags.forEachIndexed { i, (_, v) ->
                val data = box("data", u32(1, 0) + v.toByteArray())
                items += u32(8 + data.size, count + 1 + i) + data
            }
            val newIlst = box("ilst", moov.copyOfRange(ilst.start + 8, ilst.end) + items)
            var parts = ByteArray(0)
            for (c in inner) {
                parts += when {
                    c.start == keys.start -> newKeys
                    c.start == ilst.start -> newIlst
                    else -> moov.copyOfRange(c.start, c.end)
                }
            }
            val newMeta = u32(head + parts.size).let { it + "meta".toByteArray() + moov.copyOfRange(meta.start + 8, meta.start + head) + parts }
            rebuiltBody = moov.copyOfRange(8, meta.start) + newMeta + moov.copyOfRange(meta.end, moov.size)
        }
        return u32(8 + rebuiltBody.size) + "moov".toByteArray() + rebuiltBody
    }
}
