package br.com.plusapps.s23logvideo.gl

/** LUT 3D no formato .cube (Resolve/Adobe): `LUT_3D_SIZE n` e n³ linhas "r g b", R variando mais rápido. */
class CubeLut(val size: Int, val data: FloatArray) {
    companion object {
        fun parse(text: String): CubeLut {
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> Unit
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> error("LUT 1D não suportada")
                    "DOMAIN_MIN" -> min = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> max = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    else -> if (parts.size >= 3) {
                        values += parts[0].toFloat()
                        values += parts[1].toFloat()
                        values += parts[2].toFloat()
                    }
                }
            }
            check(size in 2..129) { "LUT_3D_SIZE inválido: $size" }
            check(values.size == size * size * size * 3) { "esperava ${size * size * size} linhas, achei ${values.size / 3}" }
            check(min.all { it == 0f } && max.all { it == 1f }) { "só DOMAIN 0..1 é suportado" }
            return CubeLut(size, values.toFloatArray())
        }
    }
}
