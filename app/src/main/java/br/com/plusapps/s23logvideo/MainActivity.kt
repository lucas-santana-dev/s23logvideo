package br.com.plusapps.s23logvideo

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.com.plusapps.s23logvideo.ui.CameraViewModel
import br.com.plusapps.s23logvideo.ui.ProbeController
import br.com.plusapps.s23logvideo.ui.ProbeScreen
import br.com.plusapps.s23logvideo.ui.RecordScreen

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val cameraVm: CameraViewModel by viewModels()
    private lateinit var probe: ProbeController

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = pushRotation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        probe = ProbeController(applicationContext, lifecycleScope) { currentRotation() }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        pushRotation()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    var cameraOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        cameraOk = granted(Manifest.permission.CAMERA)
                        cameraVm.withAudio = granted(Manifest.permission.RECORD_AUDIO)
                    }
                    LaunchedEffect(Unit) {
                        cameraVm.withAudio = granted(Manifest.permission.RECORD_AUDIO)
                        if (!permissions.all { granted(it) }) launcher.launch(permissions)
                    }
                    var showProbe by rememberSaveable { mutableStateOf(false) }
                    // Fase 5: desempenho sustentado durante o take (clock estável em vez de picos que esquentam).
                    val recording = cameraVm.recording
                    LaunchedEffect(recording) {
                        val power = getSystemService(android.os.PowerManager::class.java)
                        if (power.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(recording)
                    }

                    when {
                        !cameraOk -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("O LogCam precisa da câmera (e do microfone para gravar áudio).")
                            Button(onClick = { launcher.launch(permissions) }) { Text("Conceder permissões") }
                        }
                        showProbe -> {
                            BackHandler { if (!probe.busy) showProbe = false }
                            ProbeScreen(probe) { showProbe = false }
                        }
                        else -> RecordScreen(cameraVm) { showProbe = true }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        probe.close()
        super.onDestroy()
    }

    private fun currentRotation() = (display?.rotation ?: 1) * 90

    private fun pushRotation() = cameraVm.onDisplayRotation(currentRotation())

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
