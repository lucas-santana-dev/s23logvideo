package br.com.plusapps.s23logvideo.color

import kotlin.math.log10
import kotlin.math.pow

/**
 * Curva Log PROVISÓRIA da Fase 0.
 *
 * Forma: y = a · log10(x + c) + d, com x = luz linear do sensor (0..1) e y = valor gravado (0..1).
 * As três constantes (a, c, d) são resolvidas a partir de três âncoras:
 *   preto  (x = 0)       → BLACK_OUT
 *   cinza  (x = GRAY_IN) → GRAY_OUT
 *   branco (x = 1)       → WHITE_OUT
 * O offset c já dá o trecho quase linear perto do preto, então não há segmento separado.
 *
 * Este é o ÚNICO lugar das constantes: a curva do ISP, o shader e a LUT devem sair daqui.
 * A forma definitiva (e onde o cinza cai no sinal linear do sensor) é decidida na Fase 2.
 */
object LogCurve {
    const val GRAY_IN = 0.18
    const val GRAY_OUT = 0.41
    const val BLACK_OUT = 0.093
    const val WHITE_OUT = 0.94

    val c: Double
    val a: Double
    val d: Double

    init {
        // r(c) = log((g+c)/c) / log((1+c)/c) cai de 1 (c→0) até g (c→∞); bisseção em log(c).
        val target = (GRAY_OUT - BLACK_OUT) / (WHITE_OUT - BLACK_OUT)
        fun ratio(cc: Double) = log10((GRAY_IN + cc) / cc) / log10((1 + cc) / cc)
        var lo = -6.0
        var hi = 1.0
        repeat(100) {
            val mid = (lo + hi) / 2
            if (ratio(10.0.pow(mid)) > target) lo = mid else hi = mid
        }
        c = 10.0.pow((lo + hi) / 2)
        a = (WHITE_OUT - BLACK_OUT) / log10((1 + c) / c)
        d = BLACK_OUT - a * log10(c)
    }

    /** Linear → Log. */
    fun encode(x: Double): Double = a * log10(x.coerceAtLeast(0.0) + c) + d

    /** Log → linear (inversa, para a LUT da Fase 6). */
    fun decode(y: Double): Double = 10.0.pow((y - d) / a) - c

    /**
     * Pontos (entrada, saída) intercalados no formato de TonemapCurve, com ENTRADAS
     * IGUALMENTE ESPAÇADAS. Na rodada 1, pontos densos nas sombras deixaram a imagem escura
     * no S23, sinal de que o HAL ignora o Pin e trata a lista como uma LUT uniforme.
     * Com espaçamento uniforme, as duas leituras dão o mesmo resultado.
     */
    fun tonemapPoints(count: Int): FloatArray {
        val n = count.coerceAtLeast(2)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i.toDouble() / (n - 1)
            out[i * 2] = x.toFloat()
            out[i * 2 + 1] = encode(x).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    fun describe(): String =
        "y = %.5f·log10(x + %.5f) + %.5f (preto %.3f, cinza18 %.3f, branco %.3f)".format(
            a, c, d, encode(0.0), encode(GRAY_IN), encode(1.0)
        )
}
