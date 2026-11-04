package br.com.plusapps.s23logvideo.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject

/** Fase 0: o que cada encoder HEVC do aparelho aceita. */
object CodecProbe {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC

    /** Resoluções e fps que importam para o app. */
    val TARGETS = listOf(
        Triple(3840, 2160, 30), Triple(3840, 2160, 60),
        Triple(7680, 4320, 24), Triple(7680, 4320, 30),
        Triple(1920, 1080, 120), Triple(1920, 1080, 240),
    )

    fun hevcEncoders(): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }

    fun run(): JSONArray = JSONArray().apply { hevcEncoders().forEach { put(describe(it)) } }

    fun supportsMain10(info: MediaCodecInfo): Boolean =
        info.getCapabilitiesForType(MIME).profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }

    private fun levelName(level: Int): String {
        val cls = CodecProfileLevel::class.java
        Names.table(cls, "HEVCMainTierLevel")[level.toLong()]?.let { return "Main L$it" }
        Names.table(cls, "HEVCHighTierLevel")[level.toLong()]?.let { return "High L$it" }
        return "UNKNOWN_$level"
    }

    private fun describe(info: MediaCodecInfo): JSONObject {
        val o = JSONObject()
        val caps = info.getCapabilitiesForType(MIME)
        o.put("name", info.name)
        o.putSafe("canonicalName") { info.canonicalName }
        o.putSafe("hardware") { info.isHardwareAccelerated }
        o.putSafe("softwareOnly") { info.isSoftwareOnly }
        o.putSafe("vendor") { info.isVendor }
        o.putSafe("main10") { supportsMain10(info) }
        o.putSafe("profileLevels") {
            JSONArray().apply {
                caps.profileLevels.forEach {
                    put(
                        Names.of(CodecProfileLevel::class.java, "HEVCProfile", it.profile) + " @ " +
                            levelName(it.level)
                    )
                }
            }
        }
        o.putSafe("colorFormats") {
            JSONArray(caps.colorFormats.map { Names.of(CodecCapabilities::class.java, "COLOR_Format", it) })
        }
        o.putSafe("maxInstances") { caps.maxSupportedInstances }
        o.putSafe("hdrEditing") { caps.isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing) }

        val video = caps.videoCapabilities ?: return o.put("erro", "sem videoCapabilities")
        o.putSafe("bitrateRangeBps") { video.bitrateRange.json() }
        o.putSafe("widths") { video.supportedWidths.json() }
        o.putSafe("heights") { video.supportedHeights.json() }
        o.putSafe("alignment") { "${video.widthAlignment}x${video.heightAlignment}" }
        o.putSafe("targets") {
            JSONObject().apply {
                TARGETS.forEach { (w, h, fps) ->
                    val t = JSONObject()
                    t.putSafe("sizeAndRateSupported") { video.areSizeAndRateSupported(w, h, fps.toDouble()) }
                    t.putSafe("frameRates") {
                        if (video.isSizeSupported(w, h)) video.getSupportedFrameRatesFor(w, h).json() else null
                    }
                    t.putSafe("achievableFrameRates") {
                        if (video.isSizeSupported(w, h)) video.getAchievableFrameRatesFor(w, h)?.json() else null
                    }
                    put("${w}x${h}@$fps", t)
                }
            }
        }

        val enc = caps.encoderCapabilities ?: return o
        o.putSafe("bitrateModes") {
            JSONArray().apply {
                listOf(
                    EncoderCapabilities.BITRATE_MODE_CQ to "CQ",
                    EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
                    EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
                    EncoderCapabilities.BITRATE_MODE_CBR_FD to "CBR_FD",
                ).forEach { (mode, name) -> if (enc.isBitrateModeSupported(mode)) put(name) }
            }
        }
        o.putSafe("qualityRange") { enc.qualityRange.json() }
        o.putSafe("complexityRange") { enc.complexityRange.json() }
        return o
    }
}
