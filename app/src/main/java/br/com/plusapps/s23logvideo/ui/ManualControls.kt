package br.com.plusapps.s23logvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.plusapps.s23logvideo.camera.ExposureMode
import br.com.plusapps.s23logvideo.camera.FocusMode
import br.com.plusapps.s23logvideo.camera.ManualSettings
import br.com.plusapps.s23logvideo.camera.WbMode

/** Qual roda está aberta acima da barra. */
enum class Panel { NONE, EXPOSURE, ISO, SHUTTER, WB, FOCUS, PRESETS, MONITOR, AUDIO, REMOTE }

private val Accent = Color(0xFFFFC107)
private val PanelBg = Color(0xCC000000)

/**
 * Barra de controles manuais (Fase 3): botões grandes, operáveis com o polegar, e uma roda de valores
 * para o ajuste selecionado. Em AUTO, os botões mostram o que a câmera está medindo, em cinza.
 */
@Composable
fun ManualControls(
    vm: CameraViewModel, panel: Panel, onPanel: (Panel) -> Unit, modifier: Modifier = Modifier,
    /** Em retrato a barra não cabe: rola na horizontal. */
    scrollBar: Boolean = false,
) {
    val s = vm.settings
    val m = vm.measured
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (panel) {
            Panel.EXPOSURE -> ModeRow(
                listOf(ExposureMode.AUTO to "AUTO", ExposureMode.LOCK to "TRAVAR", ExposureMode.MANUAL to "MANUAL"),
                s.exposure, enabled = { it != ExposureMode.MANUAL || vm.hasManualSensor },
            ) { vm.setExposureMode(it) }

            Panel.ISO -> Wheel(vm.isoValues, s.iso, { "$it" }) {
                vm.updateSettings(s.copy(exposure = ExposureMode.MANUAL, iso = it))
            }

            Panel.SHUTTER -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Wheel(vm.shutterValues, s.exposureNs, { ManualSettings.shutterLabel(it) }) {
                    vm.updateSettings(s.copy(exposure = ExposureMode.MANUAL, exposureNs = it))
                }
                BigButton("180° (${ManualSettings.shutterLabel(vm.shutter180Ns)})") {
                    vm.updateSettings(s.copy(exposure = ExposureMode.MANUAL, exposureNs = vm.shutter180Ns))
                }
            }

            Panel.WB -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeRow(
                    listOf(WbMode.AUTO to "AUTO", WbMode.LOCK to "TRAVAR", WbMode.KELVIN to "KELVIN"),
                    s.wb,
                ) { vm.setWbMode(it) }
                val kelvins = (ManualSettings.KELVIN_RANGE step 100).toList()
                Wheel(kelvins, s.kelvin, { k ->
                    val tag = ManualSettings.KELVIN_PRESETS.firstOrNull { it.first == k }?.second.orEmpty()
                    if (tag.isEmpty()) "${k}K" else "${k}K\n$tag"
                }) { vm.updateSettings(s.copy(wb = WbMode.KELVIN, kelvin = it)) }
                if (!vm.wbCalibrated) Hud("Sem calibração de cor no sensor: Kelvin aproximado")
            }

            Panel.FOCUS -> FocusPanel(vm)
            Panel.PRESETS -> PresetsPanel(vm)
            Panel.MONITOR -> MonitorPanel(vm)
            Panel.AUDIO -> AudioPanel(vm)
            Panel.REMOTE -> RemotePanel(vm)
            Panel.NONE -> Unit
        }

        Row(
            (if (scrollBar) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val manualExp = s.exposure == ExposureMode.MANUAL
            BarButton("EXP", s.exposure.label(), panel == Panel.EXPOSURE, active = s.exposure != ExposureMode.AUTO) {
                onPanel(Panel.EXPOSURE)
            }
            BarButton(
                "ISO", if (manualExp) "${s.iso}" else m.iso?.toString() ?: "-", panel == Panel.ISO, active = manualExp,
                enabled = vm.hasManualSensor,
            ) { onPanel(Panel.ISO) }
            BarButton(
                "OBT", ManualSettings.shutterLabel(if (manualExp) s.exposureNs else m.exposureNs ?: s.exposureNs),
                panel == Panel.SHUTTER, active = manualExp, enabled = vm.hasManualSensor,
            ) { onPanel(Panel.SHUTTER) }
            BarButton(
                "WB", when (s.wb) { WbMode.AUTO -> "AUTO"; WbMode.LOCK -> "TRAV."; WbMode.KELVIN -> "${s.kelvin}K" },
                panel == Panel.WB, active = s.wb != WbMode.AUTO,
            ) { onPanel(Panel.WB) }
            if (vm.minFocusDiopters > 0f) {
                val d = if (s.focus == FocusMode.MANUAL) s.focusDiopters else m.focusDiopters
                BarButton("FOCO", if (s.focus == FocusMode.AUTO) "AF" else distanceLabel(d), panel == Panel.FOCUS, active = s.focus == FocusMode.MANUAL) {
                    onPanel(Panel.FOCUS)
                }
            }
            BarButton("PRESET", "★", panel == Panel.PRESETS, active = false) { onPanel(Panel.PRESETS) }
            BarButton(
                "ÁUDIO", if (vm.audioInput.gainDb == 0) "0 dB" else "%+d dB".format(vm.audioInput.gainDb),
                panel == Panel.AUDIO, active = vm.audioInput.gainDb != 0, enabled = vm.withAudio,
            ) {
                vm.refreshAudioDevices()
                onPanel(Panel.AUDIO)
            }
            BarButton("REMOTO", if (vm.remoteOn) "ON" else "OFF", panel == Panel.REMOTE, active = vm.remoteOn) {
                vm.refreshAddresses()
                onPanel(Panel.REMOTE)
            }
        }
    }
}

