package br.com.plusapps.s23logvideo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/** Pico de um bloco PCM 16 bits (0..1). O buffer não tem a posição alterada. */
fun pcm16Peak(buf: ByteBuffer, bytes: Int): Float {
    val s = buf.duplicate().order(ByteOrder.nativeOrder())
    s.position(0)
    var peak = 0
    var i = 0
    while (i + 1 < bytes) {
        val v = abs(s.getShort(i).toInt())
        if (v > peak) peak = v
        i += 2
    }
    return peak / 32768f
}

fun toDbfs(peak: Float): Float = if (peak <= 1e-5f) -100f else 20f * log10(peak)

/**
 * Medidor de nível fora da gravação: lê o microfone só para mostrar o VU. Durante a gravação quem mede
 * é o próprio AudioEncoder, então este medidor é parado antes do take (dois AudioRecord no mesmo
 * microfone não são garantidos).
 */
class AudioMeter(private val build: () -> AudioRecord) {
    @Volatile var peak = 0f
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rec = runCatching { build() }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                running = false
                return@Thread
            }
            val buf = ByteBuffer.allocateDirect(4096)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, buf.capacity())
                    if (n > 0) peak = pcm16Peak(buf, n) else if (n < 0) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                peak = 0f
            }
        }, "audio-meter").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun defaultRecord(): AudioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, 48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2,
        )
    }
}
