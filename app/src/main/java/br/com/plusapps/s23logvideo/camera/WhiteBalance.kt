package br.com.plusapps.s23logvideo.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Temperatura de cor (Kelvin) → ganhos RGGB do sensor (COLOR_CORRECTION_GAINS).
 *
 * Método do DNG: a cor neutra no espaço do sensor, para um iluminante de cromaticidade xy, é
 * `CM · XYZ(xy)`, onde CM (XYZ → sensor) é interpolada em 1/CCT entre as duas calibrações do aparelho
 * (SENSOR_COLOR_TRANSFORM1/2 × SENSOR_CALIBRATION_TRANSFORM1/2). Os ganhos são o inverso dessa cor
 * neutra, normalizados com o menor ganho = 1.
 *
 * Sem dados de calibração, cai numa aproximação (Tanner Helland) relativa aos ganhos medidos pelo AWB.
 */
class WhiteBalance(ch: CameraCharacteristics) {
    private val cct1 = illuminantCct(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
    private val cct2 = illuminantCct(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
    private val cm1 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toMatrix()
    private val cm2 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toMatrix()
    private val cal1 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toMatrix() ?: IDENTITY
    private val cal2 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toMatrix() ?: IDENTITY

    val calibrated get() = cm1 != null && cct1 != null

    /**
     * @param autoGains ganhos que o AWB devolveu por último (usados só no modo sem calibração).
     * @param autoKelvin temperatura que se supõe para [autoGains] nesse modo.
     */
    fun gains(kelvin: Int, autoGains: RggbChannelVector? = null, autoKelvin: Int = 5600): RggbChannelVector? {
        val k = kelvin.toDouble().coerceIn(1700.0, 20000.0)
        if (calibrated) {
            val cm = interpolatedMatrix(k)
            val (x, y) = planckianXy(k)
            val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
            val n = mul(cm, xyz)
            if (n.any { it <= 1e-6 }) return null
            val r = n[1] / n[0]
            val b = n[1] / n[2]
            val min = minOf(r, 1.0, b)
            return RggbChannelVector((r / min).toFloat(), (1 / min).toFloat(), (1 / min).toFloat(), (b / min).toFloat())
        }
        val ref = autoGains ?: return null
        val now = helland(k)
        val base = helland(autoKelvin.toDouble())
        val r = ref.red * (base[0] / now[0])
        val g = ref.greenEven.toDouble()
        val b = ref.blue * (base[2] / now[2])
        val min = minOf(r, g, b)
        return RggbChannelVector((r / min).toFloat(), (g / min).toFloat(), (g / min).toFloat(), (b / min).toFloat())
    }

    private fun interpolatedMatrix(k: Double): Array<DoubleArray> {
        val m1 = mul(cal1, cm1!!)
        if (cm2 == null || cct2 == null || cct1 == cct2) return m1
        val m2 = mul(cal2, cm2)
        // Peso em 1/CCT, como no DNG; fora do intervalo usa a matriz mais próxima.
        val w = ((1 / k - 1 / cct2) / (1 / cct1!! - 1 / cct2)).coerceIn(0.0, 1.0)
        return Array(3) { r -> DoubleArray(3) { c -> w * m1[r][c] + (1 - w) * m2[r][c] } }
    }

    companion object {
        private val IDENTITY = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))

        /** ColorSpaceTransform.getElement(coluna, linha). */
        private fun ColorSpaceTransform.toMatrix() =
            Array(3) { r -> DoubleArray(3) { c -> getElement(c, r).toDouble() } }

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(3) { r -> DoubleArray(3) { c -> (0 until 3).sumOf { a[r][it] * b[it][c] } } }

        private fun mul(a: Array<DoubleArray>, v: DoubleArray) =
            DoubleArray(3) { r -> (0 until 3).sumOf { a[r][it] * v[it] } }

        /** CCT aproximada dos iluminantes de referência (valores de LightSource do EXIF). */
        fun illuminantCct(v: Int?): Double? = when (v) {
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT -> 5500.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4150.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5500.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER -> 5500.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6500.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7500.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5000.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3525.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A -> 2856.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D55 -> 5503.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65 -> 6504.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7504.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            else -> null
        }

        /** Locus de Planck em xy (Kim et al., 2002), válido de 1667 K a 25000 K. */
        fun planckianXy(t: Double): Pair<Double, Double> {
            val x = if (t <= 4000) {
                -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
            } else {
                -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
            }
            val y = when {
                t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
            }
            return x to y
        }

        /** RGB (0..1) aproximado de um corpo negro (Tanner Helland). Só para o modo sem calibração. */
        private fun helland(k: Double): DoubleArray {
            val t = k / 100
            val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val b = when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> 138.5177312231 * ln(t - 10) - 305.0447927307
            }
            return doubleArrayOf(r, g, b).map { (it / 255).coerceIn(0.05, 1.0) }.toDoubleArray()
        }
    }
}
