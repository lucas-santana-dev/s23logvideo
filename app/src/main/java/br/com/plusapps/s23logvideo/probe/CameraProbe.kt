package br.com.plusapps.s23logvideo.probe

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Fase 0: tudo o que a Camera2 relata para cada câmera, lógica e física. */
class CameraProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun run(): JSONObject {
        val listed = manager.cameraIdList.toList()
        val cameras = JSONArray()
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listed.map { it to (null as String?) })
        while (queue.isNotEmpty()) {
            val (id, parent) = queue.removeFirst()
            if (!seen.add(id)) continue
            val ch = try {
                manager.getCameraCharacteristics(id)
            } catch (t: Throwable) {
                cameras.put(JSONObject().put("id", id).put("erro", t.toString()))
                continue
            }
            cameras.put(describe(id, parent, id in listed, ch))
            ch.physicalCameraIds.forEach { queue.add(it to id) }
        }
        return JSONObject()
            .put("cameraIdList", JSONArray(listed))
            .put("cameras", cameras)
    }

    private fun describe(id: String, parent: String?, listed: Boolean, ch: CameraCharacteristics): JSONObject {
        val o = JSONObject()
        val M = CameraMetadata::class.java
        o.put("id", id)
        o.put("logicaPai", parent ?: JSONObject.NULL)
        o.put("noCameraIdList", listed)
        o.putSafe("facing") { ch[CC.LENS_FACING]?.let { Names.of(M, "LENS_FACING_", it) } }
        o.putSafe("hardwareLevel") {
            ch[CC.INFO_SUPPORTED_HARDWARE_LEVEL]?.let { Names.of(M, "INFO_SUPPORTED_HARDWARE_LEVEL_", it) }
        }
        o.putSafe("infoVersion") { ch[CC.INFO_VERSION] }
        o.putSafe("capabilities") {
            Names.list(M, "REQUEST_AVAILABLE_CAPABILITIES_", ch[CC.REQUEST_AVAILABLE_CAPABILITIES])
        }
        o.putSafe("physicalCameraIds") { JSONArray(ch.physicalCameraIds.toList()) }

        // Óptica e sensor
        o.putSafe("focalLengthsMm") { ch[CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.let { JSONArray(it.toList()) } }
        o.putSafe("apertures") { ch[CC.LENS_INFO_AVAILABLE_APERTURES]?.let { JSONArray(it.toList()) } }
        o.putSafe("sensorPhysicalSizeMm") { ch[CC.SENSOR_INFO_PHYSICAL_SIZE]?.let { "${it.width}x${it.height}" } }
        o.putSafe("pixelArraySize") { ch[CC.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.str() }
        o.putSafe("activeArraySize") { ch[CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.flattenToString() }
        o.putSafe("sensorOrientation") { ch[CC.SENSOR_ORIENTATION] }
        o.putSafe("minFocusDistanceDiopters") { ch[CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE] }
        o.putSafe("focusCalibration") {
            ch[CC.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]?.let {
                Names.of(M, "LENS_INFO_FOCUS_DISTANCE_CALIBRATION_", it)
            }
        }

        // 10 bits
        o.putSafe("dynamicRange") { dynamicRange(ch) }

        // Curva de tom e processamento
        o.putSafe("tonemapModes") { Names.list(M, "TONEMAP_MODE_", ch[CC.TONEMAP_AVAILABLE_TONE_MAP_MODES]) }
        o.putSafe("tonemapMaxCurvePoints") { ch[CC.TONEMAP_MAX_CURVE_POINTS] }
        o.putSafe("edgeModes") { Names.list(M, "EDGE_MODE_", ch[CC.EDGE_AVAILABLE_EDGE_MODES]) }
        o.putSafe("noiseReductionModes") {
            Names.list(M, "NOISE_REDUCTION_MODE_", ch[CC.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES])
        }

        // Controles manuais
        o.putSafe("isoRange") { ch[CC.SENSOR_INFO_SENSITIVITY_RANGE]?.json() }
        o.putSafe("maxAnalogIso") { ch[CC.SENSOR_MAX_ANALOG_SENSITIVITY] }
        o.putSafe("exposureTimeRangeNs") { ch[CC.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.json() }
        o.putSafe("maxFrameDurationNs") { ch[CC.SENSOR_INFO_MAX_FRAME_DURATION] }
        o.putSafe("aeTargetFpsRanges") {
            JSONArray().apply { ch[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.forEach { put(it.json()) } }
        }
        o.putSafe("aeModes") { Names.list(M, "CONTROL_AE_MODE_", ch[CC.CONTROL_AE_AVAILABLE_MODES]) }
        o.putSafe("afModes") { Names.list(M, "CONTROL_AF_MODE_", ch[CC.CONTROL_AF_AVAILABLE_MODES]) }
        o.putSafe("awbModes") { Names.list(M, "CONTROL_AWB_MODE_", ch[CC.CONTROL_AWB_AVAILABLE_MODES]) }
        o.putSafe("oisModes") {
            Names.list(M, "LENS_OPTICAL_STABILIZATION_MODE_", ch[CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION])
        }
        o.putSafe("videoStabilizationModes") {
            Names.list(M, "CONTROL_VIDEO_STABILIZATION_MODE_", ch[CC.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES])
        }

        // Chaves que o app pode mandar e ler: diz se o controle existe de fato
        o.putSafe("requestKeysImportantes") { importantKeys(ch) }

        // Tamanhos e fps
        o.putSafe("streams") { ch[CC.SCALER_STREAM_CONFIGURATION_MAP]?.let { streams(it) } }

        // Listas completas, incluindo tags de fornecedor (samsung.*), para descobrir recursos escondidos
        o.putSafe("allCharacteristicsKeys") { JSONArray(ch.keys.map { it.name }) }
        o.putSafe("availableRequestKeys") { JSONArray(ch.availableCaptureRequestKeys.map { it.name }) }
        o.putSafe("availableResultKeys") { JSONArray(ch.availableCaptureResultKeys.map { it.name }) }
        o.putSafe("availableSessionKeys") { JSONArray(ch.availableSessionKeys.map { it.name }) }
        return o
    }

    private fun dynamicRange(ch: CameraCharacteristics): JSONObject {
        val o = JSONObject()
        val P = DynamicRangeProfiles::class.java
        val profiles = ch[CC.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]
        o.putSafe("recommendedTenBit") {
            ch[CC.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE]?.let { Names.of(P, "", it) }
        }
        if (profiles == null) {
            o.put("supported", JSONArray())
            return o
        }
        val supported = profiles.supportedProfiles.sorted()
        o.put("supported", JSONArray(supported.map { Names.of(P, "", it) }))
        val details = JSONObject()
        for (p in supported) {
            val d = JSONObject()
            d.putSafe("combinaCom") {
                JSONArray(profiles.getProfileCaptureRequestConstraints(p).sorted().map { Names.of(P, "", it) })
            }
            d.putSafe("latenciaExtra") { profiles.isExtraLatencyPresent(p) }
            details.put(Names.of(P, "", p), d)
        }
        o.put("detalhes", details)
        return o
    }

    private fun importantKeys(ch: CameraCharacteristics): JSONObject {
        val req = ch.availableCaptureRequestKeys.map { it.name }.toSet()
        val res = ch.availableCaptureResultKeys.map { it.name }.toSet()
        val o = JSONObject()
        listOf(
            CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_CURVE, CaptureRequest.EDGE_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_FRAME_DURATION,
            CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_GAINS,
            CaptureRequest.LENS_FOCUS_DISTANCE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
        ).forEach { k -> o.put(k.name, JSONObject().put("request", k.name in req).put("result", k.name in res)) }
        o.put(
            CaptureResult.TONEMAP_CURVE.name + " (result)",
            CaptureResult.TONEMAP_CURVE.name in res
        )
        return o
    }

    private fun streams(map: StreamConfigurationMap): JSONObject {
        val o = JSONObject()
        fun sizesWithFps(sizes: Array<Size>?, minDur: (Size) -> Long): JSONArray = JSONArray().apply {
            sizes?.sortedByDescending { it.width.toLong() * it.height }?.forEach { s ->
                val dur = runCatching { minDur(s) }.getOrDefault(0L)
                put(JSONObject().put("size", s.str()).put("maxFps", if (dur > 0) Math.round(1e9 / dur) else JSONObject.NULL))
            }
        }
        o.putSafe("mediaCodec") {
            sizesWithFps(map.getOutputSizes(MediaCodec::class.java)) {
                map.getOutputMinFrameDuration(MediaCodec::class.java, it)
            }
        }
        o.putSafe("surfaceTexture") {
            sizesWithFps(map.getOutputSizes(SurfaceTexture::class.java)) {
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, it)
            }
        }
        o.putSafe("yuv420") {
            sizesWithFps(map.getOutputSizes(ImageFormat.YUV_420_888)) {
                map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it)
            }
        }
        o.putSafe("p010") {
            if (map.isOutputSupportedFor(ImageFormat.YCBCR_P010)) {
                sizesWithFps(map.getOutputSizes(ImageFormat.YCBCR_P010)) {
                    map.getOutputMinFrameDuration(ImageFormat.YCBCR_P010, it)
                }
            } else JSONArray()
        }
        o.putSafe("raw") {
            if (map.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) {
                JSONArray(map.getOutputSizes(ImageFormat.RAW_SENSOR).map { it.str() })
            } else JSONArray()
        }
        o.putSafe("highSpeedVideo") {
            JSONArray().apply {
                map.highSpeedVideoSizes?.forEach { s ->
                    val ranges = JSONArray().apply { map.getHighSpeedVideoFpsRangesFor(s).forEach { put(it.json()) } }
                    put(JSONObject().put("size", s.str()).put("fpsRanges", ranges))
                }
            }
        }
        o.putSafe("outputFormats") {
            JSONArray(map.outputFormats.map { Names.of(ImageFormat::class.java, "", it) })
        }
        return o
    }
}
