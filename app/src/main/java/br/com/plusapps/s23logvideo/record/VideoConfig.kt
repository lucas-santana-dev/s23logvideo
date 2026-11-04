package br.com.plusapps.s23logvideo.record

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.json.JSONObject

/**
 * Parâmetros de vídeo de uma gravação. `tenBit` e `curve` já existem para a Fase 2;
 * na Fase 1 a gravação usa 8 bits com a curva padrão.
 */
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean = false,
    val curve: Boolean = false,
    val bitrate: Int = defaultBitrate(width, fps),
) {
    val label get() = "${if (width >= 3840) "4K" else "${height}p"}$fps"

    /** HEVC alimentado por surface, Main ou Main10. */
    fun mediaFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (tenBit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            )
            if (tenBit) {
                // Fase 0/1: marca como HLG/BT.2020. A Fase 2 troca para um arquivo sem transferência HDR.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("fps", fps)
        .put("tenBit", tenBit).put("curve", curve).put("bitrate", bitrate)

    companion object {
        fun defaultBitrate(width: Int, fps: Int): Int = when {
            width >= 7680 || fps >= 60 -> 150_000_000
            width >= 3840 -> 100_000_000
            else -> 40_000_000
        }
    }
}
