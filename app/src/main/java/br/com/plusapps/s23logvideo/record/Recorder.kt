package br.com.plusapps.s23logvideo.record

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import br.com.plusapps.s23logvideo.camera.CameraController
import br.com.plusapps.s23logvideo.probe.ProbeRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Uma gravação: vídeo da sessão de câmera já aberta + áudio, num MP4 em DCIM/LogCam.
 * Ao parar, salva um relatório JSON em Download/LogCam/gravacoes.
 */
class Recorder(
    private val context: Context,
    private val camera: CameraController,
    private val withAudio: Boolean,
    private val audioInput: br.com.plusapps.s23logvideo.audio.AudioInput = br.com.plusapps.s23logvideo.audio.AudioInput(),
    private val withGyro: Boolean = true,
) {
    private val gyro = if (withGyro) GyroLogger(context).takeIf { it.available } else null
    private val thermalLog = org.json.JSONArray()
    private var maxSkewNs = 0L

    /** Amostra térmica (a cada ~1 s, vinda do ViewModel) para o relatório. */
    fun thermalSample(status: Int, headroom: Float?) = synchronized(thermalLog) {
        thermalLog.put(org.json.JSONObject().put("s", elapsedMs / 1000).put("status", status)
            .put("headroom", headroom?.toDouble() ?: org.json.JSONObject.NULL))
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private val config = camera.config ?: error("câmera não configurada")
    val name = "LogCam_${ProbeRunner.timestamp()}"

    private lateinit var uri: Uri
    private lateinit var pfd: ParcelFileDescriptor
    private lateinit var sink: MuxerSink
    private lateinit var video: VideoEncoder
    private var audio: AudioEncoder? = null
    private val stats = FrameStats(1_000_000_000L / config.fps)
    private var startNanos = 0L
    private var thermalStart = 0
    private var audioError: String? = null
    private var settingsAtStart: JSONObject = JSONObject()

    val droppedFrames get() = stats.droppedFrames
    val audioPeak get() = audio?.peak ?: 0f
    val elapsedMs get() = if (startNanos == 0L) 0L else (System.nanoTime() - startNanos) / 1_000_000

    fun start(orientationHint: Int) {
        thermalStart = power.currentThermalStatus
        settingsAtStart = camera.settings.toJson().put("medidoNoInicio", camera.measured.let {
            JSONObject().put("iso", it.iso ?: JSONObject.NULL).put("exposicaoNs", it.exposureNs ?: JSONObject.NULL)
                .put("focoDioptrias", it.focusDiopters?.toDouble() ?: JSONObject.NULL)
        })
        uri = MediaStoreOut.newVideo(context, name, MediaStoreOut.RECORD_DIR)
        pfd = context.contentResolver.openFileDescriptor(uri, "rw")!!
        sink = MuxerSink(pfd.fileDescriptor, orientationHint, expectAudio = withAudio)
        if (withAudio) {
            // Áudio primeiro: se falhar, nada do vídeo foi mexido ainda.
            try {
                audio = AudioEncoder(sink, context, audioInput).also { it.start() }
            } catch (t: Throwable) {
                sink.stop()
                runCatching { pfd.close() }
                MediaStoreOut.delete(context, uri)
                throw IllegalStateException("áudio falhou: $t", t)
            }
        }
        video = camera.takeEncoder()
        video.sink = sink
        video.start()
        camera.setRepeating(recording = true, callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                stats.onResult(result)
                result.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let {
                    if (it > maxSkewNs) maxSkewNs = it
                }
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) =
                stats.onFailure()
        })
        runCatching { gyro?.start() }
        startNanos = System.nanoTime()
    }

    /** Para na ordem: câmera → EOS do vídeo → áudio → muxer. Devolve o relatório. */
    suspend fun stop(): JSONObject {
        val durationMs = elapsedMs
        camera.setRepeating(recording = false, callback = null)
        delay(150) // quadros já em voo ainda chegam ao encoder
        gyro?.stop()
        runCatching { video.signalEndOfStream() }
        val videoEos = withTimeoutOrNull(3_000) { runCatching { video.eos.await() }.isSuccess } ?: false
        sink.endAudioAfterVideo(1_000_000L / config.fps)
        audio?.stop()
        val ok = sink.stop()
        // Log: grava os metadados (com.logcam.logprofile...) no moov, depois do muxer e antes de publicar.
        val logTagged = ok && config.isLog && Mp4LogTag.apply(pfd.fileDescriptor)
        video.release()
        camera.prepareNextEncoder()
        runCatching { pfd.close() }
        if (ok) MediaStoreOut.publish(context, uri) else MediaStoreOut.delete(context, uri)

        val videoSec = (sink.lastVideoPtsUs - sink.firstVideoPtsUs) / 1e6
        val audioSec = (sink.lastAudioPtsUs - sink.firstAudioPtsUs) / 1e6
        val report = JSONObject()
            .put("arquivo", if (ok) "${MediaStoreOut.RECORD_DIR}/$name.mp4" else JSONObject.NULL)
            .put("status", if (ok) "ok" else "falhou")
            .put("config", config.toJson())
            .put("ajustes", settingsAtStart)
            .put("encoder", video.codecName)
            .put("formatoDeSaidaDoEncoder", video.outputFormat ?: JSONObject.NULL)
            .put("duracaoMs", durationMs)
            .put("eosDoVideo", videoEos)
            .put("camera", stats.toJson())
            .put(
                "muxer", JSONObject()
                    .put("amostrasVideo", sink.videoSamples)
                    .put("amostrasAudio", sink.audioSamples)
                    .put("duracaoVideoS", videoSec)
                    .put("duracaoAudioS", if (withAudio) audioSec else JSONObject.NULL)
                    .put("inicioAudioMenosVideoMs", if (withAudio) (sink.firstAudioPtsUs - sink.firstVideoPtsUs) / 1e3 else JSONObject.NULL)
                    .put("audioDescartadoAntesDoVideo", sink.droppedAudioBeforeVideo)
                    .put("audioDescartadoDepoisDoVideo", sink.droppedAudioAfterVideo)
                    .put("bitrateRealMbps", if (videoSec > 0) sink.videoBytes * 8 / videoSec / 1e6 else JSONObject.NULL)
            )
            .put("audio", audio?.toJson() ?: JSONObject().put("ativo", false).put("erro", audioError ?: JSONObject.NULL))
            .put("termico", JSONObject().put("inicio", thermalStart).put("fim", power.currentThermalStatus)
                .put("amostras", synchronized(thermalLog) { org.json.JSONArray(thermalLog.toString()) }))
            .put("lente", JSONObject().put("nome", camera.lens.name).put("cameraId", camera.cameraId)
                .put("fisica", camera.lens.physicalId ?: JSONObject.NULL)
                .put("focalMm", camera.focalLengthMm?.toDouble() ?: JSONObject.NULL))
            .put("estabilizacaoPedida", camera.stabilization.name)
            .put("bitrateDoEncoder", video.bitrate)
            .put("spsReescrito", video.spsPatched)
            .put("marcadoComoLog", logTagged)
            .put("rollingShutterMs", if (maxSkewNs > 0) maxSkewNs / 1e6 else JSONObject.NULL)
            .put("giroscopio", gyro?.let { g ->
                JSONObject().put("sensor", g.sensorName).put("amostras", g.sampleCount)
                    .put("arquivo", if (ok && g.sampleCount > 0) "$name.gcsv" else JSONObject.NULL)
            } ?: JSONObject.NULL)
        runCatching {
            MediaStoreOut.writeReport(context, "$name.json", report.toString(2), "gravacoes")
        }
        val g = gyro
        if (ok && g != null && g.sampleCount > 0) runCatching {
            val text = g.toGcsv(
                "$name.mp4", sink.firstVideoPtsUs, if (maxSkewNs > 0) maxSkewNs / 1e6 else null,
                camera.focalLengthMm, camera.lens.label,
            )
            MediaStoreOut.writeReport(context, "$name.gcsv", text, "gravacoes", mime = "application/octet-stream")
        }
        return report
    }
}
