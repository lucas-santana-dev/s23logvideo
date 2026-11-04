package br.com.plusapps.s23logvideo.ui

import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import br.com.plusapps.s23logvideo.camera.Lens

/** Resolução e fps; o Log/Normal é escolhido à parte (chip "LOG 10-bit"). */
private data class Mode(val w: Int, val h: Int, val fps: Int)

private val MODES = listOf(Mode(3840, 2160, 30), Mode(3840, 2160, 24), Mode(1920, 1080, 30), Mode(1920, 1080, 24))

/**
 * Tela de gravação. Gira com o aparelho: em paisagem, o botão de gravar fica na lateral direita; em
 * retrato, embaixo, e a barra de controles rola na horizontal. O botão ⊘ esconde todos os widgets.
 */
@Composable
fun RecordScreen(vm: CameraViewModel, onOpenProbe: () -> Unit) {
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }

    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        AndroidView(
            modifier = Modifier.fillMaxSize().pointerInput(vm) {
                detectTapGestures { o ->
                    // Com uma roda aberta, o 1º toque na imagem só fecha a roda.
                    if (panel != Panel.NONE) panel = Panel.NONE else vm.onPreviewTap(o.x, o.y)
                }
            },
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = Unit
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) =
                            vm.renderer.setOutput(h.surface, w, hgt)

                        override fun surfaceDestroyed(h: SurfaceHolder) = vm.renderer.setOutput(null, 0, 0)
                    })
                }
            },
        )

        // Quadradinho do toque para focar.
        vm.tapPoint?.let { (x, y) ->
            val half = with(LocalDensity.current) { 36.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((x - half).toInt(), (y - half).toInt()) }
                    .size(72.dp).border(2.dp, Color(0xFFFFC107), RoundedCornerShape(4.dp)),
            )
        }

        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).windowInsetsPadding(WindowInsets.displayCutout)
        val recordSide = 128.dp // largura reservada à coluna de gravar (paisagem)
        val recordBottom = 150.dp // altura reservada à linha de gravar (retrato)

        if (!vm.cleanScreen) {
            // Topo: chips e o status compacto.
            Column(
                safe.align(Alignment.TopStart).padding(12.dp).padding(end = if (portrait) 0.dp else recordSide),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TopChips(vm, panel, onPanel = { panel = if (panel == it) Panel.NONE else it }, onOpenProbe)
                StatusRow(vm)
                if (vm.monitor.falseColor) {
                    Hud("verde 18% · rosa +1 stop · amarelo alto · vermelho estouro · azul/roxo sombra")
                }
                if (vm.status.isNotEmpty()) Hud(vm.status)
                vm.lastResult?.let { Hud(it) }
                if (portrait) vm.scopes?.let { ScopesView(it, vm.config.isLog) }
            }
            if (!portrait) {
                vm.scopes?.let {
                    ScopesView(it, vm.config.isLog, safe.align(Alignment.TopEnd).padding(top = 120.dp, end = recordSide + 12.dp))
                }
            }

            ManualControls(
                vm, panel, onPanel = { panel = if (panel == it) Panel.NONE else it },
                modifier = if (portrait) {
                    safe.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = recordBottom)
                } else {
                    safe.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = recordSide + 12.dp)
                },
                scrollBar = portrait,
            )
        }

        // Gravar: lateral direita em paisagem, embaixo em retrato.
        if (portrait) {
            Row(
                safe.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Timer(vm)
                    if (vm.recording && !vm.cleanScreen) Hud("perdidos ${vm.droppedFrames}")
                }
                RecordButton(vm)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.withAudio && !vm.cleanScreen) AudioVu(vm.audioDb, vm.audioClipped)
                    EyeButton(vm)
                }
            }
        } else {
            Column(
                safe.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Timer(vm)
                    if (vm.recording && !vm.cleanScreen) Hud("perdidos ${vm.droppedFrames}")
                }
                if (vm.withAudio && !vm.cleanScreen) AudioVu(vm.audioDb, vm.audioClipped)
                RecordButton(vm)
                EyeButton(vm)
            }
        }

        // Mesmo com a tela limpa, os avisos que exigem ação continuam aparecendo.
        if (vm.cleanScreen && vm.status.isNotEmpty()) {
            Hud(vm.status, safe.align(Alignment.TopCenter).padding(top = 12.dp))
        }
    }
}

