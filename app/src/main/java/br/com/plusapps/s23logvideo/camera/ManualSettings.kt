package br.com.plusapps.s23logvideo.camera

import org.json.JSONArray
import org.json.JSONObject

/** AUTO: AE da câmera. LOCK: mede no automático e trava (CONTROL_AE_LOCK). MANUAL: ISO + obturador. */
enum class ExposureMode { AUTO, LOCK, MANUAL }

/** AUTO: AWB. LOCK: trava o balanço atual (CONTROL_AWB_LOCK). KELVIN: temperatura escolhida. */
enum class WbMode { AUTO, LOCK, KELVIN }

/** AUTO: AF contínuo de vídeo. MANUAL: distância fixa (LENS_FOCUS_DISTANCE, em dioptrias). */
enum class FocusMode { AUTO, MANUAL }

/**
 * Ajustes de câmera da Fase 3. Em Log tudo deve ficar travado durante o take: qualquer ajuste
 * automático no meio do clipe aparece depois na correção de cor.
 */
data class ManualSettings(
    val exposure: ExposureMode = ExposureMode.AUTO,
    val iso: Int = 100,
    val exposureNs: Long = 1_000_000_000L / 60,
    val wb: WbMode = WbMode.AUTO,
    val kelvin: Int = 5600,
    val focus: FocusMode = FocusMode.AUTO,
    /** Dioptrias (1/metros); 0 = infinito. */
    val focusDiopters: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("exposicao", exposure.name).put("iso", iso).put("exposicaoNs", exposureNs)
        .put("wb", wb.name).put("kelvin", kelvin)
        .put("foco", focus.name).put("focoDioptrias", focusDiopters.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = ManualSettings(
            exposure = enumOr(o.optString("exposicao"), ExposureMode.AUTO),
            iso = o.optInt("iso", 100),
            exposureNs = o.optLong("exposicaoNs", 1_000_000_000L / 60),
            wb = enumOr(o.optString("wb"), WbMode.AUTO),
            kelvin = o.optInt("kelvin", 5600),
            focus = enumOr(o.optString("foco"), FocusMode.AUTO),
            focusDiopters = o.optDouble("focoDioptrias", 0.0).toFloat(),
        )

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        /** Degraus de 1/3 de stop. A lista final é filtrada pela faixa do sensor. */
        val ISO_STEPS = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
        )

        /** Denominadores de obturador (1/N s). 1/50 e 1/60 ficam para luz de 50/60 Hz (Brasil: 60 Hz). */
        val SHUTTER_DENOMS = listOf(
            30, 40, 48, 50, 60, 80, 100, 120, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 2000, 4000,
        )

        val KELVIN_PRESETS = listOf(
            2800 to "tungstênio", 3200 to "estúdio", 4000 to "LED/fluor.", 5000 to "",
            5600 to "dia", 6500 to "nublado", 7500 to "sombra",
        )
        val KELVIN_RANGE = 2300..10000

        fun shutterLabel(ns: Long): String {
            val denom = 1_000_000_000.0 / ns
            return if (denom >= 1) "1/${Math.round(denom)}" else "%.1fs".format(ns / 1e9)
        }
    }
}

/** Preset nomeado ("Externa dia", "Igreja interna"…), salvo em SharedPreferences. */
data class Preset(val name: String, val settings: ManualSettings) {
    fun toJson(): JSONObject = JSONObject().put("nome", name).put("ajustes", settings.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Preset(o.getString("nome"), ManualSettings.fromJson(o.getJSONObject("ajustes")))

        fun listToJson(list: List<Preset>) = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<Preset> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        /** Pontos de partida; o usuário ajusta e salva por cima. */
        val DEFAULTS = listOf(
            Preset(
                "Externa dia",
                // Sol direto sem filtro ND: ISO 50 a 1/60 estoura ~6 stops (medido: o automático escolheu 1/4900).
                // 1/2000 é um ponto de partida para pessoa ou folha ao sol; ajuste no OBT conforme a cena.
                ManualSettings(ExposureMode.MANUAL, iso = 50, exposureNs = 1_000_000_000L / 2000, wb = WbMode.KELVIN, kelvin = 5600),
            ),
            Preset(
                "Igreja interna",
                ManualSettings(ExposureMode.MANUAL, iso = 400, exposureNs = 1_000_000_000L / 60, wb = WbMode.KELVIN, kelvin = 4000),
            ),
            Preset("Automático", ManualSettings()),
        )
    }
}
