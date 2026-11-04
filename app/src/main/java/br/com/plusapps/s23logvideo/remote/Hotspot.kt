package br.com.plusapps.s23logvideo.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Rede Wi-Fi criada pelo próprio aparelho (LocalOnlyHotspot): o PC ou o outro celular entram nela e
 * abrem a página de controle, sem roteador. Sem internet; só a ligação entre os dois aparelhos.
 * Exige a permissão NEARBY_WIFI_DEVICES (Android 13+), pedida pela Activity antes de [start].
 */
class Hotspot(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class Info(val ssid: String, val password: String)

    val active get() = reservation != null

    fun start(onResult: (Result<Info>) -> Unit) {
        if (reservation != null) return
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val cfg = r.softApConfiguration
                    onResult(Result.success(Info(cfg.ssid ?: "?", cfg.passphrase ?: "")))
                }

                override fun onStopped() {
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    val why = when (reason) {
                        ERROR_NO_CHANNEL -> "sem canal livre"
                        ERROR_TETHERING_DISALLOWED -> "o sistema não permite (roteador do celular ligado?)"
                        ERROR_INCOMPATIBLE_MODE -> "modo incompatível (desligue o roteador Wi-Fi do celular)"
                        else -> "erro $reason"
                    }
                    onResult(Result.failure(IllegalStateException("Hotspot falhou: $why")))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            onResult(Result.failure(t))
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }
}
