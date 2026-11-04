package br.com.plusapps.s23logvideo.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * MediaMuxer com trilha de vídeo e (opcionalmente) áudio, seguro entre threads.
 *
 * - Só inicia quando todas as trilhas esperadas existem; até lá guarda cópias das amostras.
 * - O arquivo começa no 1º quadro de vídeo: áudio com PTS anterior é descartado.
 */
class MuxerSink(fd: FileDescriptor, orientationHint: Int, private val expectAudio: Boolean) {
    private class Pending(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private val pendingVideo = ArrayDeque<Pending>()
    private val pendingAudio = ArrayDeque<Pending>()

    var firstVideoPtsUs = -1L; private set
    var lastVideoPtsUs = -1L; private set
    var firstAudioPtsUs = -1L; private set
    var lastAudioPtsUs = -1L; private set
    var videoSamples = 0; private set
    var audioSamples = 0; private set
    var videoBytes = 0L; private set
    var droppedAudioBeforeVideo = 0; private set
    var droppedAudioAfterVideo = 0; private set
    private var audioEndUs = -1L

    /** Depois do último quadro de vídeo: o áudio além do fim do vídeo é descartado. */
    fun endAudioAfterVideo(frameDurationUs: Long) = synchronized(lock) {
        if (lastVideoPtsUs >= 0) audioEndUs = lastVideoPtsUs + frameDurationUs
    }

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (stopped || !isData(info)) return
        if (firstVideoPtsUs < 0) firstVideoPtsUs = info.presentationTimeUs
        if (!started) {
            pendingVideo.addLast(copy(buf, info))
            maybeStart()
            return
        }
        writeVideoLocked(buf, info.offset, info.size, info.presentationTimeUs, info.flags)
    }

    fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (stopped || !isData(info)) return
        if (firstVideoPtsUs >= 0 && info.presentationTimeUs < firstVideoPtsUs) {
            droppedAudioBeforeVideo++
            return
        }
        if (audioEndUs >= 0 && info.presentationTimeUs >= audioEndUs) {
            droppedAudioAfterVideo++
            return
        }
        if (!started) {
            pendingAudio.addLast(copy(buf, info))
            return
        }
        writeAudioLocked(buf, info.offset, info.size, info.presentationTimeUs, info.flags)
    }

    /** Encerra o arquivo; devolve true se ficou um MP4 válido. */
    fun stop(): Boolean = synchronized(lock) {
        if (stopped) return false
        stopped = true
        val ok = started && videoSamples > 0 && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        ok
    }

    private fun maybeStart() {
        // Espera também o 1º quadro de vídeo: sem o PTS dele, não dá para cortar o áudio anterior.
        if (started || videoTrack < 0 || pendingVideo.isEmpty() || (expectAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        while (pendingVideo.isNotEmpty()) {
            val p = pendingVideo.removeFirst()
            writeVideoLocked(ByteBuffer.wrap(p.data), 0, p.data.size, p.ptsUs, p.flags)
        }
        while (pendingAudio.isNotEmpty()) {
            val p = pendingAudio.removeFirst()
            if (firstVideoPtsUs >= 0 && p.ptsUs < firstVideoPtsUs) {
                droppedAudioBeforeVideo++
                continue
            }
            writeAudioLocked(ByteBuffer.wrap(p.data), 0, p.data.size, p.ptsUs, p.flags)
        }
    }

    private fun writeVideoLocked(buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        muxer.writeSampleData(videoTrack, buf, info(offset, size, ptsUs, flags))
        videoSamples++
        videoBytes += size
        lastVideoPtsUs = ptsUs
    }

    private fun writeAudioLocked(buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        muxer.writeSampleData(audioTrack, buf, info(offset, size, ptsUs, flags))
        audioSamples++
        if (firstAudioPtsUs < 0) firstAudioPtsUs = ptsUs
        lastAudioPtsUs = ptsUs
    }

    private fun info(offset: Int, size: Int, ptsUs: Long, flags: Int) = MediaCodec.BufferInfo().apply {
        set(offset, size, ptsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
    }

    private fun isData(info: MediaCodec.BufferInfo) =
        info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0

    private fun copy(buf: ByteBuffer, info: MediaCodec.BufferInfo): Pending {
        val data = ByteArray(info.size)
        buf.position(info.offset)
        buf.get(data, 0, info.size)
        return Pending(data, info.presentationTimeUs, info.flags)
    }
}
