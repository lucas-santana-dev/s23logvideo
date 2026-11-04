package br.com.plusapps.s23logvideo.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.util.Range
import android.util.Size
import br.com.plusapps.s23logvideo.record.ClipSpec

data class PlannedClip(val spec: ClipSpec, val fpsRange: Range<Int>?, val skipReason: String?)

data class MatrixPlan(
    val cameraId: String,
    val sensorOrientation: Int,
    val curvePoints: Int,
    val clips: List<PlannedClip>,
)

/** Monta a matriz {4K30, 4K60, 8K30} × {8, 10 bits} × {curva padrão, customizada} e pula o que não é suportado. */
object TestMatrix {
    private data class Mode(val label: String, val w: Int, val h: Int, val fps: Int)

    private val MODES = listOf(
        Mode("4K30", 3840, 2160, 30),
        Mode("4K60", 3840, 2160, 60),
        Mode("8K30", 7680, 4320, 30),
    )

    fun defaultBackCamera(context: Context): String? {
        val m = context.getSystemService(CameraManager::class.java)
        return m.cameraIdList.firstOrNull {
            m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK
        }
    }

    fun plan(context: Context, cameraId: String): MatrixPlan {
        val ch = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val codecSizes = map.getOutputSizes(MediaCodec::class.java)?.toSet().orEmpty()
        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val hlg = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in caps &&
            ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        val curveModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
        val curveOk = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in curveModes
        val maxPoints = ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val curvePoints = maxPoints.coerceAtMost(64).coerceAtLeast(2)
        val encoders = CodecProbe.hevcEncoders()

        val clips = mutableListOf<PlannedClip>()
        for (mode in MODES) for (tenBit in listOf(false, true)) for (curve in listOf(false, true)) {
            val base = ClipSpec(mode.label, mode.w, mode.h, mode.fps, tenBit, curve)
            val size = Size(mode.w, mode.h)
            val range = fpsRanges.firstOrNull { it.lower == mode.fps && it.upper == mode.fps }
                ?: fpsRanges.firstOrNull { it.upper == mode.fps }
            val encoderCap = encoders
                .filter { !tenBit || CodecProbe.supportsMain10(it) }
                .mapNotNull { info ->
                    val v = info.getCapabilitiesForType(CodecProbe.MIME).videoCapabilities ?: return@mapNotNull null
                    if (v.areSizeAndRateSupported(mode.w, mode.h, mode.fps.toDouble())) v.bitrateRange.upper else null
                }
                .maxOrNull()
            val minDurNs = if (size in codecSizes) map.getOutputMinFrameDuration(MediaCodec::class.java, size) else 0L
            val reason = when {
                size !in codecSizes -> "câmera não oferece ${size.str()} para MediaCodec"
                minDurNs > 1_000_000_000L / mode.fps * 101 / 100 ->
                    "câmera limita ${size.str()} a ${Math.round(1e9 / minDurNs)} fps"
                range == null -> "nenhuma faixa de fps do AE termina em ${mode.fps}"
                tenBit && !hlg -> "câmera não anuncia HLG10 / DYNAMIC_RANGE_TEN_BIT"
                curve && !curveOk -> "câmera não anuncia TONEMAP_MODE_CONTRAST_CURVE"
                encoderCap == null -> "nenhum encoder HEVC ${if (tenBit) "Main10 " else ""}aceita ${mode.label}"
                else -> null
            }
            val spec = if (encoderCap != null) base.copy(bitrateCap = encoderCap) else base
            clips += PlannedClip(spec, range, reason)
        }
        return MatrixPlan(cameraId, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, curvePoints, clips)
    }
}
