package br.com.plusapps.s23logvideo.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import br.com.plusapps.s23logvideo.audio.AudioMeter
import br.com.plusapps.s23logvideo.audio.toDbfs
import br.com.plusapps.s23logvideo.gl.MonitorOptions
import br.com.plusapps.s23logvideo.gl.Scopes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.com.plusapps.s23logvideo.camera.CameraController
import br.com.plusapps.s23logvideo.camera.ExposureMode
import br.com.plusapps.s23logvideo.camera.FocusMode
import br.com.plusapps.s23logvideo.camera.ManualSettings
import br.com.plusapps.s23logvideo.camera.Measured
import br.com.plusapps.s23logvideo.camera.Preset
import br.com.plusapps.s23logvideo.camera.WbMode
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
import org.json.JSONObject

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

    // ---- Fase 4: monitoramento (só a tela) ----
    var monitor by mutableStateOf(MonitorOptions())
        private set
    /** LUTs disponíveis (assets, vindas de luts/). */
    val lutNames: List<String> = runCatching { app.assets.list("").orEmpty().filter { it.endsWith(".cube") }.sorted() }
        .getOrDefault(emptyList())
    var lutName by mutableStateOf<String?>(null)
        private set
    var scopes by mutableStateOf<Scopes?>(null)
        private set
    /** Pico de áudio em dBFS (com retenção de pico) e o maior pico do take. */
    var audioDb by mutableStateOf(-100f)
        private set
    var audioClipped by mutableStateOf(false)
        private set
    var thermalStatus by mutableIntStateOf(0)
        private set
    var thermalHeadroom by mutableStateOf<Float?>(null)
        private set
    private val power = app.getSystemService(PowerManager::class.java)
    private val meter = AudioMeter { AudioMeter.defaultRecord() }
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

    // ---- Fase 3: controles manuais ----
    private val prefs = app.getSharedPreferences("logcam", Context.MODE_PRIVATE)

    var settings by mutableStateOf(
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { ManualSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ManualSettings()
    )
        private set
    var presets by mutableStateOf(
        prefs.getString(KEY_PRESETS, null)?.let { Preset.listFromJson(it) } ?: Preset.DEFAULTS
    )
        private set
    var measured by mutableStateOf(Measured(null, null, null, null, null))
        private set

    /** Último toque para focar, em pixels da view (para desenhar o quadradinho). */
    var tapPoint by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    var focusA by mutableStateOf<Float?>(null)
        private set
    var focusB by mutableStateOf<Float?>(null)
        private set
    private var focusRamp: Job? = null
    private var hudLoop: Job? = null

    val minFocusDiopters get() = camera.minFocusDiopters
    val hasManualSensor get() = camera.hasManualSensor
    val wbCalibrated get() = camera.wbCalibrated

    val isoValues: List<Int>
        get() = ManualSettings.ISO_STEPS.filter { it in camera.isoRange.lower..camera.isoRange.upper }

    /** Obturadores possíveis no fps atual: não passa da duração do quadro. */
    val shutterValues: List<Long>
        get() {
            val frameNs = 1_000_000_000L / config.fps
            return ManualSettings.SHUTTER_DENOMS.map { 1_000_000_000L / it }
                .filter { it <= frameNs && it in camera.exposureRange.lower..camera.exposureRange.upper }
        }

    /** Regra dos 180°: obturador = metade da duração do quadro. */
    val shutter180Ns get() = 1_000_000_000L / (config.fps * 2)

    @Volatile
    private var displayRotation = 90
    private var recorder: Recorder? = null
    private var timer: Job? = null

    /** Rotação da tela em graus (0/90/180/270), vinda da Activity. */
    fun onDisplayRotation(degrees: Int) {
        displayRotation = degrees
        renderer.displayDegrees = degrees
    }

    init {
        camera.settings = settings
        monitor = loadMonitor()
        val wanted = prefs.getString(KEY_LUT, null)?.takeIf { it in lutNames }
            ?: lutNames.firstOrNull { it.contains("Rec709") } ?: lutNames.firstOrNull()
        wanted?.let { selectLut(it) }
        pushMonitor()
    }

    fun onStart() = op("Abrindo câmera…") {
        startHudLoop()
        if (withAudio) meter.start()
        if (!camera.isOpen) {
            renderer.sensorOrientation = camera.sensorOrientation
            camera.open(renderer.surfaceTexture.await(), config)
        }
        updateFreeSpace()
    }

    fun onStop() = op(null) {
        hudLoop?.cancel()
        focusRamp?.cancel()
        meter.stop()
        if (recording) stopRecordingLocked()
        camera.close()
    }

    fun selectConfig(new: VideoConfig) {
        if (recording || new == config) return
        config = new
        pushMonitor()
        op("Reconfigurando…") {
            if (camera.isOpen) camera.open(renderer.surfaceTexture.await(), new)
            updateFreeSpace()
        }
    }

    fun updateSettings(new: ManualSettings) {
        if (new == settings) return
        settings = new
        camera.settings = new
        camera.refresh()
        prefs.edit().putString(KEY_SETTINGS, new.toJson().toString()).apply()
    }

    /** Ao passar para MANUAL, parte do que o automático estava medindo. */
    fun setExposureMode(mode: ExposureMode) {
        val m = camera.measured
        updateSettings(
            if (mode == ExposureMode.MANUAL && settings.exposure != ExposureMode.MANUAL) {
                settings.copy(
                    exposure = mode,
                    iso = m.iso?.let { nearest(isoValues, it) } ?: settings.iso,
                    exposureNs = m.exposureNs?.let { ns -> shutterValues.minByOrNull { kotlin.math.abs(it - ns) } } ?: settings.exposureNs,
                )
            } else settings.copy(exposure = mode)
        )
    }

    fun setWbMode(mode: WbMode) = updateSettings(settings.copy(wb = mode))

    fun setFocusMode(mode: FocusMode) {
        val d = camera.measured.focusDiopters
        updateSettings(
            if (mode == FocusMode.MANUAL && settings.focus == FocusMode.AUTO && d != null) {
                settings.copy(focus = mode, focusDiopters = d)
            } else settings.copy(focus = mode)
        )
    }

    fun setFocus(diopters: Float) {
        focusRamp?.cancel()
        updateSettings(settings.copy(focus = FocusMode.MANUAL, focusDiopters = diopters.coerceIn(0f, minFocusDiopters)))
    }

    /** Toque no preview: AF de disparo único na região e, quando travar, foco MANUAL nessa distância. */
    fun onPreviewTap(x: Float, y: Float) {
        val p = renderer.viewToBuffer(x, y) ?: return
        if (minFocusDiopters <= 0f) return
        tapPoint = x to y
        focusRamp?.cancel()
        camera.tapToFocus(p.first, p.second) { d ->
            scope.launch {
                if (d != null) updateSettings(settings.copy(focus = FocusMode.MANUAL, focusDiopters = d))
                delay(800)
                tapPoint = null
            }
        }
    }

    fun markFocusA() { focusA = settings.focusDiopters }
    fun markFocusB() { focusB = settings.focusDiopters }

    /** Puxada de foco suave até [target] (curva ease-in-out), em [durationMs]. */
    fun pullFocusTo(target: Float, durationMs: Long = 1500) {
        focusRamp?.cancel()
        val from = settings.focusDiopters
        focusRamp = scope.launch {
            val steps = (durationMs / 33).coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val e = t * t * (3 - 2 * t)
                updateSettings(settings.copy(focus = FocusMode.MANUAL, focusDiopters = from + (target - from) * e))
                delay(33)
            }
        }
    }

    fun applyPreset(p: Preset) = updateSettings(p.settings)

    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { "Preset ${presets.size + 1}" }
        presets = presets.filterNot { it.name == clean } + Preset(clean, settings)
        prefs.edit().putString(KEY_PRESETS, Preset.listToJson(presets)).apply()
    }

    fun deletePreset(p: Preset) {
        presets = presets - p
        prefs.edit().putString(KEY_PRESETS, Preset.listToJson(presets)).apply()
    }

    private fun startHudLoop() {
        if (hudLoop?.isActive == true) return
        hudLoop = scope.launch {
            var tick = 0
            var hold = -100f
            while (isActive) {
                measured = camera.measured
                scopes = renderer.scopes
                // VU: pico com queda de ~20 dB/s.
                val peak = recorder?.audioPeak ?: meter.peak
                val db = toDbfs(peak)
                hold = maxOf(db, hold - 2f)
                audioDb = hold
                if (peak >= 0.99f && recording) audioClipped = true
                if (tick++ % 10 == 0) {
                    thermalStatus = power.currentThermalStatus
                    thermalHeadroom = power.getThermalHeadroom(10).takeIf { !it.isNaN() }
                }
                delay(100)
            }
        }
    }

    private fun nearest(list: List<Int>, v: Int) = list.minByOrNull { kotlin.math.abs(it - v) } ?: v

    /** Muda uma opção de monitoramento; vale na hora, inclusive durante a gravação. */
    fun updateMonitor(new: MonitorOptions) {
        monitor = new
        pushMonitor()
        prefs.edit().putString(KEY_MONITOR, JSONObject()
            .put("lut", new.lut).put("falseColor", new.falseColor).put("zebra", new.zebra)
            .put("zebraLevel", new.zebraLevel.toDouble()).put("peaking", new.peaking)
            .put("peakingThreshold", new.peakingThreshold.toDouble()).put("peakingColor", new.peakingColor)
            .put("scopes", new.scopes).toString()).apply()
    }

    fun selectLut(name: String) {
        runCatching {
            val text = app.assets.open(name).bufferedReader().use { it.readText() }
            renderer.setLut(name, text)
            lutName = name
            prefs.edit().putString(KEY_LUT, name).apply()
        }.onFailure { status = "LUT inválida ($name): ${it.message}" }
    }

    /** A LUT Log → 709 só vale para imagem em Log; em Normal o preview fica como está. */
    private fun pushMonitor() {
        renderer.options = monitor.copy(lut = monitor.lut && config.isLog, isLog = config.isLog)
    }

    private fun loadMonitor(): MonitorOptions {
        val o = prefs.getString(KEY_MONITOR, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return MonitorOptions()
        val d = MonitorOptions()
        return MonitorOptions(
            lut = o.optBoolean("lut", d.lut), falseColor = o.optBoolean("falseColor"), zebra = o.optBoolean("zebra"),
            zebraLevel = o.optDouble("zebraLevel", d.zebraLevel.toDouble()).toFloat(),
            peaking = o.optBoolean("peaking"),
            peakingThreshold = o.optDouble("peakingThreshold", d.peakingThreshold.toDouble()).toFloat(),
            peakingColor = o.optInt("peakingColor", d.peakingColor), scopes = o.optBoolean("scopes"),
        )
    }

    fun toggleRecording() = op(null) {
        if (recording) stopRecordingLocked() else startRecordingLocked()
    }

    private suspend fun startRecordingLocked() {
        check(camera.isOpen) { "câmera fechada" }
        meter.stop()
        audioClipped = false
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
        if (withAudio) meter.start()
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
                meter.stop()
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

    companion object {
        private const val KEY_SETTINGS = "ajustes"
        private const val KEY_PRESETS = "presets"
        private const val KEY_MONITOR = "monitor"
        private const val KEY_LUT = "lut"
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
