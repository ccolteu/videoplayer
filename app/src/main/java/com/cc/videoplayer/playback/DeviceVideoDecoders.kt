package com.cc.videoplayer.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

internal object DeviceVideoDecoders {
  @Volatile private var cached: List<ListedDecoder>? = null

  fun listed(): List<ListedDecoder> {
    cached?.let { return it }
    val loaded = runCatching { load() }.getOrDefault(emptyList())
    cached = loaded
    return loaded
  }

  private fun load(): List<ListedDecoder> {
    val found = ArrayList<ListedDecoder>()
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    for (info in list.codecInfos) {
      if (info.isEncoder) continue
      val types = runCatching { info.supportedTypes }.getOrNull() ?: continue
      val hardware = hardware(info)
      for (type in types) {
        if (!type.startsWith("video/")) continue
        val profiles =
          runCatching { info.getCapabilitiesForType(type).profileLevels }
            .getOrNull()
            ?.map { it.profile }
            ?: emptyList()
        found.add(ListedDecoder(hardware = hardware, mime = type, profiles = profiles))
      }
    }
    return found
  }

  private fun hardware(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
    val name = info.name.lowercase()
    return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
  }
}
