package br.com.plusapps.s23logvideo.probe

import android.content.Context
import android.os.Build
import br.com.plusapps.s23logvideo.color.LogCurve
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Junta o probe da câmera e do encoder num único JSON, mais um resumo legível. */
object ProbeRunner {
    fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun run(context: Context): JSONObject {
        val device = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("socModel", Build.SOC_MODEL)
            .put("buildDisplay", Build.DISPLAY)
            .put("incremental", Build.VERSION.INCREMENTAL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
        val root = JSONObject()
            .put("geradoEm", timestamp())
            .put("aparelho", device)
            .put("curvaLogProvisoria", LogCurve.describe())
        root.putSafe("camera") { CameraProbe(context).run() }
        root.putSafe("encodersHevc") { CodecProbe.run() }
        root.put("resumo", JSONArray(summary(root)))
        return root
    }

    fun summary(root: JSONObject): List<String> {
        val lines = mutableListOf<String>()
        val dev = root.optJSONObject("aparelho")
        if (dev != null) lines += "${dev.optString("model")} · Android ${dev.optString("android")} · ${dev.optString("buildDisplay")}"

        val cams = root.optJSONObject("camera")?.optJSONArray("cameras") ?: JSONArray()
        for (i in 0 until cams.length()) {
            val c = cams.getJSONObject(i)
            val caps = c.optJSONArray("capabilities").strings()
            val dr = c.optJSONObject("dynamicRange")?.optJSONArray("supported").strings() - "STANDARD"
            val tm = c.optJSONArray("tonemapModes").strings()
            val focal = c.optJSONArray("focalLengthsMm")?.optDouble(0)
            val kind = when {
                "LOGICAL_MULTI_CAMERA" in caps -> "lógica (físicas ${c.optJSONArray("physicalCameraIds").strings().joinToString(",")})"
                c.optString("logicaPai").let { it.isNotEmpty() && it != "null" } -> "física de ${c.optString("logicaPai")}"
                else -> "única"
            }
            val biggest = c.optJSONObject("streams")?.optJSONArray("mediaCodec")?.optJSONObject(0)
            lines += buildString {
                append("Câmera ${c.optString("id")} · ${c.optString("facing")} · $kind")
                if (focal != null && !focal.isNaN()) append(" · %.2f mm".format(focal))
                append(" · ${c.optString("hardwareLevel")}\n")
                append("  10 bits: ${if ("DYNAMIC_RANGE_TEN_BIT" in caps) dr.joinToString(",").ifEmpty { "?" } else "não"}")
                append(" | curva: ${if ("CONTRAST_CURVE" in tm) "sim (máx ${c.optInt("tonemapMaxCurvePoints")} pts)" else "não"}")
                append(" | manual: ${if ("MANUAL_SENSOR" in caps) "sim" else "não"}")
                if (biggest != null) append(" | maior p/ encoder: ${biggest.optString("size")}@${biggest.opt("maxFps")}")
            }
        }

        val encs = root.optJSONArray("encodersHevc") ?: JSONArray()
        for (i in 0 until encs.length()) {
            val e = encs.getJSONObject(i)
            val t = e.optJSONObject("targets")
            fun ok(k: String) = if (t?.optJSONObject(k)?.optBoolean("sizeAndRateSupported") == true) "sim" else "não"
            val maxMbps = e.optJSONArray("bitrateRangeBps")?.optLong(1)?.div(1_000_000)
            lines += "Encoder ${e.optString("name")} (${if (e.optBoolean("hardware")) "hw" else "sw"}) · " +
                "Main10 ${if (e.optBoolean("main10")) "sim" else "não"} · 4K30 ${ok("3840x2160@30")} · " +
                "4K60 ${ok("3840x2160@60")} · 8K30 ${ok("7680x4320@30")} · até $maxMbps Mb/s"
        }
        return lines
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
