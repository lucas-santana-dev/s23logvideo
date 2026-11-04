package br.com.plusapps.s23logvideo.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect

/** Resolução e fps; o Log/Normal é escolhido à parte (chip "LOG 10-bit"). */
private data class Mode(val w: Int, val h: Int, val fps: Int)

private val MODES = listOf(Mode(3840, 2160, 30), Mode(3840, 2160, 24), Mode(1920, 1080, 30), Mode(1920, 1080, 24))

@Composable
fun RecordScreen(vm: CameraViewModel, onOpenProbe: () -> Unit) {
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }

    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    val cfg = vm.config

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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

        // Topo: modo, espaço livre, status
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val canChange = !vm.recording && !vm.busy
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                br.com.plusapps.s23logvideo.camera.Lens.entries.forEach { l ->
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
                FilterChip(
                    selected = cfg.isLog,
                    onClick = { vm.selectConfig(cfg.withLog(!cfg.isLog)) },
                    enabled = canChange,
                    label = { Text("LOG 10-bit") },
                )
                val mo = vm.monitor
                if (cfg.isLog) {
                    FilterChip(mo.lut, { vm.updateMonitor(mo.copy(lut = !mo.lut)) }, label = { Text("LUT") })
                }
                FilterChip(mo.falseColor, { vm.updateMonitor(mo.copy(falseColor = !mo.falseColor)) }, label = { Text("False color") })
                FilterChip(mo.zebra, { vm.updateMonitor(mo.copy(zebra = !mo.zebra)) }, label = { Text("Zebra") })
                FilterChip(mo.peaking, { vm.updateMonitor(mo.copy(peaking = !mo.peaking)) }, label = { Text("Peaking") })
                FilterChip(mo.scopes, { vm.updateMonitor(mo.copy(scopes = !mo.scopes)) }, label = { Text("Scopes") })
                FilterChip(
                    panel == Panel.MONITOR,
                    { panel = if (panel == Panel.MONITOR) Panel.NONE else Panel.MONITOR },
                    label = { Text("⚙") },
                )
                FilterChip(vm.withGyro, { vm.toggleGyro() }, enabled = !vm.recording, label = { Text("Giro") })
                TextButton(onClick = onOpenProbe, enabled = !vm.recording) { Text("Probe") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.freeMinutes?.let { Hud("livre ≈ $it min") }
                thermalLabel(vm.thermalStatus)?.let { Hud(it) }
                vm.thermalHeadroom?.let { if (it >= 0.7f) Hud("aquecendo: %.0f%%".format(it * 100)) }
                if (vm.monitor.falseColor) {
                    Hud("verde = cinza 18% · rosa = pele · amarelo = alto · vermelho = estouro · azul/roxo = sombra")
                }
            }
            if (vm.status.isNotEmpty()) Hud(vm.status)
            vm.lastResult?.let { Hud(it) }
        }

        // Quadradinho do toque para focar.
        vm.tapPoint?.let { (x, y) ->
            val half = with(LocalDensity.current) { 36.dp.toPx() }
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset((x - half).toInt(), (y - half).toInt()) }
                    .size(72.dp).border(2.dp, Color(0xFFFFC107), RoundedCornerShape(4.dp)),
            )
        }

        ManualControls(
            vm, panel, onPanel = { panel = if (panel == it) Panel.NONE else it },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = 140.dp),
        )

        vm.scopes?.let { ScopesView(it, cfg.isLog, Modifier.align(Alignment.TopEnd).padding(top = 110.dp, end = 140.dp)) }

        // Lateral direita: cronômetro e botão de gravar
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                formatElapsed(vm.elapsedMs),
                color = if (vm.recording) Color(0xFFFF5252) else Color.White,
                fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            if (vm.recording) Hud("perdidos ${vm.droppedFrames}")
            if (vm.withAudio) AudioVu(vm.audioDb, vm.audioClipped, Modifier.padding(top = 8.dp))
            Box(
                Modifier.padding(top = 12.dp).size(76.dp).border(4.dp, Color.White, CircleShape)
                    .padding(8.dp).clip(if (vm.recording) RoundedCornerShape(8.dp) else CircleShape)
                    .background(Color(0xFFE53935))
                    .clickable(enabled = !vm.busy) { vm.toggleRecording() },
            )
        }
    }
}

@Composable
internal fun Hud(text: String) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