@Composable
private fun FocusPanel(vm: CameraViewModel) {
    val s = vm.settings
    val max = vm.minFocusDiopters
    Column(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("∞", color = Color.White, fontSize = 18.sp)
            // Slider em dioptrias: esquerda = infinito, direita = mais perto.
            Slider(
                value = s.focusDiopters.coerceIn(0f, max), onValueChange = { vm.setFocus(it) },
                valueRange = 0f..max, modifier = Modifier.width(380.dp),
            )
            Text("perto", color = Color.White, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(if (s.focus == FocusMode.AUTO) "AF (ligado)" else "AF", selected = s.focus == FocusMode.AUTO) {
                vm.setFocusMode(FocusMode.AUTO)
            }
            BigButton("Travar", selected = s.focus == FocusMode.MANUAL) { vm.setFocusMode(FocusMode.MANUAL) }
            BigButton("Marcar A") { vm.markFocusA() }
            BigButton("Marcar B") { vm.markFocusB() }
            vm.focusA?.let { a -> BigButton("→ A ${distanceLabel(a)}") { vm.pullFocusTo(a) } }
            vm.focusB?.let { b -> BigButton("→ B ${distanceLabel(b)}") { vm.pullFocusTo(b) } }
        }
        Text("Toque na imagem para focar num ponto (o foco trava ali).", color = Color.LightGray, fontSize = 11.sp)
    }
}

