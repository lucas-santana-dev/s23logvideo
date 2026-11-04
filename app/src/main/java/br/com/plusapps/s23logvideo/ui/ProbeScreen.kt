package br.com.plusapps.s23logvideo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProbeScreen(
    controller: ProbeController,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LogCam · Fase 0 (Probe)", style = MaterialTheme.typography.headlineSmall)

        if (!hasPermissions) {
            Text("O app precisa de acesso à câmera e ao microfone.")
            Button(onClick = onRequestPermissions) { Text("Conceder permissões") }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = controller::runProbe, enabled = !controller.busy) { Text("Rodar probe") }
            OutlinedButton(onClick = controller::runMatrix, enabled = !controller.busy) {
                Text("Rodar matriz de testes")
            }
            if (controller.busy) CircularProgressIndicator()
        }
        Text(controller.status, style = MaterialTheme.typography.bodyMedium)

        if (controller.summary.isNotEmpty()) {
            Text("Resumo", style = MaterialTheme.typography.titleMedium)
            controller.summary.forEach { Mono(it) }
        }
        if (controller.log.isNotEmpty()) {
            Text("Matriz", style = MaterialTheme.typography.titleMedium)
            controller.log.forEach { Mono(it) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}
