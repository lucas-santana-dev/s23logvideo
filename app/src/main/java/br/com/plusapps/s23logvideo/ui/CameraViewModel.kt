package br.com.plusapps.s23logvideo.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import br.com.plusapps.s23logvideo.audio.AudioInput
import br.com.plusapps.s23logvideo.audio.AudioMeter
import br.com.plusapps.s23logvideo.audio.AudioSourceOpt
import br.com.plusapps.s23logvideo.camera.Lens
import br.com.plusapps.s23logvideo.remote.Hotspot
import br.com.plusapps.s23logvideo.remote.RemoteApi
import br.com.plusapps.s23logvideo.remote.RemoteServer
import kotlinx.coroutines.runBlocking
import br.com.plusapps.s23logvideo.audio.toDbfs
import br.com.plusapps.s23logvideo.gl.MonitorOptions
import br.com.plusapps.s23logvideo.gl.Scopes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.com.plusapps.s23logvideo.CaptureService
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
    private val meter = AudioMeter { audioInput.build(app, 16_384) }

    // ---- Fase 5: lentes, áudio, giroscópio, térmico ----
    var lens by mutableStateOf(Lens.MAIN)
        private set
    var audioInput by mutableStateOf(AudioInput())
        private set
    var withGyro by mutableStateOf(true)
        private set
    val unprocessedSupported = AudioInput.unprocessedSupported(app)
    var audioDevices by mutableStateOf(AudioInput.inputs(app))
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
    /** Mensagem curta na tela. Some sozinha (ver [notify]); só "Abrindo câmera…" e afins ficam até acabar. */
    var status by mutableStateOf("")
        private set
    private var statusJob: Job? = null

    /** Esconde os widgets: só ficam o botão de gravar, o cronômetro e o botão de mostrar de novo. */
    var cleanScreen by mutableStateOf(false)
        private set
    private var warnedHot = false

    fun toggleCleanScreen() {
        cleanScreen = !cleanScreen
    }

    /** Mostra [msg] por [ms] e apaga (se nada mais tiver sido mostrado por cima). */
    fun notify(msg: String, ms: Long = 5_000) {
        status = msg
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(ms)
            if (status == msg) status = ""
        }
    }
    var lastResult by mutableStateOf<String?>(null)
        private set

    /** Vem da Activity quando a permissão do microfone é conhecida; liga o VU fora da gravação. */
    var withAudio = false
        set(v) {
            field = v
            if (v && !recording) meter.start() else if (!v) meter.stop()
            // O serviço pode ter subido antes de a permissão do microfone ser conhecida: refaz com o tipo certo.
            if (serviceOn) updateKeepAlive()
        }

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
        prefs.getString(KEY_LENS, null)?.let { n -> Lens.entries.firstOrNull { it.name == n } }?.let {
            runCatching { camera.selectLens(it); lens = it }
            if (it.maxWidth < config.width) config = config.withMode(1920, 1080, config.fps)
        }
        prefs.getString(KEY_AUDIO, null)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            audioInput = AudioInput(
                source = AudioSourceOpt.entries.firstOrNull { it.name == o.optString("fonte") } ?: AudioSourceOpt.UNPROCESSED,
                gainDb = o.optInt("ganhoDb", 0),
                deviceId = 0, // IDs de dispositivo mudam a cada conexão: começa sempre no automático
            )
        }
        withGyro = prefs.getBoolean(KEY_GYRO, true)
        // O controle remoto volta ligado se estava ligado (celular posicionado longe, app reaberto).
        if (prefs.getBoolean(KEY_REMOTE, false)) scope.launch(Dispatchers.Main) { startRemote() }
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
        // Tela bloqueada / app em segundo plano com gravação ou remoto ativos: o CaptureService mantém
        // o acesso à câmera, então nada é fechado. A tela só para de desenhar o preview.
        if (serviceOn && (recording || remoteOn)) return@op
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
                val peak = recorder?.audioPeak ?: (meter.peak * audioInput.gainLinear).coerceAtMost(1f)
                val db = toDbfs(peak)
                hold = maxOf(db, hold - 2f)
                audioDb = hold
                if (peak >= 0.99f && recording) audioClipped = true
                if (tick++ % 10 == 0) {
                    thermalStatus = power.currentThermalStatus
                    thermalHeadroom = power.getThermalHeadroom(10).takeIf { !it.isNaN() }
                    recorder?.thermalSample(thermalStatus, thermalHeadroom)
                    checkThermal()
                }
                delay(100)
            }
        }
    }

    /**
     * Avisa antes de o sistema derrubar a gravação e, no estado CRÍTICO, para o take por conta própria
     * (o arquivo é fechado direito, em vez de o Android matar a câmera no meio).
     */
    private fun checkThermal() {
        val h = thermalHeadroom
        when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL && recording -> {
                notify("Aparelho muito quente: gravação parada para salvar o arquivo.", 15_000)
                toggleRecording()
            }
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || (h != null && h >= 0.9f) -> {
                // Avisa uma vez ao entrar no estado; depois fica só o chip térmico colorido.
                if (!warnedHot) notify("Aparelho quente: o sistema pode parar a câmera em breve.", 6_000)
                warnedHot = true
            }
            else -> warnedHot = false
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
        }.onFailure { notify("LUT inválida ($name): ${it.message}", 8_000) }
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

    /** Modo (resolução) de antes de a tele forçar 1080p, para voltar a ele ao sair da tele. */
    private var modeBeforeDowngrade: Pair<Int, Int>? = null

    /** Troca de lente: reabre a câmera. A tele só vai até 1080p, então o modo desce junto (e volta depois). */
    fun selectLens(l: Lens) {
        if (recording || l == lens) return
        op("Trocando para ${l.label}…") {
            camera.selectLens(l)
            lens = l
            prefs.edit().putString(KEY_LENS, l.name).apply()
            val back = modeBeforeDowngrade
            if (l.maxWidth < config.width) {
                modeBeforeDowngrade = config.width to config.height
                config = config.withMode(1920, 1080, config.fps)
            } else if (back != null && back.first <= l.maxWidth) {
                config = config.withMode(back.first, back.second, config.fps)
                modeBeforeDowngrade = null
            }
            renderer.sensorOrientation = camera.sensorOrientation
            pushMonitor()
            if (camera.isOpen) camera.open(renderer.surfaceTexture.await(), config)
            updateFreeSpace()
        }
    }

    fun updateAudioInput(new: AudioInput) {
        if (recording || new == audioInput) return
        audioInput = new
        prefs.edit().putString(KEY_AUDIO, JSONObject().put("fonte", new.source.name).put("ganhoDb", new.gainDb).toString()).apply()
        // O medidor reabre com a nova entrada.
        meter.stop()
        if (withAudio) meter.start()
    }

    fun refreshAudioDevices() {
        audioDevices = AudioInput.inputs(app)
    }

    fun toggleGyro() {
        if (recording) return
        withGyro = !withGyro
        prefs.edit().putBoolean(KEY_GYRO, withGyro).apply()
    }


    // ---- Controle remoto pela web ----
    private var server: RemoteServer? = null
    private val hotspot = Hotspot(app)

    /** PIN de 4 dígitos que a página web pede; fixo entre aberturas do app (dá para trocar). */
    var remotePin by mutableStateOf(prefs.getString(KEY_PIN, null) ?: RemoteServer.newPin().also { prefs.edit().putString(KEY_PIN, it).apply() })
        private set
    /** Endereço principal para digitar no navegador (null = servidor desligado). */
    var remoteUrl by mutableStateOf<String?>(null)
        private set
    /** Todos os endereços (Wi-Fi, hotspot), com o nome da rede. */
    var remoteAddresses by mutableStateOf<List<String>>(emptyList())
        private set
    var hotspotInfo by mutableStateOf<Hotspot.Info?>(null)
        private set

    /** A Activity troca isto por um pedido real da permissão NEARBY_WIFI_DEVICES. */
    var askNearbyPermission: ((Boolean) -> Unit) -> Unit = { it(true) }

    val remoteOn get() = server?.running == true

    /** O CaptureService (primeiro plano, câmera + microfone) está ativo. */
    private var serviceOn = false

    /**
     * Liga o CaptureService enquanto houver gravação ou remoto (para continuar com a tela bloqueada) e
     * desliga quando não houver nenhum dos dois. Tem que ser chamado com o app visível para ligar.
     */
    private fun updateKeepAlive() {
        val need = recording || remoteOn
        if (need) {
            val text = when {
                recording && remoteOn -> "Gravando · controle remoto ligado"
                recording -> "Gravando"
                else -> "Controle remoto ligado${remoteUrl?.let { " · $it" } ?: ""}"
            }
            CaptureService.start(app, text, withAudio)
            serviceOn = true
        } else if (serviceOn) {
            CaptureService.stop(app)
            serviceOn = false
        }
    }

    fun toggleRemote() = if (remoteOn) stopRemote() else startRemote()

    fun startRemote() {
        if (remoteOn) return
        runCatching {
            server = RemoteServer(app, remoteApi, remotePin).also { it.start() }
            prefs.edit().putBoolean(KEY_REMOTE, true).apply()
            refreshAddresses()
            updateKeepAlive()
            notify("Controle remoto ligado: abra ${remoteUrl ?: "o endereço"} no outro aparelho (PIN $remotePin)", 10_000)
        }.onFailure { notify("Controle remoto falhou: ${it.message}", 10_000) }
    }

    fun stopRemote() {
        server?.stop()
        server = null
        remoteUrl = null
        remoteAddresses = emptyList()
        prefs.edit().putBoolean(KEY_REMOTE, false).apply()
        updateKeepAlive()
    }

    fun newRemotePin() {
        remotePin = RemoteServer.newPin()
        prefs.edit().putString(KEY_PIN, remotePin).apply()
        if (remoteOn) { stopRemote(); startRemote() }
    }

    /** Liga/desliga a rede própria do app (hotspot local, sem internet). */
    fun toggleHotspot() {
        if (hotspot.active) {
            hotspot.stop()
            hotspotInfo = null
            refreshAddresses()
            return
        }
        askNearbyPermission { ok ->
            if (!ok) {
                notify("Sem a permissão de dispositivos Wi-Fi próximos não dá para criar a rede.", 8_000)
                return@askNearbyPermission
            }
            hotspot.start { r ->
                r.onSuccess {
                    hotspotInfo = it
                    if (!remoteOn) startRemote()
                    // A interface do hotspot leva um instante para ganhar IP.
                    scope.launch { repeat(4) { delay(1_500); refreshAddresses() } }
                }.onFailure { notify(it.message ?: "Hotspot falhou", 10_000) }
            }
        }
    }

    fun refreshAddresses() {
        val port = server?.port ?: return
        val all = RemoteServer.addresses()
        remoteAddresses = all.map { (iface, ip) ->
            val net = when {
                iface == "wlan0" -> "Wi-Fi"
                iface.startsWith("swlan") || iface.startsWith("ap") || iface == "wlan1" || iface.startsWith("wlan2") -> "rede do app"
                iface.startsWith("rmnet") || iface.startsWith("ccmni") -> "dados móveis"
                else -> iface
            }
            "http://$ip:$port  ($net)"
        }.filterNot { it.contains("dados móveis") }
        remoteUrl = all.firstOrNull { it.first == "wlan0" }?.let { "${it.second}:$port" }
            ?: all.firstOrNull { !it.first.startsWith("rmnet") }?.let { "${it.second}:$port" }
    }

    private val remoteApi = object : RemoteApi {
        override fun stateJson(): JSONObject = runBlocking(Dispatchers.Main) { remoteState() }
        override fun command(cmd: JSONObject): JSONObject = runBlocking(Dispatchers.Main) {
            runCatching { remoteCommand(cmd) }.getOrElse { JSONObject().put("ok", false).put("erro", it.message) }
        }
        override fun latestJpeg() = renderer.latestJpeg()
        override fun setStreaming(on: Boolean) {
            renderer.remoteStreaming = on
        }
    }

    /** Estado para a página web (lido a cada ~0,5 s). */
    private fun remoteState(): JSONObject {
        val s = settings
        val m = measured
        return JSONObject()
            .put("recording", recording).put("busy", busy).put("elapsedMs", elapsedMs).put("dropped", droppedFrames)
            .put("status", status).put("lastResult", lastResult ?: JSONObject.NULL)
            .put("config", JSONObject().put("w", config.width).put("h", config.height).put("fps", config.fps)
                .put("log", config.isLog).put("label", config.label))
            .put("lens", lens.name)
            .put("lenses", org.json.JSONArray().apply {
                Lens.entries.forEach { put(JSONObject().put("name", it.name).put("label", it.label).put("maxWidth", it.maxWidth)) }
            })
            .put("settings", s.toJson())
            .put("measured", JSONObject().put("iso", m.iso ?: JSONObject.NULL).put("exposureNs", m.exposureNs ?: JSONObject.NULL)
                .put("focusDiopters", m.focusDiopters?.toDouble() ?: JSONObject.NULL))
            .put("isoValues", org.json.JSONArray(isoValues))
            .put("shutterValues", org.json.JSONArray(shutterValues))
            .put("shutter180Ns", shutter180Ns)
            .put("minFocusDiopters", minFocusDiopters.toDouble())
            .put("hasManualSensor", hasManualSensor)
            .put("monitor", JSONObject().put("lut", monitor.lut).put("falseColor", monitor.falseColor)
                .put("zebra", monitor.zebra).put("peaking", monitor.peaking).put("scopes", monitor.scopes))
            .put("lutNames", org.json.JSONArray(lutNames)).put("lutName", lutName ?: JSONObject.NULL)
            .put("presets", org.json.JSONArray(presets.map { it.name }))
            .put("audio", JSONObject().put("on", withAudio).put("db", audioDb.toDouble()).put("clipped", audioClipped)
                .put("gainDb", audioInput.gainDb))
            .put("thermal", JSONObject().put("status", thermalStatus).put("headroom", thermalHeadroom?.toDouble() ?: JSONObject.NULL))
            .put("freeMinutes", freeMinutes ?: JSONObject.NULL)
            .put("histogram", scopes?.let { org.json.JSONArray(it.histogram) } ?: JSONObject.NULL)
            .put("focusA", focusA?.toDouble() ?: JSONObject.NULL).put("focusB", focusB?.toDouble() ?: JSONObject.NULL)
    }

    /** Um comando da página web: {"acao": "...", ...}. Roda na thread principal. */
    private fun remoteCommand(c: JSONObject): JSONObject {
        val s = settings
        when (c.optString("acao")) {
            "gravar" -> if (!recording) toggleRecording()
            "parar" -> if (recording) toggleRecording()
            "alternarGravacao" -> toggleRecording()
            "lente" -> selectLens(Lens.valueOf(c.getString("valor")))
            "modo" -> selectConfig(config.withMode(c.getInt("w"), c.getInt("h"), c.getInt("fps")))
            "log" -> selectConfig(config.withLog(c.getBoolean("valor")))
            "monitor" -> {
                val o = monitor
                updateMonitor(o.copy(
                    lut = c.optBoolean("lut", o.lut), falseColor = c.optBoolean("falseColor", o.falseColor),
                    zebra = c.optBoolean("zebra", o.zebra), peaking = c.optBoolean("peaking", o.peaking),
                    scopes = c.optBoolean("scopes", o.scopes),
                ))
            }
            "lut" -> selectLut(c.getString("valor"))
            "exposicao" -> setExposureMode(ExposureMode.valueOf(c.getString("valor")))
            "iso" -> updateSettings(s.copy(exposure = ExposureMode.MANUAL, iso = c.getInt("valor")))
            "obturador" -> updateSettings(s.copy(exposure = ExposureMode.MANUAL, exposureNs = c.getLong("valor")))
            "wb" -> setWbMode(WbMode.valueOf(c.getString("valor")))
            "kelvin" -> updateSettings(s.copy(wb = WbMode.KELVIN, kelvin = c.getInt("valor")))
            "foco" -> setFocusMode(FocusMode.valueOf(c.getString("valor")))
            "distanciaFoco" -> setFocus(c.getDouble("valor").toFloat())
            "tocar" -> {
                val p = renderer.imageToBuffer(c.getDouble("u").toFloat(), c.getDouble("v").toFloat())
                    ?: return JSONObject().put("ok", false).put("erro", "fora da imagem")
                camera.tapToFocus(p.first, p.second) { d ->
                    scope.launch { if (d != null) updateSettings(settings.copy(focus = FocusMode.MANUAL, focusDiopters = d)) }
                }
            }
            "marcarA" -> markFocusA()
            "marcarB" -> markFocusB()
            "puxarA" -> focusA?.let { pullFocusTo(it) }
            "puxarB" -> focusB?.let { pullFocusTo(it) }
            "preset" -> presets.firstOrNull { it.name == c.getString("valor") }?.let { applyPreset(it) }
            "ganho" -> updateAudioInput(audioInput.copy(gainDb = c.getInt("valor")))
            else -> return JSONObject().put("ok", false).put("erro", "ação desconhecida: ${c.optString("acao")}")
        }
        return JSONObject().put("ok", true)
    }

    /**
     * "Padrão de fábrica": apaga todas as preferências (ajustes, presets, monitor, lente, áudio, remoto,
     * PIN) e volta ao estado de instalação. Não mexe nos vídeos gravados. Não roda durante um take.
     */
    fun resetToFactory() {
        if (recording) {
            notify("Pare a gravação antes de restaurar o padrão.", 6_000)
            return
        }
        stopRemote()
        if (hotspot.active) hotspot.stop()
        hotspotInfo = null
        focusRamp?.cancel()
        prefs.edit().clear().commit()

        settings = ManualSettings()
        camera.settings = settings
        presets = Preset.DEFAULTS
        monitor = MonitorOptions()
        withGyro = true
        audioInput = AudioInput()
        focusA = null
        focusB = null
        cleanScreen = false
        modeBeforeDowngrade = null
        remotePin = RemoteServer.newPin().also { prefs.edit().putString(KEY_PIN, it).apply() }
        lutNames.firstOrNull { it.contains("Rec709") }?.let { selectLut(it) }
        pushMonitor()
        meter.stop()
        if (withAudio) meter.start()

        val factory = VideoConfig(3840, 2160, 30, tenBit = true, curve = true)
        op("Restaurando o padrão…") {
            camera.selectLens(Lens.MAIN)
            lens = Lens.MAIN
            config = factory
            renderer.sensorOrientation = camera.sensorOrientation
            pushMonitor()
            if (camera.isOpen) camera.open(renderer.surfaceTexture.await(), factory)
            updateFreeSpace()
        }
        notify("Padrão de fábrica restaurado.", 5_000)
    }

    fun toggleRecording() = op(null) {
        if (recording) stopRecordingLocked() else startRecordingLocked()
    }

    private suspend fun startRecordingLocked() {
        check(camera.isOpen) { "câmera fechada" }
        meter.stop()
        audioClipped = false
        val rec = Recorder(app, camera, withAudio, audioInput, withGyro)
        withContext(Dispatchers.Default) { rec.start(orientationHint = uprightRotation()) }
        recorder = rec
        recording = true
        updateKeepAlive()
        lastResult = null
        timer = scope.launch {
            var tick = 0
            while (isActive) {
                elapsedMs = rec.elapsedMs
                droppedFrames = rec.droppedFrames
                if (tick++ % 25 == 0) {
                    updateFreeSpace()
                    if ((freeMinutes ?: Long.MAX_VALUE) < 1) {
                        notify("Espaço acabando: gravação parada.", 15_000)
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
        updateKeepAlive()
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
        // O resumo de um take bom some depois de 8 s; o de falha fica até o próximo take.
        val shown = lastResult
        if (report.optString("status") == "ok") scope.launch {
            delay(8_000)
            if (lastResult == shown) lastResult = null
        }
        updateFreeSpace()
    }

    override fun onCleared() {
        // Não cancela o escopo: termina a gravação (se houver) e só então libera tudo.
        scope.launch {
            mutex.withLock {
                runCatching { if (recording) stopRecordingLocked() }
                meter.stop()
                server?.stop()
                hotspot.stop()
                CaptureService.stop(app)
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
        private const val KEY_LENS = "lente"
        private const val KEY_AUDIO = "audio"
        private const val KEY_GYRO = "giro"
        private const val KEY_REMOTE = "remoto"
        private const val KEY_PIN = "pinRemoto"
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
                    notify("Erro: ${t.message ?: t}", 10_000)
                } finally {
                    busy = false
                }
            }
        }
    }
}
