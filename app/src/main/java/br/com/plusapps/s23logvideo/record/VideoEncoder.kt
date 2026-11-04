package br.com.plusapps.s23logvideo.record

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred

/**
 * Encoder HEVC alimentado pela surface persistente da sessão de câmera.
 * Fica configurado (mas parado) entre gravações, para a surface sempre ter um consumidor
 * com o tamanho certo; start() começa a gravação e as saídas vão para o [sink].
 */
class VideoEncoder(val config: VideoConfig, persistentSurface: Surface, handler: Handler) {
    val codecName: String
    private val codec: MediaCodec
    val eos = CompletableDeferred<Unit>()

    @Volatile
    var sink: MuxerSink? = null

    @Volatile
    var outputFormat: String? = null
        private set

    /** Bitrate efetivo, já limitado ao máximo que o encoder anuncia. */
    val bitrate: Int

    init {
        val format = config.mediaFormat()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        codecName = list.findEncoderForFormat(format) ?: error("nenhum encoder aceita $format")
        val max = list.codecInfos.firstOrNull { it.name == codecName }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)?.videoCapabilities?.bitrateRange?.upper
        bitrate = if (max != null && config.bitrate > max) max else config.bitrate
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        codec = MediaCodec.createByCodecName(codecName)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0) sink?.writeVideo(c.getOutputBuffer(index)!!, info)
                } finally {
                    c.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.complete(Unit)
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                eos.completeExceptionally(e)
            }

            override fun onOutputFormatChanged(c: MediaCodec, fmt: MediaFormat) {
                outputFormat = fmt.toString()
                sink?.addVideoTrack(fmt)
            }
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.setInputSurface(persistentSurface)
    }

    fun start() = codec.start()

    fun signalEndOfStream() = codec.signalEndOfInputStream()

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
