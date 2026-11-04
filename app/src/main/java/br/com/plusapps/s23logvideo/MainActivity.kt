package br.com.plusapps.s23logvideo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.com.plusapps.s23logvideo.ui.ProbeController
import br.com.plusapps.s23logvideo.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private lateinit var controller: ProbeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gravações de teste não podem ser interrompidas pelo apagar da tela.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = ProbeController(applicationContext, lifecycleScope) { (display?.rotation ?: 0) * 90 }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    var granted by remember { mutableStateOf(hasPermissions()) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { granted = hasPermissions() }
                    ProbeScreen(controller, granted) { launcher.launch(permissions) }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
