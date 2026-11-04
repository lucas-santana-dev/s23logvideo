package br.com.plusapps.s23logvideo.record

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import br.com.plusapps.s23logvideo.color.LogCurve
import br.com.plusapps.s23logvideo.probe.Names
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Um clipe da matriz de testes da Fase 0. */
data class ClipSpec(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean,
    val curve: Boolean,
    /** Teto do encoder (bitrateRange.upper), medido no probe. */
    val bitrateCap: Int = Int.MAX_VALUE,
) {
    val name get() = "${label}_${if (tenBit) "10bit" else "8bit"}_${if (curve) "curva" else "padrao"}"

    fun bitrate(): Int = minOf(bitrateCap, if (width >= 7680 || fps >= 60) 150_000_000 else 100_000_000)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("width", width).put("height", height).put("fps", fps)
        .put("tenBit", tenBit).put("curve", curve)

    /** Formato do encoder: HEVC por surface, Main ou Main10. */
    fun mediaFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (tenBit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            )
            if (tenBit) {
                // Só para medir na Fase 0: marca como HLG/BT.2020, que é o que o ISP entrega.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
}

/**
 * Gravador mínimo da Fase 0: uma sessão Camera2 só com a surface do encoder
 * (sem preview, sem áudio), para medir o que o S23 aceita de fato.
 */
class TestRecorder(
    private val context: Context,
    private val cameraHandler: Handler,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    /** Estatísticas por quadro, escritas na thread da câmera. */
    private class Stats(val expectedFrameNs: Long) {
        var frames = 0
        var failures = 0
        var dropped = 0
        var firstTs = 0L
        var lastTs = 0L
        val tonemapModes = HashMap<String, Int>()
        var curveReturned = 0
        var returnedCurvePoints = -1
        var hlgFrames = 0
        var lastIso: Int? = null
        var lastExposureNs: Long? = null
        var lastFrameDurationNs: Long? = null

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
            lastIso = r.get(CaptureResult.SENSOR_SENSITIVITY)
            lastExposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            lastFrameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION)
        }

        @Synchronized
        fun onFailure() {
            failures++
        }

        @Synchronized
        fun toJson(): JSONObject {
            val seconds = if (lastTs > firstTs) (lastTs - firstTs) / 1e9 else 0.0
            return JSONObject()
                .put("framesCapturados", frames)
                .put("falhasDeCaptura", failures)
                .put("quadrosPerdidos", dropped)
                .put("fpsMedio", if (seconds > 0) (frames - 1) / seconds else JSONObject.NULL)
                .put("tonemapModeDevolvido", JSONObject(tonemapModes as Map<*, *>))
                .put("quadrosComCurvaNoResult", curveReturned)
                .put("pontosDaCurvaDevolvida", returnedCurvePoints)
                .put("ultimoIso", lastIso ?: JSONObject.NULL)
                .put("ultimaExposicaoNs", lastExposureNs ?: JSONObject.NULL)
                .put("ultimaFrameDurationNs", lastFrameDurationNs ?: JSONObject.NULL)
        }
    }

    suspend fun record(
        cameraId: String,
        spec: ClipSpec,
        fpsRange: Range<Int>,
        curvePoints: Int,
        orientationHint: Int,
        runTag: String,
        durationMs: Long = 10_000,
    ): JSONObject {
        val report = JSONObject().put("spec", spec.toJson()).put("cameraId", cameraId)
            .put("fpsRangePedido", "[${fpsRange.lower}, ${fpsRange.upper}]")
        val stats = Stats(1_000_000_000L / spec.fps)
        val encoderThread = HandlerThread("encoder").apply { start() }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val uri = MediaStoreOut.newVideo(context, "${spec.name}_$runTag")
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")!!
        var muxerStarted = false
        var encodedFrames = 0
        var encodedBytes = 0L
        var firstPtsUs = -1L
        var lastPtsUs = 0L
        val eos = CompletableDeferred<Unit>()
        val stage = StringBuilder("encoder")

        try {
            // 1. Encoder
            val format = spec.mediaFormat()
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
                ?: error("nenhum encoder aceita $format")
            report.put("encoder", codecName)
            report.put("bitratePedido", spec.bitrate())
            codec = MediaCodec.createByCodecName(codecName)
            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            mux.setOrientationHint(orientationHint)
            var track = -1
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        val buf = c.getOutputBuffer(index)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mux.writeSampleData(track, buf, info)
                        encodedFrames++
                        encodedBytes += info.size
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        lastPtsUs = info.presentationTimeUs
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.complete(Unit)
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                    eos.completeExceptionally(e)
                }

                override fun onOutputFormatChanged(c: MediaCodec, fmt: MediaFormat) {
                    report.put("formatoDeSaidaDoEncoder", fmt.toString())
                    track = mux.addTrack(fmt)
                    mux.start()
                    muxerStarted = true
                }
            }, Handler(encoderThread.looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            // 2. Câmera e sessão
            stage.setLength(0); stage.append("abrir câmera")
            device = openCamera(cameraId)
            stage.setLength(0); stage.append("configurar sessão")
            val output = OutputConfiguration(inputSurface)
            if (spec.tenBit) output.dynamicRangeProfile = DynamicRangeProfiles.HLG10
            session = createSession(device, output)

            // 3. Request
            stage.setLength(0); stage.append("request")
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(inputSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                if (spec.curve) {
                    val pts = LogCurve.tonemapPoints(curvePoints)
                    set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
                    report.put("pontosDaCurvaPedida", curvePoints)
                }
            }
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) =
                    stats.onResult(result)

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) =
                    stats.onFailure()
            }, cameraHandler)

            // 4. Grava e encerra
            stage.setLength(0); stage.append("gravando")
            delay(durationMs)
            session.stopRepeating()
            delay(200)
            codec.signalEndOfInputStream()
            withTimeout(5_000) { eos.await() }
            report.put("status", "ok")
        } catch (t: Throwable) {
            report.put("status", "falhou").put("etapa", stage.toString()).put("erro", t.toString())
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            val muxOk = muxerStarted && encodedFrames > 0 && runCatching { muxer?.stop() }.isSuccess
            runCatching { muxer?.release() }
            runCatching { pfd.close() }
            encoderThread.quitSafely()
            if (muxOk) MediaStoreOut.publish(context, uri) else MediaStoreOut.delete(context, uri)
            report.put("arquivo", if (muxOk) "${MediaStoreOut.VIDEO_DIR}/${spec.name}_$runTag.mp4" else JSONObject.NULL)
        }

        val seconds = if (lastPtsUs > firstPtsUs && firstPtsUs >= 0) (lastPtsUs - firstPtsUs) / 1e6 else 0.0
        report.put("camera", stats.toJson())
        report.put(
            "encoderSaida", JSONObject()
                .put("quadrosCodificados", encodedFrames)
                .put("bytes", encodedBytes)
                .put("bitrateRealMbps", if (seconds > 0) encodedBytes * 8 / seconds / 1e6 else JSONObject.NULL)
        )
        report.put("veredito", verdict(spec, report))
        return report
    }

    /** Resumo em uma linha para a tela. */
    private fun verdict(spec: ClipSpec, r: JSONObject): String {
        if (r.optString("status") != "ok") return "FALHOU (${r.optString("etapa")}): ${r.optString("erro")}"
        val cam = r.getJSONObject("camera")
        val modes = cam.getJSONObject("tonemapModeDevolvido")
        val curveOk = modes.optInt("CONTRAST_CURVE", 0) > cam.optInt("framesCapturados") / 2
        val fps = cam.optDouble("fpsMedio", 0.0)
        val parts = mutableListOf("%.1f fps".format(fps), "perdidos ${cam.optInt("quadrosPerdidos")}")
        if (spec.curve) parts += if (curveOk) "curva ACEITA" else "curva IGNORADA ($modes)"
        else parts += "tonemap $modes"
        return parts.joinToString(" | ")
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (cont.isActive) cont.resume(d) else d.close()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("câmera desconectada"))
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("erro ao abrir câmera: $error"))
            }
        })
    }

    private suspend fun createSession(device: CameraDevice, output: OutputConfiguration): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, listOf(output), cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("onConfigureFailed"))
                    }
                }
            )
            device.createCaptureSession(config)
        }
}
