package br.com.plusapps.s23logvideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano (tipo câmera + microfone) que mantém a câmera, a gravação e o controle
 * remoto funcionando com a TELA BLOQUEADA. O Android só deixa um app usar câmera/microfone em segundo
 * plano assim, e só se o serviço for iniciado com o app visível — por isso o ViewModel o liga quando o
 * remoto é ligado ou uma gravação começa, ainda com a tela acesa.
 *
 * O serviço não faz nada além de existir: a câmera continua no CameraViewModel, que não a fecha no
 * onStop enquanto o serviço estiver ativo. Segura também um wake lock parcial, para a CPU não dormir
 * (servidor HTTP e encoder precisam dela com a tela apagada).
 */
class CaptureService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Câmera ativa", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aparece enquanto o LogCam mantém a câmera aberta (gravação ou controle remoto)."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Câmera ativa"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("LogCam")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (intent?.getBooleanExtra(EXTRA_MIC, true) != false) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        runCatching { startForeground(ID, n, type) }
            .onFailure { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LogCam:captura").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "captura"
        private const val ID = 42
        private const val EXTRA_TEXT = "texto"
        private const val EXTRA_MIC = "mic"

        /** Liga ou atualiza o texto da notificação. Chamar com o app visível. */
        fun start(context: Context, text: String, withMic: Boolean) {
            val i = Intent(context, CaptureService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_MIC, withMic)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
