package com.cc.videoplayer.playback

/**
 * VLC opens the first MediaCodec whose advertised profiles match the file.
 * MediaCodec is used only when that decoder is hardware. Qualcomm HEVC is the
 * exception: it plays Main 10 and only advertises Main, so the file profile is
 * presented as Main and the hardware decoder is selected.
 */
internal data class ListedDecoder(
  val hardware: Boolean,
  val mime: String,
  val profiles: List<Int>,
)

internal data class VideoStream(val mime: String, val profile: Int)

internal data class DecoderPlan(
  val useMediaCodec: Boolean,
  val rewriteHevcMain10: Boolean,
)

internal object DecoderSelection {
  const val HEVC = "video/hevc"
  const val AVC = "video/avc"

  fun plan(stream: VideoStream?, decoders: List<ListedDecoder>): DecoderPlan {
    if (stream == null) return DecoderPlan(useMediaCodec = false, rewriteHevcMain10 = false)
    if (stream.mime == HEVC && stream.profile == HEVC_MAIN_10 && !selectsHardware(decoders, stream)) {
      if (selectsHardware(decoders, stream.copy(profile = HEVC_MAIN))) {
        return DecoderPlan(useMediaCodec = true, rewriteHevcMain10 = true)
      }
    }
    return DecoderPlan(selectsHardware(decoders, stream), rewriteHevcMain10 = false)
  }

  fun hevcMain10Quirk(decoders: List<ListedDecoder>): Boolean {
    return !selectsHardware(decoders, VideoStream(HEVC, HEVC_MAIN_10)) &&
      selectsHardware(decoders, VideoStream(HEVC, HEVC_MAIN))
  }

  private fun selectsHardware(decoders: List<ListedDecoder>, stream: VideoStream): Boolean {
    val pick = decoders.firstOrNull { it.mime == stream.mime && vlcAccepts(it, stream) } ?: return false
    return pick.hardware
  }

  private fun vlcAccepts(decoder: ListedDecoder, stream: VideoStream): Boolean {
    if (stream.profile <= 0) return true
    return decoder.profiles.any { mapOmxProfile(stream.mime, it) == stream.profile }
  }

  /**
   * Same profile map as VLC 3 MediaCodec_GetName. Other codecs are not compared,
   * so a known profile above 0 never selects them.
   */
  internal fun mapOmxProfile(mime: String, omxProfile: Int): Int {
    return when (mime) {
      HEVC -> when (omxProfile) {
        0x1 -> HEVC_MAIN
        0x2, 0x1000 -> HEVC_MAIN_10
        else -> 0
      }
      AVC -> when (omxProfile) {
        0x01 -> 66
        0x02 -> 77
        0x04 -> 88
        0x08 -> 100
        0x10 -> 110
        0x20 -> 122
        0x40 -> 144
        else -> 0
      }
      else -> 0
    }
  }

  private const val HEVC_MAIN = 1
  private const val HEVC_MAIN_10 = 2
}
