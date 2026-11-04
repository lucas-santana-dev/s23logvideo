package br.com.plusapps.s23logvideo.record

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.json.JSONObject

/**
 * Parâmetros de vídeo de uma gravação.
 *  - `tenBit`: sessão HLG10 (P010, BT.2020) e HEVC Main10;
 *  - `curve`: curva Log da `LogCurve` aplicada pelo ISP (TONEMAP_MODE_CONTRAST_CURVE).
 * Log = os dois ligados (Fase 2). Normal = 8 bits com a curva da Samsung.
 */
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean = false,
    val curve: Boolean = false,
    val bitrate: Int = defaultBitrate(width, fps, tenBit),
) {
    val isLog get() = tenBit && curve
    val label get() = "${if (width >= 3840) "4K" else "${height}p"}$fps"

    /** Mesma resolução e fps, alternando entre Log 10 bits e Normal 8 bits. */
    fun withLog(log: Boolean) = VideoConfig(width, height, fps, tenBit = log, curve = log)

    fun withMode(w: Int, h: Int, f: Int) = VideoConfig(w, h, f, tenBit, curve)

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
            if (tenBit && curve) {
                // Log: gamut BT.2020 (o do perfil HLG10), mas SEM transferência HDR na marcação.
                // Marcado como HLG, o editor aplicaria tone mapping de HDR em cima do Log.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            } else if (tenBit) {
                // 10 bits sem curva: é HLG de verdade, então a marcação HLG está certa.
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
        /**
         * Fase 5: em 4K 10 bits, o máximo do encoder HEVC do S23 (160 Mb/s, medido na Fase 0). O
         * VideoEncoder limita ao teto que o codec anunciar, caso outro aparelho tenha um teto menor.
         */
        fun defaultBitrate(width: Int, fps: Int, tenBit: Boolean = false): Int = when {
            width >= 7680 || fps >= 60 -> 160_000_000
            width >= 3840 -> if (tenBit) 160_000_000 else 100_000_000
            else -> if (tenBit) 60_000_000 else 40_000_000
        }
    }
}
