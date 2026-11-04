package br.com.plusapps.s23logvideo.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import br.com.plusapps.s23logvideo.color.LogCurve
import br.com.plusapps.s23logvideo.record.VideoConfig
import br.com.plusapps.s23logvideo.record.VideoEncoder
import java.util.concurrent.Executor

/**
 * Uma sessão Camera2 com duas saídas, configurada uma vez:
 *  - preview: SurfaceTexture do renderer OpenGL;
 *  - gravação: surface persistente de MediaCodec.
 * Gravar só troca o repeating request (preview → preview + encoder), sem reconfigurar a sessão.
 */
class CameraController(context: Context) {
    val cameraId = "0" // traseira principal (lógica); troca de lente fica para a Fase 5
    private val manager = context.getSystemService(CameraManager::class.java)
    val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cameraId)
    val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    private val cameraThread = HandlerThread("camera").apply { start() }
    val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executor { cameraHandler.post(it) }
    private val encoderThread = HandlerThread("video-encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    /** Mesma surface em todas as gravações; recriar exigiria reconfigurar a sessão. */
    private val persistentSurface: Surface = MediaCodec.createPersistentInputSurface()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var preparedEncoder: VideoEncoder? = null

    var config: VideoConfig? = null
        private set

    val previewSize = Size(1920, 1080)

    val isOpen get() = session != null

    /** Abre a câmera e configura a sessão para [config]. Reabre do zero se já estiver aberta. */
    suspend fun open(previewTexture: SurfaceTexture, config: VideoConfig) {
        close()
        this.config = config
        previewTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = Surface(previewTexture).also { previewSurface = it }
        // O encoder precisa estar configurado ANTES da sessão: é ele que dá o tamanho à surface persistente.
        preparedEncoder = VideoEncoder(config, persistentSurface, encoderHandler)

        val dev = manager.openAsync(cameraId, executor).also { device = it }
        val outputs = listOf(preview, persistentSurface).map { s ->
            OutputConfiguration(s).apply {
                timestampBase = OutputConfiguration.TIMESTAMP_BASE_MONOTONIC
                if (config.tenBit) dynamicRangeProfile = DynamicRangeProfiles.HLG10
            }
        }
        session = dev.createSessionAsync(outputs, executor)
        setRepeating(recording = false, callback = null)
    }

    /** Entrega o encoder já configurado para uma gravação (e deixa de ser dono dele). */
    fun takeEncoder(): VideoEncoder =
        preparedEncoder.also { preparedEncoder = null } ?: VideoEncoder(config!!, persistentSurface, encoderHandler)

    /** Depois de uma gravação: prepara o próximo encoder para a surface nunca ficar sem consumidor. */
    fun prepareNextEncoder() {
        if (preparedEncoder == null) config?.let { preparedEncoder = VideoEncoder(it, persistentSurface, encoderHandler) }
    }

    fun setRepeating(recording: Boolean, callback: CameraCaptureSession.CaptureCallback?) {
        val s = session ?: return
        val cfg = config ?: return
        val request = s.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface!!)
            if (recording) addTarget(persistentSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            // EIS corta a imagem; a estabilização fica com o OIS (Fase 5 trata do giroscópio).
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (hasOis()) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            if (cfg.curve) {
                val pts = LogCurve.tonemapPoints(maxCurvePoints())
                set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
                // Log: o mínimo de processamento possível no ISP (sem nitidez artificial, ruído quase cru).
                if (supportsEdgeOff()) set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                if (supportsNrMinimal()) {
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                }
            }
        }.build()
        s.setRepeatingRequest(request, callback, cameraHandler)
    }

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        preparedEncoder?.release()
        preparedEncoder = null
        previewSurface?.release()
        previewSurface = null
    }

    fun release() {
        close()
        persistentSurface.release()
        cameraThread.quitSafely()
        encoderThread.quitSafely()
    }

    private fun hasOis() = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

    private fun supportsEdgeOff() = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        ?.contains(CameraMetadata.EDGE_MODE_OFF) == true

    private fun supportsNrMinimal() =
        characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true

    private fun maxCurvePoints() =
        (characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 64).coerceIn(2, 64)
}
