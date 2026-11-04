package br.com.plusapps.s23logvideo.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Versões suspend de abrir a câmera e criar a sessão. */
@SuppressLint("MissingPermission")
suspend fun CameraManager.openAsync(id: String, executor: Executor): CameraDevice =
    suspendCancellableCoroutine { cont ->
        openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (cont.isActive) cont.resume(d) else d.close()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("câmera desconectada"))
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("erro ao abrir câmera: $error"))
            }
        })
    }

suspend fun CameraDevice.createSessionAsync(
    outputs: List<OutputConfiguration>,
    executor: Executor,
    sessionParameters: CaptureRequest? = null,
): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    val config = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR, outputs, executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cont.isActive) cont.resume(s) else s.close()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("onConfigureFailed"))
            }
        }
    )
    // Em vários aparelhos o modo de estabilização é "session key": tem que ir já na criação da sessão.
    sessionParameters?.let { config.sessionParameters = it }
    createCaptureSession(config)
}
