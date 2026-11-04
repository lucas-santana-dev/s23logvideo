package br.com.plusapps.s23logvideo.ui

import android.app.Application
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.com.plusapps.s23logvideo.camera.CameraController
import br.com.plusapps.s23logvideo.gl.PreviewRenderer
import br.com.plusapps.s23logvideo.record.Recorder
import br.com.plusapps.s23logvideo.record.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Estado da câmera e da gravação. Sobrevive a recriações da Activity.
 * As operações rodam num escopo próprio (não cancelado em onCleared) para que
 * uma gravação sempre termine e o arquivo seja fechado.
 */
class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    val renderer = PreviewRenderer()
    private val camera = CameraController(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    /** Log 10 bits por padrão (Fase 2). */
    var config by mutableStateOf(VideoConfig(3840, 2160, 30, tenBit = true, curve = true))
        private set

    /** Preferência do usuário pelo monitor Rec.709; só tem efeito com a imagem em Log. */
    var monitor709 by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var droppedFrames by mutableIntStateOf(0)
        private set
    var freeMinutes by mutableStateOf<Long?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var lastResult by mutableStateOf<String?>(null)
        private set

    var withAudio = false

    @Volatile
    private var displayRotation = 90
    private var recorder: Recorder? = null
    private var timer: Job? = null

    /** Rotação da tela em graus (0/90/180/270), vinda da Activity. */
    fun onDisplayRotation(degrees: Int) {
        displayRotation = degrees
        renderer.displayDegrees = degrees
    }

    fun onStart() = op("Abrindo câmera…") {
        if (!camera.isOpen) {
            renderer.sensorOrientation = camera.sensorOrientation
            camera.open(renderer.surfaceTexture.await(), config)
        }
        updateFreeSpace()
    }

    fun onStop() = op(null) {
        if (recording) stopRecordingLocked()
        camera.close()
    }

    fun selectConfig(new: VideoConfig) {
        if (recording || new == config) return
        config = new
        applyMonitor()
        op("Reconfigurando…") {
            if (camera.isOpen) camera.open(renderer.surfaceTexture.await(), new)
            updateFreeSpace()
        }
    }

    fun toggleMonitor709() {
        if (recording) return
        monitor709 = !monitor709
        applyMonitor()
    }

    /** A conversão Log → 709 só vale para imagem em Log; em Normal o preview fica como está. */
    private fun applyMonitor() {
        renderer.monitor709 = monitor709 && config.isLog
    }

    fun toggleRecording() = op(null) {
        if (recording) stopRecordingLocked() else startRecordingLocked()
    }

    private suspend fun startRecordingLocked() {
        check(camera.isOpen) { "câmera fechada" }
        val rec = Recorder(app, camera, withAudio)
        withContext(Dispatchers.Default) { rec.start(orientationHint = uprightRotation()) }
        recorder = rec
        recording = true
        lastResult = null
        timer = scope.launch {
            var tick = 0
            while (isActive) {
                elapsedMs = rec.elapsedMs
                droppedFrames = rec.droppedFrames
                if (tick++ % 25 == 0) {
                    updateFreeSpace()
                    if ((freeMinutes ?: Long.MAX_VALUE) < 1) {
                        status = "Espaço acabando: gravação parada."
                        toggleRecording()
                        break
                    }
                }
                delay(200)
            }
        }
    }

    private suspend fun stopRecordingLocked() {
        val rec = recorder ?: return
        timer?.cancel()
        val report = withContext(Dispatchers.Default) { rec.stop() }
        recorder = null
        recording = false
        val cam = report.optJSONObject("camera")
        val mux = report.optJSONObject("muxer")
        lastResult = if (report.optString("status") == "ok") {
            "Salvo: ${report.optString("arquivo")} · %.1f fps · perdidos %d · A−V %s ms".format(
                cam?.optDouble("fpsMedio", 0.0) ?: 0.0,
                cam?.optInt("quadrosPerdidos") ?: 0,
                mux?.opt("inicioAudioMenosVideoMs")?.toString() ?: "-",
            )
        } else {
            "Falhou: arquivo descartado (veja Download/LogCam/gravacoes)"
        }
        updateFreeSpace()
    }

    override fun onCleared() {
        // Não cancela o escopo: termina a gravação (se houver) e só então libera tudo.
        scope.launch {
            mutex.withLock {
                runCatching { if (recording) stopRecordingLocked() }
                camera.release()
                renderer.release()
            }
        }
    }

    /** Rotação que deixa a imagem em pé: serve para o preview e para o hint do MP4. */
    private fun uprightRotation() = (camera.sensorOrientation - displayRotation + 360) % 360

    private fun updateFreeSpace() {
        val dir = app.getExternalFilesDir(null) ?: return
        val bytesPerMinute = (config.bitrate / 8L + 32_000L) * 60
        freeMinutes = StatFs(dir.path).availableBytes / bytesPerMinute
    }

    private fun op(message: String?, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                busy = true
                if (message != null) status = message
                try {
                    block()
                    if (message != null) status = ""
                } catch (t: Throwable) {
                    status = "Erro: ${t.message ?: t}"
                } finally {
                    busy = false
                }
            }
        }
    }
}
