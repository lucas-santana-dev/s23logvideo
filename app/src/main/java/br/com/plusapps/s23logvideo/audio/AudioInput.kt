package br.com.plusapps.s23logvideo.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Fonte do AudioRecord. UNPROCESSED = sem AGC/supressão de ruído do sistema (quando o aparelho tem). */
enum class AudioSourceOpt(val label: String, val source: Int) {
    UNPROCESSED("Sem processamento", MediaRecorder.AudioSource.UNPROCESSED),
    CAMCORDER("Câmera", MediaRecorder.AudioSource.CAMCORDER),
    MIC("Mic", MediaRecorder.AudioSource.MIC),
}

/**
 * Entrada de áudio (Fase 5): fonte, ganho manual (aplicado no PCM, com saturação) e o microfone
 * preferido (0 = o que o sistema escolher; um microfone USB-C conectado aparece em [inputs]).
 */
data class AudioInput(
    val source: AudioSourceOpt = AudioSourceOpt.UNPROCESSED,
    val gainDb: Int = 0,
    val deviceId: Int = 0,
) {
    val gainLinear: Float get() = 10f.pow(gainDb / 20f)

    companion object {
        const val SAMPLE_RATE = 48_000

        fun unprocessedSupported(context: Context): Boolean =
            context.getSystemService(AudioManager::class.java)
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        /** Microfones disponíveis (interno, USB, P2 com fio, Bluetooth). */
        fun inputs(context: Context): List<AudioDeviceInfo> =
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
                .toList()

        fun deviceLabel(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Interno" + (d.address.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                "USB: ${d.productName}"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth: ${d.productName}"
            else -> d.productName.toString()
        }
    }

    /**
     * AudioRecord 48 kHz estéreo 16 bits com esta configuração. Cai para CAMCORDER se o aparelho não
     * tiver UNPROCESSED. Desliga AGC e supressor de ruído quando o sistema os anexa sozinho.
     */
    @SuppressLint("MissingPermission")
    fun build(context: Context, bufferBytes: Int): AudioRecord {
        val src = if (source == AudioSourceOpt.UNPROCESSED && !unprocessedSupported(context)) {
            AudioSourceOpt.CAMCORDER.source
        } else source.source
        val rec = AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (deviceId != 0) {
            inputs(context).firstOrNull { it.id == deviceId }?.let { rec.setPreferredDevice(it) }
        }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.enabled = false }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = false }
        return rec
    }

    fun describe(context: Context, rec: AudioRecord?): String {
        val dev = rec?.routedDevice?.let { deviceLabel(it) } ?: "?"
        val src = if (source == AudioSourceOpt.UNPROCESSED && !unprocessedSupported(context)) "CAMCORDER (sem UNPROCESSED)" else source.name
        return "$src · ganho ${gainDb} dB · $dev"
    }
}

/** Aplica um ganho linear a PCM 16 bits, em lugar, com saturação. */
fun applyGain(buf: ByteBuffer, bytes: Int, gain: Float) {
    if (gain == 1f) return
    val s = buf.duplicate().order(ByteOrder.nativeOrder())
    var i = 0
    while (i + 1 < bytes) {
        val v = (s.getShort(i) * gain).roundToInt().coerceIn(-32768, 32767)
        s.putShort(i, v.toShort())
        i += 2
    }
}
