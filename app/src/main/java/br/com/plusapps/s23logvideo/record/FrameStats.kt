package br.com.plusapps.s23logvideo.record

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import br.com.plusapps.s23logvideo.probe.Names
import org.json.JSONObject
import kotlin.math.roundToInt

/** Estatísticas por quadro do CaptureResult, escritas na thread da câmera. */
class FrameStats(private val expectedFrameNs: Long) {
    private var frames = 0
    private var failures = 0
    private var dropped = 0
    private var firstTs = 0L
    private var lastTs = 0L
    private val tonemapModes = HashMap<String, Int>()
    private val videoStabModes = HashMap<String, Int>()
    private val opticalStabModes = HashMap<String, Int>()
    private var lastCrop: android.graphics.Rect? = null
    private var curveReturned = 0
    private var returnedCurvePoints = -1
    private var lastIso: Int? = null
    private var lastExposureNs: Long? = null
    private var lastFrameDurationNs: Long? = null

    @Synchronized
    fun onResult(r: TotalCaptureResult) {
        frames++
        val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        if (firstTs == 0L) firstTs = ts
        if (lastTs != 0L && ts > lastTs) {
            val gap = (ts - lastTs).toDouble() / expectedFrameNs
            if (gap > 1.5) dropped += gap.roundToInt() - 1
        }
        lastTs = ts
        val mode = r.get(CaptureResult.TONEMAP_MODE)
        val name = mode?.let { Names.of(CameraMetadata::class.java, "TONEMAP_MODE_", it) } ?: "null"
        tonemapModes[name] = (tonemapModes[name] ?: 0) + 1
        r.get(CaptureResult.TONEMAP_CURVE)?.let {
            curveReturned++
            returnedCurvePoints = it.getPointCount(TonemapCurve.CHANNEL_GREEN)
        }
        r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)?.let {
            val k = Names.of(CameraMetadata::class.java, "CONTROL_VIDEO_STABILIZATION_MODE_", it)
            videoStabModes[k] = (videoStabModes[k] ?: 0) + 1
        }
        r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let {
            val k = Names.of(CameraMetadata::class.java, "LENS_OPTICAL_STABILIZATION_MODE_", it)
            opticalStabModes[k] = (opticalStabModes[k] ?: 0) + 1
        }
        lastCrop = r.get(CaptureResult.SCALER_CROP_REGION)
        lastIso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        lastExposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        lastFrameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION)
    }

    @Synchronized
    fun onFailure() {
        failures++
    }

    @get:Synchronized
    val droppedFrames get() = dropped

    @get:Synchronized
    val capturedFrames get() = frames

    @Synchronized
    fun toJson(): JSONObject {
        val seconds = if (lastTs > firstTs) (lastTs - firstTs) / 1e9 else 0.0
        return JSONObject()
            .put("framesCapturados", frames)
            .put("falhasDeCaptura", failures)
            .put("quadrosPerdidos", dropped)
            .put("fpsMedio", if (seconds > 0) (frames - 1) / seconds else JSONObject.NULL)
            .put("tonemapModeDevolvido", JSONObject(tonemapModes as Map<*, *>))
            .put("estabilizacaoVideoDevolvida", JSONObject(videoStabModes as Map<*, *>))
            .put("estabilizacaoOpticaDevolvida", JSONObject(opticalStabModes as Map<*, *>))
            .put("cropRegionFinal", lastCrop?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: JSONObject.NULL)
            .put("quadrosComCurvaNoResult", curveReturned)
            .put("pontosDaCurvaDevolvida", returnedCurvePoints)
            .put("ultimoIso", lastIso ?: JSONObject.NULL)
            .put("ultimaExposicaoNs", lastExposureNs ?: JSONObject.NULL)
            .put("ultimaFrameDurationNs", lastFrameDurationNs ?: JSONObject.NULL)
    }
}
