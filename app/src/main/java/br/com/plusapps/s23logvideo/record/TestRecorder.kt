package br.com.plusapps.s23logvideo.record

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import br.com.plusapps.s23logvideo.camera.createSessionAsync
import br.com.plusapps.s23logvideo.camera.openAsync
import br.com.plusapps.s23logvideo.color.LogCurve
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.Executor

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

    fun bitrate(): Int = minOf(bitrateCap, VideoConfig.defaultBitrate(width, fps).coerceAtLeast(100_000_000))

    fun videoConfig() = VideoConfig(width, height, fps, tenBit, curve, bitrate())

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("width", width).put("height", height).put("fps", fps)
        .put("tenBit", tenBit).put("curve", curve)
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
        val stats = FrameStats(1_000_000_000L / spec.fps)
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
        var stage = "encoder"

        try {
            // 1. Encoder
            val format = spec.videoConfig().mediaFormat()
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
            stage = "abrir câmera"
            device = manager.openAsync(cameraId, cameraExecutor)
            stage = "configurar sessão"
            val output = OutputConfiguration(inputSurface)
            if (spec.tenBit) output.dynamicRangeProfile = DynamicRangeProfiles.HLG10
            session = device.createSessionAsync(listOf(output), cameraExecutor)

            // 3. Request
            stage = "request"
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
            stage = "gravando"
            delay(durationMs)
            session.stopRepeating()
            delay(200)
            codec.signalEndOfInputStream()
            withTimeout(5_000) { eos.await() }
            report.put("status", "ok")
        } catch (t: Throwable) {
            report.put("status", "falhou").put("etapa", stage).put("erro", t.toString())
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
}
