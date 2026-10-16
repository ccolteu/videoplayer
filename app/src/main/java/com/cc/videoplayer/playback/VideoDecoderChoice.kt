package com.cc.videoplayer.playback

import java.io.File
import java.io.RandomAccessFile

internal object VideoDecoderChoice {
  fun plan(path: String): DecoderPlan {
    val stream = runCatching { describe(File(path)) }.getOrNull()
    return DecoderSelection.plan(stream, DeviceVideoDecoders.listed())
  }

  internal fun describe(file: File): VideoStream? {
    if (!file.isFile) return null
    RandomAccessFile(file, "r").use { raf ->
      if (raf.length() < 12) return null
      val head = ByteArray(12)
      raf.readFully(head)
      val kind = head.copyOfRange(8, 12).decodeToString()
      val box = head.copyOfRange(4, 8).decodeToString()
      return when {
        head.copyOfRange(0, 4).decodeToString() == "RIFF" && kind == "AVI " ->
          aviVideo(raf)?.let { fourcc -> streamForFourcc(fourcc, 0) }
        box == "ftyp" -> mp4Video(raf, raf.length())
        head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> matroska(raf)
        else -> null
      }
    }
  }

  private fun aviVideo(raf: RandomAccessFile): String? = walkRiff(raf, 12, raf.length(), 0)

  private fun walkRiff(raf: RandomAccessFile, start: Long, end: Long, depth: Int): String? {
    if (depth > 8) return null
    var pos = start
    while (pos + 8 <= end) {
      raf.seek(pos)
      val id = readFour(raf) ?: return null
      val size = readU32Le(raf)
      if (size < 0) return null
      val data = pos + 8
      if (data > end) return null
      if (id == "RIFF" || id == "LIST") {
        val type = readFour(raf) ?: return null
        if (type == "hdrl" || type == "strl" || id == "RIFF") {
          val found = walkRiff(raf, data + 4, minOf(end, data + size), depth + 1)
          if (found != null) return found
        }
      } else if (id == "strh" && size >= 8) {
        val fccType = readFour(raf)
        val handler = readFour(raf)
        if (fccType == "vids" && handler != null) return handler
      }
      val padded = size + (size and 1L)
      if (padded < 0 || data > end - padded) return null
      pos = data + padded
    }
    return null
  }

  private fun mp4Video(raf: RandomAccessFile, end: Long): VideoStream? = walkMp4(raf, 0, end, null, 0)

  private fun walkMp4(
    raf: RandomAccessFile,
    start: Long,
    end: Long,
    handler: String?,
    depth: Int,
  ): VideoStream? {
    if (depth > 12 || end - start < 8) return null
    var pos = start
    var trackHandler = handler
    while (pos + 8 <= end) {
      raf.seek(pos)
      val size32 = readU32Be(raf)
      val type = readFour(raf) ?: return null
      if (size32 < 0) return null
      var header = 8L
      var size = size32
      if (size32 == 1L) {
        if (pos + 16 > end) return null
        size = readU64Be(raf)
        header = 16
      } else if (size32 == 0L) {
        size = end - pos
      }
      if (size < header || pos > end - size) return null
      val child = pos + header
      when (type) {
        "moov", "mdia", "minf", "stbl" -> {
          val found = walkMp4(raf, child, pos + size, trackHandler, depth + 1)
          if (found != null) return found
        }
        "trak" -> {
          val found = walkMp4(raf, child, pos + size, null, depth + 1)
          if (found != null) return found
        }
        "hdlr" -> if (pos + size - child >= 12) {
          raf.seek(child + 8)
          trackHandler = readFour(raf)
        }
        "stsd" -> if (trackHandler == "vide") return sampleEntry(raf, child, pos + size)
      }
      pos += size
    }
    return null
  }

  private fun sampleEntry(raf: RandomAccessFile, payload: Long, end: Long): VideoStream? {
    if (end - payload < 16) return null
    raf.seek(payload + 4)
    val count = readU32Be(raf)
    if (count <= 0 || payload + 16 > end) return null
    raf.seek(payload + 8)
    val entrySize = readU32Be(raf)
    val fourcc = readFour(raf) ?: return null
    if (entrySize < 8 || payload + 8 + entrySize > end) return streamForFourcc(fourcc, 0)
    val entry = ByteArray(entrySize.toInt())
    raf.seek(payload + 8)
    raf.readFully(entry)
    return streamForFourcc(fourcc, profileInEntry(entry, fourcc))
  }

  private fun profileInEntry(entry: ByteArray, fourcc: String): Int {
    val mime = mimeFor(fourcc) ?: return 0
    val payload = boxPayload(entry, configBox(mime)) ?: return 0
    return when (mime) {
      DecoderSelection.HEVC ->
        if (payload.size >= 2 && payload[0] == 1.toByte()) payload[1].toInt() and 0x1F else 0
      DecoderSelection.AVC ->
        if (payload.size >= 2 && payload[0] == 1.toByte()) payload[1].toInt() and 0xFF else 0
      "video/x-vnd.on2.vp9" -> if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
      else -> 0
    }
  }

  private fun configBox(mime: String): String {
    return when (mime) {
      DecoderSelection.HEVC -> "hvcC"
      DecoderSelection.AVC -> "avcC"
      else -> "vpcC"
    }
  }

  private fun boxPayload(buf: ByteArray, type: String): ByteArray? {
    val marker = type.encodeToByteArray()
    var index = 0
    while (index + 8 < buf.size) {
      if (buf[index + 4] == marker[0] && buf[index + 5] == marker[1] &&
        buf[index + 6] == marker[2] && buf[index + 7] == marker[3]
      ) {
        val size = readU32Be(buf, index)
        if (size in 9..4096 && index + size <= buf.size) {
          return buf.copyOfRange(index + 8, index + size.toInt())
        }
      }
      index++
    }
    return null
  }

