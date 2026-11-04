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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
            val cfg = vm.config
            val canChange = !vm.recording && !vm.busy
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MODES.forEach { m ->
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
                if (cfg.isLog) {
                    FilterChip(
                        selected = vm.monitor709,
                        onClick = { vm.toggleMonitor709() },
                        enabled = !vm.recording,
                        label = { Text("Monitor 709") },
                    )
                }
                vm.freeMinutes?.let { Hud("livre ≈ $it min") }
            }
            if (vm.status.isNotEmpty()) Hud(vm.status)
            vm.lastResult?.let { Hud(it) }
        }

        TextButton(onClick = onOpenProbe, enabled = !vm.recording, modifier = Modifier.align(Alignment.BottomStart)) {
            Text("Probe")
        }

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
private fun Hud(text: String) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