/** Controle remoto pela web: liga o servidor, mostra PIN e endereços, cria a rede própria. */
@Composable
private fun RemotePanel(vm: CameraViewModel) {
    Column(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(if (vm.remoteOn) "Desligar remoto" else "Ligar remoto", selected = vm.remoteOn) { vm.toggleRemote() }
            BigButton(if (vm.hotspotInfo != null) "Desligar rede do app" else "Criar rede do app", selected = vm.hotspotInfo != null) {
                vm.toggleHotspot()
            }
            BigButton("Novo PIN") { vm.newRemotePin() }
        }
        Text("PIN: ${vm.remotePin}", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (vm.remoteOn) {
            Text("No PC ou no outro celular, abra no navegador:", color = Color.LightGray, fontSize = 11.sp)
            vm.remoteAddresses.forEach { Text(it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            if (vm.remoteAddresses.isEmpty()) Text("(sem rede: conecte ao Wi-Fi ou crie a rede do app)", color = Color.LightGray, fontSize = 12.sp)
        }
        if (vm.remoteOn) {
            Text(
                "Pode bloquear a tela: a câmera, a gravação e o remoto continuam (notificação \"LogCam\").",
                color = Color(0xFF80D8FF), fontSize = 12.sp,
            )
        }
        vm.hotspotInfo?.let {
            Text("Rede do app: ${it.ssid}   senha: ${it.password}", color = Color(0xFF80D8FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Conecte o outro aparelho a essa rede e abra o endereço \"rede do app\" acima.", color = Color.LightGray, fontSize = 11.sp)
        }
    }
}

/** Entrada de áudio (Fase 5): fonte, microfone (interno/USB-C) e ganho manual. */
@Composable
private fun AudioPanel(vm: CameraViewModel) {
    val a = vm.audioInput
    val canChange = !vm.recording
    Column(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Fonte (sem AGC; ajuste só pelo ganho abaixo)", color = Color.LightGray, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            br.com.plusapps.s23logvideo.audio.AudioSourceOpt.entries.forEach { src ->
                val ok = src != br.com.plusapps.s23logvideo.audio.AudioSourceOpt.UNPROCESSED || vm.unprocessedSupported
                BigButton(src.label, selected = a.source == src, enabled = canChange && ok) {
                    vm.updateAudioInput(a.copy(source = src))
                }
            }
        }
        Text("Microfone", color = Color.LightGray, fontSize = 11.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(listOf<android.media.AudioDeviceInfo?>(null) + vm.audioDevices) { _, d ->
                val label = d?.let { br.com.plusapps.s23logvideo.audio.AudioInput.deviceLabel(it) } ?: "Automático"
                BigButton(label, selected = a.deviceId == (d?.id ?: 0), enabled = canChange) {
                    vm.updateAudioInput(a.copy(deviceId = d?.id ?: 0))
                }
            }
        }
        Text("Ganho (picos da voz perto de -12 dBFS no VU)", color = Color.LightGray, fontSize = 11.sp)
        Wheel((-12..24 step 3).toList(), a.gainDb, { if (it > 0) "+$it" else "$it" }) {
            if (canChange) vm.updateAudioInput(a.copy(gainDb = it))
        }
        if (vm.recording) Text("Troca de entrada só fora da gravação.", color = Color.LightGray, fontSize = 11.sp)
    }
}

/** Ajustes finos do monitoramento (Fase 4): LUT, nível da zebra, peaking. E o reset de fábrica. */
@Composable
private fun MonitorPanel(vm: CameraViewModel) {
    val o = vm.monitor
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restaurar padrão de fábrica?") },
            text = {
                Text(
                    "Apaga ajustes manuais, presets salvos, opções de monitor, lente, áudio, giroscópio e " +
                        "controle remoto (o PIN muda). Os vídeos gravados não são apagados."
                )
            },
            confirmButton = { TextButton({ confirmReset = false; vm.resetToFactory() }) { Text("Restaurar") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Cancelar") } },
        )
    }
    Column(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (vm.lutNames.isNotEmpty()) {
            Text("LUT na tela (só com Log)", color = Color.LightGray, fontSize = 11.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(vm.lutNames) { _, n ->
                    BigButton(n.removeSuffix(".cube"), selected = n == vm.lutName) { vm.selectLut(n) }
                }
            }
        }
        Text("Zebra acima de (sinal gravado)", color = Color.LightGray, fontSize = 11.sp)
        Wheel((70..100 step 5).toList(), Math.round(o.zebraLevel * 100), { "$it%" }) {
            vm.updateMonitor(o.copy(zebra = true, zebraLevel = it / 100f))
        }
        Text("Peaking", color = Color.LightGray, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.20f to "baixo", 0.12f to "médio", 0.07f to "alto").forEach { (t, n) ->
                BigButton(n, selected = o.peakingThreshold == t) {
                    vm.updateMonitor(o.copy(peaking = true, peakingThreshold = t))
                }
            }
            listOf(
                0xFFFF1744.toInt() to "vermelho", 0xFF00E676.toInt() to "verde",
                0xFF2979FF.toInt() to "azul", 0xFFFFFFFF.toInt() to "branco",
            ).forEach { (c, n) ->
                BigButton(n, selected = o.peakingColor == c) { vm.updateMonitor(o.copy(peaking = true, peakingColor = c)) }
            }
        }
        Text("App", color = Color.LightGray, fontSize = 11.sp)
        BigButton("↺ Restaurar padrão de fábrica", enabled = !vm.recording) { confirmReset = true }
    }
}

