package br.com.plusapps.s23logvideo.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import org.json.JSONObject

/**
 * Áudio: AudioRecord 48 kHz estéreo → AAC-LC 256 kb/s, numa thread própria.
 *
 * O PTS vem de AudioRecord.getTimestamp(TIMEBASE_MONOTONIC), o mesmo relógio (System.nanoTime)
 * da câmera com TIMESTAMP_BASE_MONOTONIC: pts = t_ref + (quadro - quadro_ref) / 48000.
 */
class AudioEncoder(private val sink: MuxerSink) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITRATE = 256_000
        private const val BYTES_PER_FRAME = 2 * CHANNELS
    }

    private lateinit var record: AudioRecord
    private lateinit var codec: MediaCodec
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private var framesRead = 0L
    private var timestampFallbacks = 0
    private var error: Throwable? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE * BYTES_PER_FRAME / 2))
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord não inicializou" }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        running = true
        thread = Thread({ loop() }, "audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Para a captura, drena o encoder até o EOS e libera tudo. */
    fun stop() {
        running = false
        thread?.join(5_000)
        runCatching { record.stop() }
        runCatching { record.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sampleRate", SAMPLE_RATE).put("canais", CHANNELS).put("bitrate", BITRATE)
        .put("quadrosLidos", framesRead)
        .put("segundosLidos", framesRead.toDouble() / SAMPLE_RATE)
        .put("ptsSemTimestamp", timestampFallbacks)
        .put("erro", error?.toString() ?: JSONObject.NULL)

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        val ts = AudioTimestamp()
        var refFrame = -1L
        var refNanos = 0L
        var lastRefresh = 0L
        try {
            while (true) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    buf.clear()
                    if (!running) {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs(framesRead, refFrame, refNanos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        drain(info, untilEos = true)
                        break
                    }
                    val want = buf.capacity() - buf.capacity() % BYTES_PER_FRAME
                    val n = record.read(buf, want)
                    if (n > 0) {
                        // Atualiza a referência de tempo a cada ~1 s (corrige deriva entre os relógios).
                        val now = System.nanoTime()
                        if (refFrame < 0 || now - lastRefresh > 1_000_000_000L) {
                            if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                refFrame = ts.framePosition
                                refNanos = ts.nanoTime
                            } else if (refFrame < 0) {
                                // Sem timestamp do HAL: estima que o fim deste bloco é "agora".
                                timestampFallbacks++
                                refFrame = framesRead + n / BYTES_PER_FRAME
                                refNanos = now
                            }
                            lastRefresh = now
                        }
                        val pts = ptsUs(framesRead, refFrame, refNanos)
                        codec.queueInputBuffer(inIndex, 0, n, pts, 0)
                        framesRead += n / BYTES_PER_FRAME
                    } else {
                        error("AudioRecord.read devolveu $n")
                    }
                }
                drain(info, untilEos = false)
            }
        } catch (t: Throwable) {
            error = t
        }
    }

    /**
     * O AAC do Android põe amostras de "priming" no início e o MP4 sai sem edit list para
     * compensar, então o som toca atrasado. No 1º teste de palmas (2026-11-04) o atraso medido
     * foi de ~66 ms ±17. Adiantar o PTS pelo atraso típico do encoder AAC-LC (2048 amostras)
     * corrige a maior parte disso; a confirmação vem do tools/av_sync.py.
     */
    private val primingUs = 2048L * 1_000_000L / SAMPLE_RATE

    private fun ptsUs(frame: Long, refFrame: Long, refNanos: Long): Long =
        (refNanos + (frame - refFrame) * 1_000_000_000L / SAMPLE_RATE) / 1_000 - primingUs

    private fun drain(info: MediaCodec.BufferInfo, untilEos: Boolean) {
        var waits = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++waits > 300) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.addAudioTrack(codec.outputFormat)
                out >= 0 -> {
                    if (info.size > 0) sink.writeAudio(codec.getOutputBuffer(out)!!, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