  private fun matroska(raf: RandomAccessFile): VideoStream? {
    val window = minOf(raf.length(), 1024L * 1024).toInt()
    if (window < 16) return null
    val buf = ByteArray(window)
    raf.seek(0)
    raf.readFully(buf)
    var bestAt = Int.MAX_VALUE
    var bestMime: String? = null
    for ((marker, mime) in mkvMarkers) {
      val at = indexOf(buf, marker.encodeToByteArray(), 0)
      if (at in 0 until bestAt) {
        bestAt = at
        bestMime = mime
      }
    }
    val mime = bestMime ?: return null
    val profile =
      if (mime == DecoderSelection.HEVC || mime == DecoderSelection.AVC) {
        codecPrivateProfile(buf, bestAt, mime)
      } else {
        0
      }
    return VideoStream(mime, profile)
  }

  private fun codecPrivateProfile(buf: ByteArray, from: Int, mime: String): Int {
    val regionEnd = minOf(buf.size, from + 256 * 1024)
    var index = from
    while (index + 4 < regionEnd) {
      if (buf[index] == 0x63.toByte() && buf[index + 1] == 0xA2.toByte()) {
        val vint = readVint(buf, index + 2) ?: break
        val dataAt = index + 2 + vint.second
        if (vint.first in 2..4096 && dataAt + 2 <= buf.size && buf[dataAt] == 1.toByte()) {
          return if (mime == DecoderSelection.HEVC) buf[dataAt + 1].toInt() and 0x1F
          else buf[dataAt + 1].toInt() and 0xFF
        }
      }
      index++
    }
    return 0
  }

  private fun streamForFourcc(fourcc: String, profile: Int): VideoStream? {
    val mime = mimeFor(fourcc) ?: return null
    return VideoStream(mime, profile)
  }

  private fun mimeFor(fourcc: String): String? {
    return when (fourcc.lowercase()) {
      "avc1", "avc3", "h264", "x264", "davc", "dvav" -> DecoderSelection.AVC
      "hevc", "h265", "hvc1", "hev1", "dvh1", "dvhe" -> DecoderSelection.HEVC
      "vp09", "vp90" -> "video/x-vnd.on2.vp9"
      "vp08", "vp80" -> "video/x-vnd.on2.vp8"
      "mp4v", "xvid", "divx", "dx50", "fmp4", "mpg4", "m4s2" -> "video/mp4v-es"
      "h263", "s263" -> "video/3gpp"
      "mp2v", "mpgv", "mpeg" -> "video/mpeg2"
      "av01" -> "video/av01"
      "wmv3" -> "video/x-ms-wmv"
      "wvc1" -> "video/wvc1"
      else -> null
    }
  }

  private val mkvMarkers =
    listOf(
      "V_MPEGH/ISO/HEVC" to DecoderSelection.HEVC,
      "V_MPEG4/ISO/AVC" to DecoderSelection.AVC,
      "V_VP9" to "video/x-vnd.on2.vp9",
      "V_VP8" to "video/x-vnd.on2.vp8",
      "V_MPEG4/ISO/ASP" to "video/mp4v-es",
      "V_MPEG4/ISO/SP" to "video/mp4v-es",
      "V_MPEG4/MS/V3" to "video/mp4v-es",
      "V_MPEG2" to "video/mpeg2",
      "V_AV1" to "video/av01",
    )

  private fun readVint(buf: ByteArray, offset: Int): Pair<Int, Int>? {
    if (offset >= buf.size) return null
    val first = buf[offset].toInt() and 0xFF
    if (first == 0) return null
    var marker = 0x80
    var length = 1
    while (length <= 8 && first and marker == 0) {
      marker = marker shr 1
      length++
    }
    if (marker == 0 || offset + length > buf.size) return null
    var value = first and (marker - 1)
    for (extra in 1 until length) {
      value = (value shl 8) or (buf[offset + extra].toInt() and 0xFF)
      if (value < 0) return null
    }
    return value to length
  }

  private fun indexOf(buf: ByteArray, needle: ByteArray, start: Int): Int {
    val last = buf.size - needle.size
    var index = start
    while (index <= last) {
      var match = true
      for (n in needle.indices) {
        if (buf[index + n] != needle[n]) {
          match = false
          break
        }
      }
      if (match) return index
      index++
    }
    return -1
  }

  private fun readFour(raf: RandomAccessFile): String? {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return null
    return buf.decodeToString()
  }

  private fun readU32Le(raf: RandomAccessFile): Long {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return -1
    return (buf[0].toLong() and 0xFF) or
      ((buf[1].toLong() and 0xFF) shl 8) or
      ((buf[2].toLong() and 0xFF) shl 16) or
      ((buf[3].toLong() and 0xFF) shl 24)
  }

  private fun readU32Be(raf: RandomAccessFile): Long {
    val buf = ByteArray(4)
    if (raf.read(buf) != 4) return -1
    return readU32Be(buf, 0)
  }

  private fun readU32Be(buf: ByteArray, offset: Int): Long {
    return ((buf[offset].toLong() and 0xFF) shl 24) or
      ((buf[offset + 1].toLong() and 0xFF) shl 16) or
      ((buf[offset + 2].toLong() and 0xFF) shl 8) or
      (buf[offset + 3].toLong() and 0xFF)
  }

  private fun readU64Be(raf: RandomAccessFile): Long {
    val high = readU32Be(raf)
    val low = readU32Be(raf)
    if (high < 0 || low < 0) return -1
    return (high shl 32) or low
  }
}