@Composable
private fun PresetsPanel(vm: CameraViewModel) {
    var asking by remember { mutableStateOf(false) }
    Row(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 520.dp)) {
            itemsIndexed(vm.presets) { _, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigButton(p.name, selected = p.settings == vm.settings) { vm.applyPreset(p) }
                    Text(
                        "×", color = Color.Gray, fontSize = 22.sp,
                        modifier = Modifier.clickable { vm.deletePreset(p) }.padding(horizontal = 6.dp),
                    )
                }
            }
        }
        BigButton("+ Salvar atual") { asking = true }
    }
    if (asking) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Nome do preset") },
            text = { TextField(name, { name = it }, singleLine = true, placeholder = { Text("ex.: Igreja interna") }) },
            confirmButton = { TextButton({ vm.savePreset(name); asking = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ asking = false }) { Text("Cancelar") } },
        )
    }
}

/** Roda horizontal de valores grandes; centraliza o valor atual ao abrir. */
@Composable
private fun <T> Wheel(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val state = rememberLazyListState()
    val idx = values.indexOf(selected)
    LaunchedEffect(values.size) { if (idx > 2) state.scrollToItem(idx - 2) }
    LazyRow(
        state = state,
        modifier = Modifier.background(PanelBg, RoundedCornerShape(8.dp)).widthIn(max = 600.dp),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(values) { _, v ->
            val sel = v == selected
            Box(
                Modifier.height(56.dp).widthIn(min = 64.dp)
                    .background(if (sel) Accent else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                    .clickable { onSelect(v) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(v), color = if (sel) Color.Black else Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun <T> ModeRow(options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    Row(
        Modifier.background(PanelBg, RoundedCornerShape(8.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (v, name) -> BigButton(name, selected = v == selected, enabled = enabled(v)) { onSelect(v) } }
    }
}

@Composable
private fun BigButton(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(52.dp).widthIn(min = 72.dp)
            .background(if (selected) Accent else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = when { !enabled -> Color.Gray; selected -> Color.Black; else -> Color.White },
        )
    }
}

@Composable
private fun BarButton(title: String, value: String, open: Boolean, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.height(60.dp).widthIn(min = 76.dp)
            .background(PanelBg, RoundedCornerShape(8.dp))
            .border(2.dp, if (open) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Color.LightGray, fontSize = 11.sp)
        Text(
            value, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            // Em AUTO o valor é só o que a câmera mede: cinza. Travado/manual: amarelo.
            color = when { !enabled -> Color.DarkGray; active -> Accent; else -> Color(0xFFBDBDBD) },
        )
    }
}

private fun ExposureMode.label() = when (this) {
    ExposureMode.AUTO -> "AUTO"
    ExposureMode.LOCK -> "TRAV."
    ExposureMode.MANUAL -> "MAN"
}

fun distanceLabel(diopters: Float?): String = when {
    diopters == null -> "-"
    diopters <= 0.02f -> "∞"
    else -> {
        val m = 1f / diopters
        if (m < 1f) "${(m * 100).toInt()}cm" else "%.1fm".format(m)
    }
}