@Composable
private fun TopChips(vm: CameraViewModel, panel: Panel, onPanel: (Panel) -> Unit, onOpenProbe: () -> Unit) {
    val cfg = vm.config
    val canChange = !vm.recording && !vm.busy
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lens.entries.forEach { l ->
            FilterChip(vm.lens == l, { vm.selectLens(l) }, enabled = canChange, label = { Text(l.label) })
        }
        MODES.filter { it.w <= vm.lens.maxWidth }.forEach { m ->
            val modeCfg = cfg.withMode(m.w, m.h, m.fps)
            FilterChip(
                selected = cfg.width == m.w && cfg.height == m.h && cfg.fps == m.fps,
                onClick = { vm.selectConfig(modeCfg) },
                enabled = canChange,
                label = { Text(modeCfg.label) },
            )
        }
        FilterChip(cfg.isLog, { vm.selectConfig(cfg.withLog(!cfg.isLog)) }, enabled = canChange, label = { Text("LOG 10-bit") })
        val mo = vm.monitor
        if (cfg.isLog) FilterChip(mo.lut, { vm.updateMonitor(mo.copy(lut = !mo.lut)) }, label = { Text("LUT") })
        FilterChip(mo.falseColor, { vm.updateMonitor(mo.copy(falseColor = !mo.falseColor)) }, label = { Text("False color") })
        FilterChip(mo.zebra, { vm.updateMonitor(mo.copy(zebra = !mo.zebra)) }, label = { Text("Zebra") })
        FilterChip(mo.peaking, { vm.updateMonitor(mo.copy(peaking = !mo.peaking)) }, label = { Text("Peaking") })
        FilterChip(mo.scopes, { vm.updateMonitor(mo.copy(scopes = !mo.scopes)) }, label = { Text("Scopes") })
        FilterChip(panel == Panel.MONITOR, { onPanel(Panel.MONITOR) }, label = { Text("⚙") })
        FilterChip(vm.withGyro, { vm.toggleGyro() }, enabled = !vm.recording, label = { Text("Giro") })
        TextButton(onClick = onOpenProbe, enabled = !vm.recording) { Text("Probe") }
    }
}

/** Status compacto: minutos livres, térmico (só quando importa) e o controle remoto ativo. */
@Composable
private fun StatusRow(vm: CameraViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        vm.freeMinutes?.let { Hud("$it min", color = if (it < 5) Color(0xFFFF5252) else Color.White) }
        val st = vm.thermalStatus
        val head = vm.thermalHeadroom
        if (st >= PowerManager.THERMAL_STATUS_LIGHT || (head != null && head >= 0.7f)) {
            val color = when {
                st >= PowerManager.THERMAL_STATUS_SEVERE || (head != null && head >= 0.95f) -> Color(0xFFFF5252)
                st >= PowerManager.THERMAL_STATUS_MODERATE || (head != null && head >= 0.85f) -> Color(0xFFFFC107)
                else -> Color.White
            }
            Hud("🌡 " + (head?.let { "%.0f%%".format(it * 100) } ?: (thermalLabel(st) ?: "")), color = color)
        }
        vm.remoteUrl?.let { Hud("📡 $it", color = Color(0xFF80D8FF)) }
    }
}

@Composable
private fun Timer(vm: CameraViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vm.recording) Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF1744)))
        Text(
            formatElapsed(vm.elapsedMs),
            color = if (vm.recording) Color(0xFFFF5252) else Color.White,
            fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun RecordButton(vm: CameraViewModel) {
    Box(
        Modifier.size(76.dp).border(4.dp, Color.White, CircleShape)
            .padding(8.dp).clip(if (vm.recording) RoundedCornerShape(8.dp) else CircleShape)
            .background(Color(0xFFE53935))
            .clickable(enabled = !vm.busy) { vm.toggleRecording() },
    )
}

/** Esconde/mostra os widgets. ⊘ = esconder; 👁 = mostrar de novo. */
@Composable
private fun EyeButton(vm: CameraViewModel) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0x88000000)).clickable { vm.toggleCleanScreen() },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (vm.cleanScreen) "👁" else "⊘", fontSize = 20.sp, color = Color.White)
    }
}

@Composable
internal fun Hud(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Text(
        text, color = color, fontSize = 12.sp,
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
