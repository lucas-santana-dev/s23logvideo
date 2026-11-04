package br.com.plusapps.s23logvideo.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
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

/** Valores que a câmera devolveu no último quadro (para o HUD e para "medir no auto e travar"). */
data class Measured(
    val iso: Int?,
    val exposureNs: Long?,
    val focusDiopters: Float?,
    val afState: Int?,
    val rollingShutterSkewNs: Long?,
)

/**
 * Uma sessão Camera2 com duas saídas, configurada uma vez:
 *  - preview: SurfaceTexture do renderer OpenGL;
 *  - gravação: surface persistente de MediaCodec.
 * Gravar só troca o repeating request (preview → preview + encoder), sem reconfigurar a sessão.
 * Os ajustes manuais ([settings]) entram no mesmo repeating request; mudar um ajuste só refaz o request.
 */
class CameraController(context: Context) {
    val cameraId = "0" // traseira principal (lógica); troca de lente fica para a Fase 5
    private val manager = context.getSystemService(CameraManager::class.java)
    val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cameraId)
    val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val whiteBalance = WhiteBalance(characteristics)

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

    /** Ajustes manuais atuais; quem muda chama [refresh]. */
    @Volatile var settings = ManualSettings()

    private var recording = false
    private var recordCallback: CameraCaptureSession.CaptureCallback? = null

    /** Toque para focar em andamento: região de AF e o que fazer quando o foco travar. */
    private var tapRegion: MeteringRectangle? = null
    private var tapDone: ((Float?) -> Unit)? = null
    private var tapStartedNs = 0L

    @Volatile var measured = Measured(null, null, null, null, null)
        private set

    /** Últimos ganhos e matriz do AWB automático; base do modo Kelvin. */
    @Volatile private var autoGains: RggbChannelVector? = null
    @Volatile private var autoTransform: ColorSpaceTransform? = null

    val previewSize = Size(1920, 1080)

    val isOpen get() = session != null

    // Faixas do sensor (para as rodas da UI).
    val isoRange: Range<Int> = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
    val exposureRange: Range<Long> =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
    /** 0 = foco fixo. */
    val minFocusDiopters: Float = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val hasManualSensor = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val wbCalibrated get() = whiteBalance.calibrated

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
        recording = false
        recordCallback = null
        refresh()
    }

    /** Entrega o encoder já configurado para uma gravação (e deixa de ser dono dele). */
    fun takeEncoder(): VideoEncoder =
        preparedEncoder.also { preparedEncoder = null } ?: VideoEncoder(config!!, persistentSurface, encoderHandler)

    /** Depois de uma gravação: prepara o próximo encoder para a surface nunca ficar sem consumidor. */
    fun prepareNextEncoder() {
        if (preparedEncoder == null) config?.let { preparedEncoder = VideoEncoder(it, persistentSurface, encoderHandler) }
    }

    /** Liga/desliga o encoder no repeating request. [callback] recebe os resultados durante a gravação. */
    fun setRepeating(recording: Boolean, callback: CameraCaptureSession.CaptureCallback?) {
        cameraHandler.post {
            this.recording = recording
            recordCallback = callback
            applyRepeating()
        }
    }

    /** Refaz o repeating request com os [settings] atuais (chamar depois de mudar um ajuste). */
    fun refresh() {
        cameraHandler.post { applyRepeating() }
    }

    /**
     * Toque para focar em (nx, ny), coordenadas normalizadas do buffer da câmera (0..1, origem no
     * canto superior esquerdo, orientação do sensor). Faz um AF de disparo único na região e, quando
     * o foco trava, devolve a distância em dioptrias para [onLocked] (que costuma passar para MANUAL).
     */
    fun tapToFocus(nx: Float, ny: Float, onLocked: (Float?) -> Unit) {
        if (minFocusDiopters <= 0f) return onLocked(null)
        cameraHandler.post {
            val s = session ?: return@post onLocked(null)
            val region = meteringRegion(nx, ny)
            tapRegion = region
            tapDone = onLocked
            tapStartedNs = System.nanoTime()
            runCatching {
                // Cancela qualquer AF anterior e dispara um novo na região.
                val trigger = buildRequest(s) ?: return@post
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                applyRepeating()
                s.capture(trigger.build(), internalCallback, cameraHandler)
            }.onFailure { finishTap(null) }
        }
    }

    private fun finishTap(diopters: Float?) {
        val cb = tapDone
        tapRegion = null
        tapDone = null
        cb?.invoke(diopters)
    }

    private fun applyRepeating() {
        val s = session ?: return
        runCatching {
            val req = buildRequest(s) ?: return
            s.setRepeatingRequest(req.build(), internalCallback, cameraHandler)
        }
    }

    private fun buildRequest(s: CameraCaptureSession): CaptureRequest.Builder? {
        val cfg = config ?: return null
        val m = settings
        return s.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface ?: return null)
            if (recording) addTarget(persistentSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
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
            applyExposure(this, m, cfg)
            applyWhiteBalance(this, m)
            applyFocus(this, m)
        }
    }

    private fun applyExposure(b: CaptureRequest.Builder, m: ManualSettings, cfg: VideoConfig) {
        if (m.exposure == ExposureMode.MANUAL && hasManualSensor) {
            val frameNs = 1_000_000_000L / cfg.fps
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(isoRange.lower, isoRange.upper))
            // O obturador não pode passar da duração do quadro, senão o fps cai.
            b.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                m.exposureNs.coerceIn(exposureRange.lower, minOf(exposureRange.upper, frameNs)),
            )
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // Anti-flicker automático; em MANUAL quem cuida é o obturador (1/60 ou 1/120 em rede de 60 Hz).
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_LOCK, m.exposure == ExposureMode.LOCK)
            tapRegion?.let { if (m.exposure == ExposureMode.AUTO) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        }
    }

    private fun applyWhiteBalance(b: CaptureRequest.Builder, m: ManualSettings) {
        val gains = if (m.wb == WbMode.KELVIN) whiteBalance.gains(m.kelvin, autoGains) else null
        val transform = autoTransform
        if (gains != null && transform != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
        } else {
            // Kelvin ainda sem matriz de referência (primeiro quadro) cai no AWB até ela chegar.
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, m.wb == WbMode.LOCK)
        }
    }

    private fun applyFocus(b: CaptureRequest.Builder, m: ManualSettings) {
        val region = tapRegion
        when {
            minFocusDiopters <= 0f -> Unit // foco fixo
            region != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            m.focus == FocusMode.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopters.coerceIn(0f, minFocusDiopters))
            }
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    private val internalCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            measured = Measured(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = focus,
                afState = af,
                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            )
            if (result.get(CaptureResult.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { autoGains = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let {
                    val first = autoTransform == null
                    autoTransform = it
                    // Kelvin escolhido antes da 1ª matriz: agora dá para aplicar.
                    if (first && settings.wb == WbMode.KELVIN) applyRepeating()
                }
            }
            if (tapDone != null) {
                val locked = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                val timeout = System.nanoTime() - tapStartedNs > 2_500_000_000L
                if (locked || timeout) finishTap(focus)
            }
            recordCallback?.onCaptureCompleted(s, r, result)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            recordCallback?.onCaptureFailed(s, r, f)
        }
    }

    /** Região de ~10% do sensor em volta do ponto; o buffer 16:9 é um recorte central do sensor. */
    private fun meteringRegion(nx: Float, ny: Float): MeteringRectangle {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4000, 3000)
        val aw = active.width()
        val ah = active.height()
        val cropH = minOf(ah, aw * previewSize.height / previewSize.width)
        val top = (ah - cropH) / 2
        val cx = (nx.coerceIn(0f, 1f) * aw).toInt()
        val cy = top + (ny.coerceIn(0f, 1f) * cropH).toInt()
        val half = aw / 20
        val l = (cx - half).coerceIn(0, aw - 2 * half)
        val t = (cy - half).coerceIn(0, ah - 2 * half)
        return MeteringRectangle(l, t, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
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
        tapRegion = null
        tapDone = null
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
